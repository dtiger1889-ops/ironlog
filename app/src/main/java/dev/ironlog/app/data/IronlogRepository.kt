package dev.ironlog.app.data

import android.content.Context
import androidx.room.withTransaction
import dev.ironlog.app.data.importer.CardioTypeRepair
import dev.ironlog.app.data.importer.ImportResult
import dev.ironlog.app.data.importer.BundledHistoryImporter
import dev.ironlog.app.data.importer.CsvHistoryImporter
import dev.ironlog.app.data.importer.ImportedMetadataRepair
import dev.ironlog.app.export.CsvExporter
import dev.ironlog.app.export.ExportManager
import dev.ironlog.app.export.ExportManager.toDomain
import dev.ironlog.app.coach.CoachEngine
import dev.ironlog.app.gym.GymMetadata
import dev.ironlog.app.gym.MuscleAssistant
import dev.ironlog.app.metrics.PrEngine
import dev.ironlog.app.progression.Backtest
import dev.ironlog.app.progression.GoalPreset
import dev.ironlog.app.progression.SuggestionEngine
import dev.ironlog.app.progression.VolumeLandmarks
import dev.ironlog.app.reconcile.ExerciseReconciler
import dev.ironlog.app.session.DraftExercise
import dev.ironlog.app.session.DraftSet
import dev.ironlog.app.session.DraftWorkout
import dev.ironlog.app.session.TemplateSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A single template set's target values (no DB identity) — the editor's per-set unit. */
data class TemplateSetValue(
    val weightLb: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceMeters: Double? = null,
)

/** An exercise in a template: per-set targets (weight + reps) plus the legacy exercise-level
 *  target fields (kept for the active-workout header hint, derived from the sets on save). */
data class TemplateExerciseDetail(
    val exercise: Exercise,
    val targetSets: Int?,
    val targetRepsLow: Int?,
    val targetRepsHigh: Int?,
    val targetRestSec: Int?,
    val sets: List<TemplateSetValue> = emptyList(),
)

/** A template with its ordered exercises, resolved for starting a workout. */
data class TemplateDetail(
    val id: Long,
    val name: String,
    val note: String?,
    val exercises: List<Exercise>,
    val exerciseDetails: List<TemplateExerciseDetail> = emptyList(),
)

/** Result of a JSON backup restore. */
data class ImportJsonResult(
    val insertedExercises: Int,
    val insertedWorkouts: Int,
    val insertedSets: Int,
    val insertedMeasurements: Int,
)

/** Single entry point to persisted data; keeps Room threading out of the UI/ViewModel. */
class IronlogRepository(private val db: IronlogDatabase) {

    fun exercises(): Flow<List<Exercise>> = db.exerciseDao().allFlow()

    fun history(): Flow<List<Workout>> = db.workoutDao().recentFlow()

    fun templates(): Flow<List<Template>> = db.templateDao().allFlow()

    /** A template plus its ordered exercises -- the seed for a start-from-template draft. */
    suspend fun templateDetail(templateId: Long): TemplateDetail? = withContext(Dispatchers.IO) {
        val template = db.templateDao().byId(templateId) ?: return@withContext null
        val exercises = db.templateExerciseDao().exercisesForTemplate(templateId)
        val teRows = db.templateExerciseDao().rowsForTemplate(templateId)
        val detailsByExId = teRows.associateBy { it.exerciseId }
        TemplateDetail(
            id = template.id,
            name = template.name,
            note = template.note,
            exercises = exercises,
            exerciseDetails = exercises.map { ex ->
                val te = detailsByExId[ex.id]
                val tplSets = te?.id?.let { teId ->
                    db.templateSetDao().forTemplateExercise(teId).map { s ->
                        TemplateSetValue(s.weightLb, s.reps, s.seconds, s.distanceMeters)
                    }
                } ?: emptyList()
                TemplateExerciseDetail(
                    exercise = ex,
                    targetSets = te?.targetSets,
                    targetRepsLow = te?.targetRepsLow,
                    targetRepsHigh = te?.targetRepsHigh,
                    targetRestSec = te?.targetRestSec,
                    sets = tplSets,
                )
            },
        )
    }

    suspend fun counts(): ImportResult = withContext(Dispatchers.IO) {
        ImportResult(
            exercises = db.exerciseDao().count(),
            workouts = db.workoutDao().count(),
            sets = db.setEntryDao().count(),
        )
    }

    suspend fun setsForWorkout(workoutId: Long): List<SetEntry> =
        withContext(Dispatchers.IO) { db.setEntryDao().forWorkout(workoutId) }

    suspend fun lastPerformance(exerciseId: Long): SetEntry? =
        withContext(Dispatchers.IO) { db.setEntryDao().lastSetFor(exerciseId) }

    /** Previous session's sets for an exercise -- prefill + the gray PREVIOUS labels. */
    suspend fun lastSessionSets(exerciseId: Long): List<SetEntry> =
        withContext(Dispatchers.IO) { db.setEntryDao().lastSessionSets(exerciseId) }

    /** Persist a per-exercise rest duration so it is remembered next session. */
    suspend fun setExerciseRest(exerciseId: Long, seconds: Int?) = withContext(Dispatchers.IO) {
        db.exerciseDao().updateRest(exerciseId, seconds)
    }

    /** Persist a sticky note on the exercise catalog entry. */
    suspend fun setStickyNote(exerciseId: Long, note: String?) = withContext(Dispatchers.IO) {
        db.exerciseDao().updateStickyNote(exerciseId, note)
    }

    /** Load the current plate inventory (empty list = no plates configured). */
    suspend fun plateInventory(): List<PlateInventory> = withContext(Dispatchers.IO) {
        db.plateInventoryDao().all()
    }

    suspend fun upsertPlate(plate: PlateInventory) = withContext(Dispatchers.IO) {
        db.plateInventoryDao().upsert(plate)
    }

    /** Create (id null/0) or update a template's name + note + ordered exercises. Returns its id. */
    suspend fun saveTemplate(
        id: Long?,
        name: String,
        note: String?,
        exerciseDetails: List<TemplateExerciseDetail>,
    ): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val templateId = if (id == null || id <= 0L) {
                db.templateDao().insert(Template(name = name.ifBlank { "Template" }, note = note))
            } else {
                db.withTransaction {
                    // Room doesn't auto-generate UPDATE for note; use raw query
                    db.templateDao().updateNameAndNote(id, name.ifBlank { "Template" }, note)
                    db.templateExerciseDao().deleteForTemplate(id)
                }
                id
            }
            exerciseDetails.forEachIndexed { position, detail ->
                // Derive the legacy exercise-level target hint from the per-set rows so the
                // active-workout header still shows "N sets × low-high" without a second source.
                val repsLogged = detail.sets.mapNotNull { it.reps }
                val derivedSets = detail.sets.size.takeIf { it > 0 } ?: detail.targetSets
                val teId = db.templateExerciseDao().insert(
                    TemplateExercise(
                        templateId = templateId,
                        exerciseId = detail.exercise.id,
                        position = position,
                        targetSets = derivedSets,
                        targetRepsLow = repsLogged.minOrNull() ?: detail.targetRepsLow,
                        targetRepsHigh = repsLogged.maxOrNull() ?: detail.targetRepsHigh,
                        targetRestSec = detail.targetRestSec,
                    ),
                )
                detail.sets.forEachIndexed { sp, sv ->
                    db.templateSetDao().insert(
                        TemplateSet(
                            templateExerciseId = teId,
                            position = sp,
                            weightLb = sv.weightLb,
                            reps = sv.reps,
                            seconds = sv.seconds,
                            distanceMeters = sv.distanceMeters,
                        ),
                    )
                }
            }
            templateId
        }
    }

    /**
     * One-time backfill: give legacy templates (exercise list only, no per-set rows) a persisted
     * per-set structure snapshotted from the last logged session of each exercise. Idempotent —
     * only touches template exercises that still have zero TemplateSet rows. After this, the
     * TEMPLATE (not the last session) is the source of a draft's set structure, so removing a set
     * mid-workout can never shrink the next session's prefill.
     */
    suspend fun backfillTemplateSetsIfNeeded() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.templateDao().allList().forEach { template ->
                db.templateExerciseDao().rowsForTemplate(template.id).forEach { te ->
                    if (db.templateSetDao().forTemplateExercise(te.id).isEmpty()) {
                        db.setEntryDao().lastSessionSets(te.exerciseId)
                            .filter { !it.isWarmup }
                            .forEachIndexed { i, p ->
                                db.templateSetDao().insert(
                                    TemplateSet(
                                        templateExerciseId = te.id,
                                        position = i,
                                        weightLb = p.weightLb,
                                        reps = p.reps,
                                        seconds = p.seconds,
                                        distanceMeters = p.distanceMeters,
                                    ),
                                )
                            }
                    }
                }
            }
        }
    }

    /**
     * F1 one-time repair: pre-2026-07-18 imports turned the previous tracker's "Rest Timer"/"Note" metadata
     * rows into phantom sets (the "60s" rows in history) and let them skew type classification
     * (Chest Dip tied into TIMED by its own rest-timer rows).  Deletes exactly those rows,
     * corrects misclassified exercise types, and removes junk seconds-only template targets
     * snapshotted from them.  Native data is safe: only workouts with a importedWorkoutNumber are
     * touched, TIMED exercises use an exact (legacySetOrder, seconds) fingerprint, and the pass
     * is flag-guarded to run once.  Matching rules live in [ImportedMetadataRepair] (JVM-tested).
     */
    suspend fun repairImportedMetadataIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val settings = AppSettings(context)
        if (settings.importedMetadataRepairDone.first()) return@withContext
        val csv = runCatching {
            context.assets.open(BundledHistoryImporter.ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext
        val parsed = CsvHistoryImporter.parse(csv)
        val typeByName = parsed.exercises.associate { it.name to it.type }
        db.withTransaction {
            val workoutsByNumber = db.workoutDao().all()
                .filter { it.importedWorkoutNumber != null }
                .associateBy { it.importedWorkoutNumber }
            val exercisesByName = db.exerciseDao().all()
                .filter { !it.catalogOnly }
                .associateBy { it.name }

            parsed.metadataRows
                .groupBy { it.importedWorkoutNumber to it.exerciseName }
                .forEach { (key, metaRows) ->
                    val workout = workoutsByNumber[key.first] ?: return@forEach
                    val exercise = exercisesByName[key.second] ?: return@forEach
                    val stored = db.setEntryDao().forWorkout(workout.id)
                        .filter { it.exerciseId == exercise.id }
                        .map {
                            ImportedMetadataRepair.StoredSet(
                                it.id, it.setOrder, it.weightLb, it.reps, it.seconds, it.distanceMeters,
                            )
                        }
                    val junk = ImportedMetadataRepair.junkSetIds(
                        metaRows, stored,
                        timedExercise = typeByName[key.second]
                            .let { it == ExerciseType.TIMED || it == ExerciseType.CARDIO },
                    )
                    if (junk.isNotEmpty()) db.setEntryDao().deleteByIds(junk)
                }

            // Reclassify exercises the junk rows had tipped into the wrong type.
            parsed.exercises.forEach { pe ->
                val dbEx = exercisesByName[pe.name] ?: return@forEach
                if (dbEx.type != pe.type) db.exerciseDao().updateType(dbEx.id, pe.type)
            }

            // Junk template targets: a seconds-only target on a non-TIMED exercise can only have
            // been snapshotted from a junk set by the template backfill.
            val exercisesById = db.exerciseDao().all().associateBy { it.id }
            db.templateDao().allList().forEach { template ->
                db.templateExerciseDao().rowsForTemplate(template.id).forEach inner@{ te ->
                    val exType = exercisesById[te.exerciseId]?.type ?: return@inner
                    if (exType == ExerciseType.TIMED || exType == ExerciseType.CARDIO) return@inner
                    val junkTargets = db.templateSetDao().forTemplateExercise(te.id)
                        .filter {
                            it.weightLb == null && it.reps == null &&
                                it.distanceMeters == null && it.seconds != null
                        }
                        .map { it.id }
                    if (junkTargets.isNotEmpty()) db.templateSetDao().deleteByIds(junkTargets)
                }
            }
        }
        settings.setImportedMetadataRepairDone()
    }

    /**
     * One-time repair for databases imported before CARDIO existed: any logged exercise whose
     * own sets are mostly distance-and-time (treadmill, bike) is re-typed CARDIO so both
     * columns appear.  Nothing is deleted -- only `exercises.type` changes, and the set rows
     * already hold both values.  Flag-guarded to run once, same shape as the F1 metadata
     * repair above; the decision rule lives in [CardioTypeRepair] (JVM-tested).
     */
    suspend fun repairCardioExerciseTypesIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val settings = AppSettings(context)
        if (settings.cardioTypeRepairDone.first()) return@withContext
        db.withTransaction {
            db.exerciseDao().all()
                .filter { !it.catalogOnly && it.type != ExerciseType.CARDIO }
                .forEach { ex ->
                    val stored = db.setEntryDao().allForExercise(ex.id).map {
                        CardioTypeRepair.StoredSet(it.weightLb, it.reps, it.seconds, it.distanceMeters)
                    }
                    if (CardioTypeRepair.shouldBeCardio(stored)) {
                        db.exerciseDao().updateType(ex.id, ExerciseType.CARDIO)
                    }
                }
            // Never-logged catalog rows for cardio machines were seeded TIMED before CARDIO
            // existed; relabel by catalog category so a first log opens with MI + TIME.
            db.exerciseDao().all()
                .filter { it.catalogOnly && it.type == ExerciseType.TIMED && it.category == "cardio" }
                .forEach { db.exerciseDao().updateType(it.id, ExerciseType.CARDIO) }
        }
        settings.setCardioTypeRepairDone()
    }

    /** Per-exercise "Logs as" override -- changes which columns show, never any logged data. */
    suspend fun setExerciseType(exerciseId: Long, type: ExerciseType) = withContext(Dispatchers.IO) {
        db.exerciseDao().updateType(exerciseId, type)
    }

    /** Apply the user's OPT-IN finish-dialog template updates. No-op unless a box was ticked. */
    suspend fun updateTemplateFromDraft(
        draft: DraftWorkout,
        updateValues: Boolean,
        updateStructure: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (!updateValues && !updateStructure) return@withContext
        val templateId = draft.templateId ?: return@withContext
        val detail = templateDetail(templateId) ?: return@withContext
        val exercisesById = db.exerciseDao().all().associateBy { it.id }
        val updated = TemplateSync.updatedDetails(
            detail.exerciseDetails, draft, exercisesById, updateValues, updateStructure,
        )
        saveTemplate(templateId, detail.name, detail.note, updated)
    }

    /** Move a template one slot up/down on the Workout tab (materializes positions on first use). */
    suspend fun moveTemplate(templateId: Long, up: Boolean) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val all = db.templateDao().allList()
            val idx = all.indexOfFirst { it.id == templateId }
            if (idx == -1) return@withTransaction
            val target = if (up) idx - 1 else idx + 1
            if (target !in all.indices) return@withTransaction
            val reordered = all.toMutableList().apply { add(target, removeAt(idx)) }
            reordered.forEachIndexed { i, t ->
                if (t.position != i) db.templateDao().updatePosition(t.id, i)
            }
        }
    }

    suspend fun deleteTemplate(templateId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.templateExerciseDao().deleteForTemplate(templateId)
            db.templateDao().deleteById(templateId)
        }
    }

    /** Add a brand-new exercise to the catalog (from the picker's "create" action). */
    suspend fun addExercise(name: String, type: ExerciseType): Exercise =
        withContext(Dispatchers.IO) {
            val id = db.exerciseDao().insert(Exercise(name = name, type = type))
            Exercise(id = id, name = name, type = type)
        }

    // --- M5: gym profiles + muscle assistant ---

    fun gymProfilesFlow(): Flow<List<GymProfile>> = db.gymProfileDao().allFlow()

    suspend fun activeGymProfile(): GymProfile? =
        withContext(Dispatchers.IO) { db.gymProfileDao().activeProfile() }

    /** Equipment tokens that are ON for a profile (a missing row = OFF). */
    suspend fun equipmentForProfile(profileId: Long): Set<String> =
        withContext(Dispatchers.IO) { db.gymProfileEquipmentDao().tokensForProfile(profileId).toSet() }

    /** Create a gym profile with every equipment token ON by default; make it active if it's the first. */
    suspend fun createGymProfile(name: String): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val isFirst = db.gymProfileDao().all().isEmpty()
            val id = db.gymProfileDao().insert(
                GymProfile(name = name.ifBlank { "Gym" }, isActive = isFirst),
            )
            db.gymProfileEquipmentDao().insertAll(
                GymMetadata.ALL_EQUIPMENT_TOKENS.map { GymProfileEquipment(profileId = id, equipmentToken = it) },
            )
            id
        }
    }

    suspend fun renameGymProfile(id: Long, name: String) =
        withContext(Dispatchers.IO) { db.gymProfileDao().rename(id, name.ifBlank { "Gym" }) }

    suspend fun deleteGymProfile(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.gymProfileEquipmentDao().clearForProfile(id)
            db.gymProfileDao().deleteById(id)
        }
    }

    suspend fun setActiveGymProfile(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.gymProfileDao().clearActive()
            db.gymProfileDao().setActive(id)
        }
    }

    suspend fun setEquipmentToken(profileId: Long, token: String, on: Boolean) = withContext(Dispatchers.IO) {
        if (on) {
            db.gymProfileEquipmentDao().addToken(GymProfileEquipment(profileId = profileId, equipmentToken = token))
        } else {
            db.gymProfileEquipmentDao().removeToken(profileId, token)
        }
    }

    /** Equipment available right now (active profile's ON tokens; everything if no active profile). */
    private suspend fun availableEquipment(): Set<String> {
        val active = db.gymProfileDao().activeProfile() ?: return GymMetadata.ALL_EQUIPMENT_TOKENS
        return db.gymProfileEquipmentDao().tokensForProfile(active.id).toSet()
    }

    /** Per-exercise logged-set frequency (the finder/swap preference tie-break). */
    private fun frequencyMap(sets: List<SetEntry>): Map<Long, Int> =
        sets.groupingBy { it.exerciseId }.eachCount()

    /** (b) Finder: catalog exercises whose primary muscle matches, filtered to the active gym. */
    suspend fun findExercisesForMuscle(targetMuscle: String): List<Exercise> = withContext(Dispatchers.IO) {
        MuscleAssistant.findForMuscle(
            exercises = db.exerciseDao().all(),
            targetMuscle = targetMuscle,
            availableEquipment = availableEquipment(),
            exerciseFrequency = frequencyMap(db.setEntryDao().all()),
        )
    }

    /** (c) Busy-station swap: ranked substitutes for [source] on other available equipment. */
    suspend fun swapAlternatives(source: Exercise): List<MuscleAssistant.ScoredAlternative> =
        withContext(Dispatchers.IO) {
            MuscleAssistant.rankAlternatives(
                source = source,
                candidates = db.exerciseDao().all(),
                availableEquipment = availableEquipment(),
                exerciseFrequency = frequencyMap(db.setEntryDao().all()),
            )
        }

    /** Distinct primary-muscle tokens present in the catalog, for the finder's muscle picker. */
    suspend fun catalogMuscles(): List<String> = withContext(Dispatchers.IO) {
        db.exerciseDao().all()
            .flatMap { it.primaryMuscles?.map { m -> m.lowercase() } ?: emptyList() }
            .distinct()
            .sorted()
    }

    /** Persist a finished draft (workout header + its sets) in one transaction.
     *  PR tags are computed per-exercise against historical working sets before insert.
     *  Warm-up sets are persisted but never tagged isPR (enforced in PrEngine). */
    suspend fun finish(draft: DraftWorkout, elapsedSec: Int? = null): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val workoutId = db.workoutDao().insert(draft.toWorkout(elapsedSec))
            val rawSets = draft.toSetEntries(workoutId)

            // Tag PRs per exercise: fetch all historical sets BEFORE inserting this workout.
            // PrEngine internally filters out warm-ups from both history and new sets.
            val taggedSets = rawSets
                .groupBy { it.exerciseId }
                .flatMap { (exerciseId, newSets) ->
                    val history = db.setEntryDao().allForExercise(exerciseId)
                    PrEngine.tagPRs(newSets, history)
                }

            if (taggedSets.isNotEmpty()) db.setEntryDao().insertAll(taggedSets)

            // Any accepted progression hint for an exercise just logged is now spent: mark it
            // CONSUMED so the gray hint stops showing and the rule re-evaluates from fresh data.
            rawSets.map { it.exerciseId }.toSet().forEach { exId ->
                db.progressionSuggestionDao().markConsumedFor(exId)
            }
            workoutId
        }
    }

    suspend fun importBundledHistory(context: Context): ImportResult =
        BundledHistoryImporter.importFromAssets(context, db)

    suspend fun deleteWorkout(workoutId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.setEntryDao().deleteForWorkout(workoutId)
            db.workoutDao().deleteById(workoutId)
        }
    }

    // --- Reconciliation ---

    /**
     * Apply confirmed free-exercise-db metadata to one exercise.
     * NEVER called without an explicit user confirmation on the reconciliation screen.
     * Copies metadata by freeDbId; never touches name/type/sets/rest.
     */
    suspend fun applyConfirmedMetadata(
        exerciseId: Long,
        candidate: ExerciseReconciler.FreeDbEntry,
    ) = withContext(Dispatchers.IO) {
        db.exerciseDao().applyMetadata(
            id = exerciseId,
            bodyPart = candidate.category,
            category = candidate.category,
            equipment = candidate.equipment,
            mechanic = candidate.mechanic,
            force = candidate.force,
            instructions = Json.encodeToString(candidate.instructions),
            primaryMuscles = Json.encodeToString(candidate.primaryMuscles),
            secondaryMuscles = Json.encodeToString(candidate.secondaryMuscles),
            imageRef = candidate.imageRef,
            freeDbId = candidate.id,
        )
    }

    /** Load all exercises with their IDs (for reconciliation proposals). */
    suspend fun exerciseIdNamePairs(): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        db.exerciseDao().all().map { it.id to it.name }
    }

    /** Manually map a logged exercise to a chosen catalog exercise — copies its muscle/equipment/
     *  mechanic/force/instructions + movement metadata. Used by the "Confirm muscle maps" picker
     *  when the auto-matcher found nothing (or the wrong thing). */
    suspend fun copyMetadataFromCatalog(targetId: Long, sourceCatalogId: Long) = withContext(Dispatchers.IO) {
        val src = db.exerciseDao().all().firstOrNull { it.id == sourceCatalogId } ?: return@withContext
        db.exerciseDao().applyMetadata(
            id = targetId,
            bodyPart = src.bodyPart,
            category = src.category,
            equipment = src.equipment,
            mechanic = src.mechanic,
            force = src.force,
            instructions = Json.encodeToString(src.instructions ?: emptyList<String>()),
            primaryMuscles = Json.encodeToString(src.primaryMuscles ?: emptyList<String>()),
            secondaryMuscles = Json.encodeToString(src.secondaryMuscles ?: emptyList<String>()),
            imageRef = src.imageRef,
            freeDbId = src.freeDbId,
        )
        db.exerciseDao().updateGymMetadata(targetId, src.movementPattern, src.deltHead)
    }

    /**
     * Auto-map unmapped LOGGED exercises to free-exercise-db muscle metadata for confident
     * (EXACT / FUZZY) name matches, so the muscle finder, swap, and coverage work without the
     * user hand-confirming all 54. Ambiguous (NONE) matches are left for the manual
     * "Confirm muscle maps" flow. Guarded: no-op once nothing is unmapped. Returns # mapped.
     */
    suspend fun reconcileLoggedExercisesIfNeeded(context: Context): Int = withContext(Dispatchers.IO) {
        val unmapped = db.exerciseDao().all()
            .filter { !it.catalogOnly && it.primaryMuscles.isNullOrEmpty() }
        if (unmapped.isEmpty()) return@withContext 0

        val json = context.assets.open("exercises.json").bufferedReader().use { it.readText() }
        val freeDb = ExerciseReconciler.parseDb(json)
        val proposals = ExerciseReconciler.propose(unmapped.map { it.id to it.name }, freeDb)

        var applied = 0
        db.withTransaction {
            for (p in proposals) {
                val cand = p.candidate ?: continue
                if (p.matchType == ExerciseReconciler.MatchType.NONE) continue
                db.exerciseDao().applyMetadata(
                    id = p.exerciseId,
                    bodyPart = cand.category,
                    category = cand.category,
                    equipment = cand.equipment,
                    mechanic = cand.mechanic,
                    force = cand.force,
                    instructions = Json.encodeToString(cand.instructions),
                    primaryMuscles = Json.encodeToString(cand.primaryMuscles),
                    secondaryMuscles = Json.encodeToString(cand.secondaryMuscles),
                    imageRef = cand.imageRef,
                    freeDbId = cand.id,
                )
                db.exerciseDao().updateGymMetadata(
                    p.exerciseId,
                    GymMetadata.deriveMovementPattern(p.exerciseName, cand.force, cand.primaryMuscles),
                    GymMetadata.deriveDeltHead(p.exerciseName, cand.primaryMuscles),
                )
                applied++
            }
        }
        applied
    }

    /** Load only exercises that have no muscle metadata yet (primaryMuscles null or empty). */
    suspend fun unmappedExerciseIdNamePairs(): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        db.exerciseDao().all()
            .filter { it.primaryMuscles.isNullOrEmpty() }
            .map { it.id to it.name }
    }

    /** All sets for a given exercise across all workouts (used by coverage-lite). */
    suspend fun allSetsForExercise(exerciseId: Long): List<SetEntry> =
        withContext(Dispatchers.IO) { db.setEntryDao().allForExercise(exerciseId) }

    /** All set entries across all workouts, for muscle-coverage computation. */
    suspend fun allSets(): List<SetEntry> = withContext(Dispatchers.IO) {
        db.setEntryDao().all()
    }

    // --- M4: progression engine ---

    @Serializable
    private data class LandmarkRow(
        val muscle: String,
        val mv: Double,
        val mev: Double,
        val mav: Double,
        val mrv: Double,
    )

    @Serializable
    private data class LandmarkFile(val landmarks: List<LandmarkRow>)

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * Seed the muscle_volume_targets table from the bundled assets/volume_landmarks.json
     * (a labeled practitioner model -- NOT from his history). One-time: only if empty.
     */
    suspend fun seedVolumeLandmarksIfEmpty(context: Context) = withContext(Dispatchers.IO) {
        if (db.muscleVolumeTargetDao().count() > 0) return@withContext
        val text = context.assets.open("volume_landmarks.json").bufferedReader().use { it.readText() }
        val parsed = lenientJson.decodeFromString<LandmarkFile>(text)
        db.muscleVolumeTargetDao().upsertAll(
            parsed.landmarks.map {
                MuscleVolumeTarget(
                    muscle = it.muscle.lowercase(),
                    mv = it.mv, mev = it.mev, mav = it.mav, mrv = it.mrv,
                )
            },
        )
    }

    suspend fun volumeTargets(): List<MuscleVolumeTarget> = withContext(Dispatchers.IO) {
        db.muscleVolumeTargetDao().all()
    }

    /**
     * M5 (d): per-muscle coverage status — volume band (vs MEV/MAV/MRV), never-isolated, and
     * stale flags. Drives the Profile coverage-dashboard band upgrade. Reads only.
     */
    suspend fun muscleCoverageStatus(nowMillis: Long): List<MuscleAssistant.MuscleStatus> =
        withContext(Dispatchers.IO) {
            val sets = db.setEntryDao().all()
            val workouts = db.workoutDao().all()
            val exercises = db.exerciseDao().all()
            val targets = db.muscleVolumeTargetDao().all().associateBy { it.muscle.lowercase() }
            MuscleAssistant.coverageStatus(sets, workouts, exercises, targets, nowMillis)
        }

    /**
     * Compute the live Coach feed: per-exercise progression suggestions + muscle-volume nudges,
     * with already accepted/dismissed ones suppressed. Reads only -- writes nothing.
     */
    suspend fun progressionSuggestions(
        defaultGoal: GoalPreset,
        nowMillis: Long,
    ): List<SuggestionEngine.Suggestion> = withContext(Dispatchers.IO) {
        val sets = db.setEntryDao().all()
        val workouts = db.workoutDao().all()
        val exercises = db.exerciseDao().all()
        val targets = db.muscleVolumeTargetDao().all()
        val targetsByMuscle = targets.associateBy { it.muscle.lowercase() }
        val handled = db.progressionSuggestionDao().handledDedupKeys().toSet()

        val inputs = SuggestionEngine.buildExerciseInputs(sets, workouts, exercises)
        val volumeCounts = VolumeLandmarks.weeklySets(sets, workouts, exercises, nowMillis)
        SuggestionEngine.generateAll(inputs, defaultGoal, volumeCounts, targetsByMuscle, handled)
    }

    /** Record the user's verdict on a suggestion. NEVER writes a SetEntry or edits a Template.
     *  An ACCEPTED suggestion stores its suggested load/time as a prefill HINT that surfaces on the
     *  next draft of that exercise (see [coachHintFor]); it is never written into a set. */
    suspend fun recordSuggestionVerdict(
        suggestion: SuggestionEngine.Suggestion,
        accepted: Boolean,
        nowMillis: Long,
    ) = withContext(Dispatchers.IO) {
        db.progressionSuggestionDao().insert(
            ProgressionSuggestion(
                exerciseId = suggestion.exerciseId,
                kind = suggestion.kind.name,
                payload = suggestion.message,
                citation = suggestion.citation,
                status = if (accepted) "ACCEPTED" else "DISMISSED",
                dedupKey = suggestion.dedupKey,
                createdAt = nowMillis,
                hintWeightLb = if (accepted) suggestion.machineWeightLb else null,
                hintSeconds = if (accepted) suggestion.machineSeconds else null,
            ),
        )
    }

    /** A gray "Coach:" hint string for the active-workout header, from the latest ACCEPTED
     *  suggestion for this exercise (null = none pending). Reference only -- never auto-filled. */
    suspend fun coachHintFor(exerciseId: Long): String? = withContext(Dispatchers.IO) {
        val s = db.progressionSuggestionDao().latestAcceptedFor(exerciseId) ?: return@withContext null
        fun lb(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
        when (s.kind) {
            "ADD_LOAD" -> s.hintWeightLb?.let { "try ${lb(it)} lb" } ?: "ready to add a level"
            "DELOAD" -> s.hintWeightLb?.let { "deload to ${lb(it)} lb" } ?: "deload ~10%"
            "TIMED_PROGRESS" -> s.hintSeconds?.let { "try ${it}s" } ?: "progress the hold"
            else -> null
        }
    }

    /** Persist per-exercise progression config (goal preset / increment / enable toggle). */
    suspend fun setExerciseProgression(
        exerciseId: Long,
        goalPreset: GoalPreset?,
        incrementLb: Double?,
        progressionEnabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        db.exerciseDao().updateProgression(
            id = exerciseId,
            goalPreset = goalPreset?.name,
            incrementLb = incrementLb,
            progressionEnabled = progressionEnabled,
        )
    }

    // --- M3: catalog seeding & browse ---

    @kotlinx.serialization.Serializable
    private data class CatalogEntry(
        val name: String,
        val force: String? = null,
        val level: String? = null,
        val mechanic: String? = null,
        val equipment: String? = null,
        val primaryMuscles: List<String> = emptyList(),
        val secondaryMuscles: List<String> = emptyList(),
        val instructions: List<String> = emptyList(),
        val category: String? = null,
        val images: List<String> = emptyList(),
        val id: String? = null,
    )

    /**
     * Seed catalog-only exercise rows from exercises.json (one-time; guarded by catalogOnly count).
     * Runs INSERT OR IGNORE so the user's logged exercises are never overwritten.
     * Sets movementPattern + deltHead algorithmically (same as M5 GymMetadata derivation).
     */
    suspend fun seedCatalogIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        if (db.exerciseDao().countCatalogOnly() > 0) return@withContext

        val text = context.assets.open("exercises.json").bufferedReader().use { it.readText() }
        val entries = lenientJson.decodeFromString<List<CatalogEntry>>(text)

        val batch = entries.map { e ->
            Exercise(
                name = e.name,
                type = classifyCatalogType(e.category, e.equipment),
                bodyPart = e.category,
                category = e.category,
                equipment = e.equipment,
                mechanic = e.mechanic,
                force = e.force,
                instructions = e.instructions.takeIf { it.isNotEmpty() },
                primaryMuscles = e.primaryMuscles.takeIf { it.isNotEmpty() },
                secondaryMuscles = e.secondaryMuscles.takeIf { it.isNotEmpty() },
                imageRef = e.images.firstOrNull(),
                freeDbId = e.id,
                catalogOnly = true,
                movementPattern = GymMetadata.deriveMovementPattern(e.name, e.force, e.primaryMuscles),
                deltHead = GymMetadata.deriveDeltHead(e.name, e.primaryMuscles),
            )
        }
        db.exerciseDao().insertAllIgnore(batch)
    }

    private fun classifyCatalogType(category: String?, equipment: String?): ExerciseType = when {
        category == "cardio" -> ExerciseType.CARDIO
        category == "stretching" -> ExerciseType.TIMED
        equipment == "body only" -> ExerciseType.BODYWEIGHT
        else -> ExerciseType.WEIGHTED
    }

    /** Sets for one exercise (non-warm-up only) paired with the workout's startTime, for charts. */
    suspend fun exerciseSetsWithTime(exerciseId: Long): List<SetWithWorkoutTime> =
        withContext(Dispatchers.IO) { db.setEntryDao().setsWithWorkoutTimeForExercise(exerciseId) }

    /**
     * Load an existing workout + its sets into a DraftWorkout for editing.
     * The returned draft carries [editingWorkoutId] so [finishEdit] knows to replace it.
     */
    suspend fun loadWorkoutForEditing(workoutId: Long): DraftWorkout? =
        withContext(Dispatchers.IO) {
            val workout = db.workoutDao().byId(workoutId) ?: return@withContext null
            val sets = db.setEntryDao().forWorkout(workoutId)
            val exerciseIds = sets.map { it.exerciseId }.distinct()
            val exerciseById = exerciseIds.mapNotNull { id ->
                db.exerciseDao().byId(id)?.let { it.id to it }
            }.toMap()

            val grouped = sets.groupBy { it.exerciseId }
            val draftExercises = exerciseIds.mapNotNull { exId ->
                val ex = exerciseById[exId] ?: return@mapNotNull null
                val exSets = grouped[exId] ?: emptyList()
                DraftExercise(
                    exerciseId = ex.id,
                    name = ex.name,
                    type = ex.type,
                    restSeconds = ex.restSeconds,
                    stickyNote = ex.stickyNote,
                    supersetGroup = null,
                    sets = exSets.map { s ->
                        DraftSet(
                            id = s.id,
                            weightLb = s.weightLb,
                            reps = s.reps,
                            seconds = s.seconds,
                            distanceMeters = s.distanceMeters,
                            done = true, // all persisted sets count as done
                            isWarmup = s.isWarmup,
                        )
                    },
                )
            }

            DraftWorkout(
                name = workout.name,
                startedAtMillis = workout.startTime,
                notes = workout.notes,
                exercises = draftExercises,
                editingWorkoutId = workoutId,
            )
        }

    /**
     * Save edits to an existing workout: delete the old sets (cascade), re-insert the edited ones.
     * PR tags are recomputed against the pre-edit history (excluding the workout being replaced).
     * Never touches a Template (the hard guardrail). Returns the same [workoutId].
     */
    suspend fun finishEdit(draft: DraftWorkout): Long = withContext(Dispatchers.IO) {
        val workoutId = draft.editingWorkoutId ?: error("finishEdit called without editingWorkoutId")
        db.withTransaction {
            // Clear old sets (keep the Workout row; just replace its sets + note)
            db.setEntryDao().deleteForWorkout(workoutId)
            db.workoutDao().updateNotes(workoutId, draft.notes)

            val rawSets = draft.toSetEntries(workoutId)
            val taggedSets = rawSets
                .groupBy { it.exerciseId }
                .flatMap { (exerciseId, newSets) ->
                    // Fetch history excluding this workout for correct PR detection.
                    val history = db.setEntryDao().allForExercise(exerciseId)
                        .filter { it.workoutId != workoutId }
                    PrEngine.tagPRs(newSets, history)
                }
            if (taggedSets.isNotEmpty()) db.setEntryDao().insertAll(taggedSets)
            workoutId
        }
    }

    // --- M6: measurements ---

    fun measurements(): Flow<List<Measurement>> = db.measurementDao().allFlow()

    suspend fun insertMeasurement(m: Measurement): Long = withContext(Dispatchers.IO) {
        db.measurementDao().insert(m)
    }

    suspend fun deleteMeasurement(id: Long) = withContext(Dispatchers.IO) {
        db.measurementDao().deleteById(id)
    }

    /** Find an existing HC-synced measurement by record id (for dedup on repeated sync). */
    suspend fun findMeasurementByHcId(hcId: String): Measurement? = withContext(Dispatchers.IO) {
        db.measurementDao().findByHcId(hcId)
    }

    /** All measurements of a given type more recent than [sinceMs] (HC dedup window). */
    suspend fun measurementsSince(type: String, sinceMs: Long): List<Measurement> =
        withContext(Dispatchers.IO) { db.measurementDao().sinceMs(type, sinceMs) }

    // --- M6: export / import ---

    /** Export full history as an import-compatible CSV (round-trips through CsvHistoryImporter). */
    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val workouts = db.workoutDao().all().sortedBy { it.startTime }
        val allSets = db.setEntryDao().all()
        val exercises = db.exerciseDao().all()
        CsvExporter.exportCsv(
            workouts = workouts,
            setsByWorkout = allSets.groupBy { it.workoutId },
            exercisesById = exercises.associateBy { it.id },
        )
    }

    /** Export everything (exercises, workouts, sets, measurements) as a versioned JSON string. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        ExportManager.exportJson(
            // Catalog-only seed rows aren't user data — exclude them so backups carry
            // just the user's logged exercises (the catalog re-seeds on install).
            exercises = db.exerciseDao().all().filter { !it.catalogOnly },
            workouts = db.workoutDao().all(),
            sets = db.setEntryDao().all(),
            measurements = db.measurementDao().all(),
        )
    }

    /**
     * Non-destructive merge restore from a JSON backup. Existing rows are kept;
     * exercises are matched by name (INSERT OR IGNORE), imported workouts by
     * importedWorkoutNumber and natively-logged workouts by (name + startTime), measurements
     * by (type + timestamp).
     * Returns a summary of how many rows were inserted.
     */
    suspend fun importJsonBackup(jsonText: String): ImportJsonResult = withContext(Dispatchers.IO) {
        val export = ExportManager.importJson(jsonText)
        var insertedEx = 0; var insertedWo = 0; var insertedSets = 0; var insertedMeas = 0

        db.withTransaction {
            // Exercises: insert-or-ignore by name
            export.exercises.forEach { dto ->
                val inserted = db.exerciseDao().insertIgnore(dto.toDomain())
                if (inserted != -1L) insertedEx++
            }

            // Build name→id map for resolving exerciseId in sets
            val exByName = db.exerciseDao().all().associateBy { it.name }

            // Workouts: skip duplicates. Imported workouts dedup by importedWorkoutNumber;
            // natively-logged workouts have no number, so dedup them by (name + startTime) —
            // otherwise re-importing the same backup re-inserts every native workout (and its
            // sets), silently doubling the history. Both keys are checked.
            val existingWorkouts = db.workoutDao().all()
            val existingNums = existingWorkouts.mapNotNull { it.importedWorkoutNumber }.toSet()
            val existingWoKeys = existingWorkouts.map { "${it.name}:${it.startTime}" }.toSet()
            val insertedWorkoutIdMap = mutableMapOf<Long, Long>() // old id → new id
            export.workouts.forEach { dto ->
                val candidate = dto.toDomain()
                val skip = (candidate.importedWorkoutNumber != null &&
                    candidate.importedWorkoutNumber in existingNums) ||
                    "${candidate.name}:${candidate.startTime}" in existingWoKeys
                if (!skip) {
                    val newId = db.workoutDao().insert(candidate.copy(id = 0))
                    insertedWorkoutIdMap[dto.id] = newId
                    insertedWo++
                }
            }

            // Sets: only for newly inserted workouts; re-map exerciseId by name
            export.sets.forEach { dto ->
                val newWorkoutId = insertedWorkoutIdMap[dto.workoutId] ?: return@forEach
                val exName = export.exercises.find { it.id == dto.exerciseId }?.name
                    ?: return@forEach
                val exId = exByName[exName]?.id ?: return@forEach
                db.setEntryDao().insert(dto.toDomain().copy(id = 0, workoutId = newWorkoutId, exerciseId = exId))
                insertedSets++
            }

            // Measurements: skip if (type + timestamp) already exists
            val existingKeys = db.measurementDao().all()
                .map { "${it.type}:${it.timestamp}" }
                .toSet()
            export.measurements.forEach { dto ->
                val key = "${dto.type}:${dto.timestamp}"
                if (key !in existingKeys) {
                    db.measurementDao().insert(dto.toDomain().copy(id = 0))
                    insertedMeas++
                }
            }
        }

        ImportJsonResult(insertedEx, insertedWo, insertedSets, insertedMeas)
    }

    /** F2: per-exercise plate-calculator override (show/hide mode + base/bar weight). */
    suspend fun setExercisePlateCalcConfig(exerciseId: Long, mode: String?, barWeightLb: Double?) =
        withContext(Dispatchers.IO) {
            db.exerciseDao().updatePlateCalcConfig(exerciseId, mode, barWeightLb)
        }

    /** Read-only backtest of the rules over the full history (writes nothing). */
    suspend fun runBacktest(defaultGoal: GoalPreset): Backtest.Result = withContext(Dispatchers.IO) {
        Backtest.run(
            sets = db.setEntryDao().all(),
            workouts = db.workoutDao().all(),
            exercises = db.exerciseDao().all(),
            defaultGoal = defaultGoal,
        )
    }

    // --- M7: coach summary ---

    /**
     * Generate a CoachSummaryData from the just-finished workout and persist it.
     * Called async after [finish] so it never delays the UX. Pulls coverage fresh from DB
     * so the summary reflects the workout that was just saved.
     *
     * @param workoutId   the id returned by [finish]
     * @param suggestions the live SuggestionEngine feed (already computed; passed from ViewModel)
     */
    suspend fun generateAndSaveCoachSummary(
        workoutId: Long,
        suggestions: List<SuggestionEngine.Suggestion>,
        nowMillis: Long,
    ) = withContext(Dispatchers.IO) {
        val exercises = db.exerciseDao().all()
        val exercisesById = exercises.associateBy { it.id }
        val prSets = db.setEntryDao().forWorkout(workoutId).filter { it.isPR }

        val allSets = db.setEntryDao().all()
        val allWorkouts = db.workoutDao().all()
        val targets = db.muscleVolumeTargetDao().all().associateBy { it.muscle }
        val coverage = MuscleAssistant.coverageStatus(allSets, allWorkouts, exercises, targets, nowMillis)

        // #23: muscles the explicitly-selected next plan already schedules = the primaryMuscles of
        // every exercise that has a PlanEntry. Lowercased to match coverageStatus's token vocabulary.
        // Only these explicit plan rows feed the coach — no schedule is ever inferred.
        val plannedMuscles = db.planEntryDao().all()
            .mapNotNull { exercisesById[it.exerciseId] }
            .flatMap { it.primaryMuscles ?: emptyList() }
            .map { it.lowercase() }
            .toSet()

        val data = CoachEngine.summarize(prSets, exercisesById, suggestions, coverage, plannedMuscles)
        db.coachSummaryDao().upsert(
            CoachSummary(
                workoutId = workoutId,
                prLines = data.prLines.takeIf { it.isNotEmpty() },
                progressionLines = data.progressionLines.takeIf { it.isNotEmpty() },
                volumeLines = data.volumeLines.takeIf { it.isNotEmpty() },
                neglectLines = data.neglectLines.takeIf { it.isNotEmpty() },
                createdAt = nowMillis,
            ),
        )
    }

    suspend fun latestCoachSummary(): CoachSummary? = withContext(Dispatchers.IO) {
        db.coachSummaryDao().latest()
    }

    // --- M7: reminders ---

    suspend fun reminderConfig(): ReminderConfig = withContext(Dispatchers.IO) {
        db.reminderConfigDao().get() ?: ReminderConfig()
    }

    suspend fun setReminderConfig(neglectEnabled: Boolean, restDayEnabled: Boolean) =
        withContext(Dispatchers.IO) {
            db.reminderConfigDao().upsert(
                ReminderConfig(
                    neglectNudgesEnabled = neglectEnabled,
                    restDayNudgesEnabled = restDayEnabled,
                ),
            )
        }

    // --- M7: plan entries (external coach overlay) ---

    suspend fun allPlanEntries(): List<PlanEntry> = withContext(Dispatchers.IO) {
        db.planEntryDao().all()
    }

    suspend fun planEntriesForExercise(exerciseId: Long): List<PlanEntry> = withContext(Dispatchers.IO) {
        db.planEntryDao().forExercise(exerciseId)
    }

    suspend fun upsertPlanEntry(entry: PlanEntry): Long = withContext(Dispatchers.IO) {
        db.planEntryDao().upsert(entry)
    }

    suspend fun deletePlanEntry(id: Long) = withContext(Dispatchers.IO) {
        db.planEntryDao().deleteById(id)
    }
}
