package dev.ironlog.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.ironlog.app.data.AppSettings
import dev.ironlog.app.data.CoachSummary
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.GymProfile
import dev.ironlog.app.gym.GymMetadata
import dev.ironlog.app.gym.MuscleAssistant
import dev.ironlog.app.data.IronlogRepository
import dev.ironlog.app.data.Measurement
import dev.ironlog.app.data.PlanEntry
import dev.ironlog.app.data.PlateInventory
import dev.ironlog.app.data.ReminderConfig
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Template
import dev.ironlog.app.data.TemplateExerciseDetail
import dev.ironlog.app.data.TemplateSetValue
import dev.ironlog.app.data.Workout
import dev.ironlog.app.data.importer.ImportResult
import dev.ironlog.app.healthconnect.HealthConnectGateway
import dev.ironlog.app.metrics.CardioFormat
import dev.ironlog.app.metrics.PlateMath
import dev.ironlog.app.progression.Backtest
import dev.ironlog.app.progression.GoalPreset
import dev.ironlog.app.progression.SuggestionEngine
import dev.ironlog.app.reconcile.ExerciseReconciler
import dev.ironlog.app.session.DraftExercise
import dev.ironlog.app.session.DraftSet
import dev.ironlog.app.session.DraftWorkout
import dev.ironlog.app.session.DurationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import dev.ironlog.app.data.ImportJsonResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Rest-timer state: how much time is left and the total it started from (for the draining bar). */
data class RestState(val remainingMs: Long, val totalMs: Long)

/** Haptic/sound cues from a running rest timer. */
sealed interface RestCue {
    /** Countdown tick with seconds remaining (1, 2, or 3) — drives the Mario-Kart cadence. */
    data class Countdown(val secondsLeft: Int) : RestCue
    /** Rest interval has ended. */
    object Done : RestCue
}

class IronlogViewModel(
    private val repo: IronlogRepository,
    private val settings: AppSettings,
    private val restScheduler: dev.ironlog.app.timer.RestAlarmScheduler,
    val hcGateway: HealthConnectGateway,
    /** Slim in-progress tray notification (null on the JVM — tests don't exercise it). */
    private val workoutNotifier: dev.ironlog.app.timer.WorkoutNotifier? = null,
) : ViewModel() {

    val exercises: StateFlow<List<Exercise>> =
        repo.exercises().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- M6: measurements ---
    val measurements: StateFlow<List<Measurement>> =
        repo.measurements().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // M5: gym profiles (the active one filters the finder + swap).
    val gymProfiles: StateFlow<List<GymProfile>> =
        repo.gymProfilesFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The 13 equipment tokens a profile can toggle (free-exercise-db vocabulary). */
    val allEquipmentTokens: List<String> = GymMetadata.ALL_EQUIPMENT_TOKENS.sorted()

    // --- M6: settings flows ---
    val defaultBarLb: StateFlow<Double> =
        settings.defaultBarLb.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 45.0)
    val defaultRestSec: StateFlow<Int> =
        settings.defaultRestSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    // RETIRED — DO NOT RE-WIRE. lockCompletedSets + deleteSetConfirmation were removed 2026-06-22
    // (set-locking was never wanted; delete-confirm silently changed swipe-delete). Nothing reads
    // these; kept only so the DataStore keys stay readable. See
    // specs/decisions/settings-lock-and-delete-confirm-removed.md.
    val lockCompletedSets: StateFlow<Boolean> =
        settings.lockCompletedSets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val deleteSetConfirmation: StateFlow<Boolean> =
        settings.deleteSetConfirmation.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val preventSleep: StateFlow<Boolean> =
        settings.preventSleep.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val soundEnabled: StateFlow<Boolean> =
        settings.soundEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val xrmFormula: StateFlow<String> =
        settings.xrmFormula.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "EPLEY")
    val autoBackupEnabled: StateFlow<Boolean> =
        settings.autoBackupEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    /** F9: the picked SAF folder (tree uri), or null if none chosen yet. */
    val autoBackupUri: StateFlow<String?> =
        settings.autoBackupUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** F9: last backup outcome line, shown under the auto-backup toggle. */
    val lastBackupStatus: StateFlow<String?> =
        settings.lastBackupStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** F6: rest-over alert sound preference (null = system default) -- see RestSoundPref. */
    val restSoundUri: StateFlow<String?> =
        settings.restSoundUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setRestSoundUri(value: String?) {
        viewModelScope.launch { settings.setRestSoundUri(value) }
    }

    val history: StateFlow<List<Workout>> =
        repo.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val templates: StateFlow<List<Template>> =
        repo.templates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _draft = MutableStateFlow<DraftWorkout?>(null)
    val draft: StateFlow<DraftWorkout?> = _draft.asStateFlow()

    /** Rest-timer state; null when no timer is running. Ticks finely so the bar drains smoothly. */
    private val _rest = MutableStateFlow<RestState?>(null)
    val rest: StateFlow<RestState?> = _rest.asStateFlow()

    /** Rest-timer haptic/sound cues: a Countdown(secondsLeft) for 3-2-1, then Done. */
    private val _restCue = MutableSharedFlow<RestCue>(extraBufferCapacity = 4)
    val restCue: SharedFlow<RestCue> = _restCue.asSharedFlow()
    private var restJob: Job? = null
    /** Absolute end-time set when a rest starts; used to recompute remaining on resume. */
    private var restEndTimeMs: Long? = null

    private val _counts = MutableStateFlow(ImportResult(0, 0, 0))
    val counts: StateFlow<ImportResult> = _counts.asStateFlow()

    /** Elapsed seconds ticker, updated every second while a workout is active. */
    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()
    private var elapsedJob: Job? = null
    private var durationTracker: DurationTracker? = null

    /** Plate inventory loaded on demand; used by the plate calculator sheet. */
    private val _plateInventory = MutableStateFlow<List<PlateInventory>>(emptyList())
    val plateInventory: StateFlow<List<PlateInventory>> = _plateInventory.asStateFlow()

    init {
        refreshCounts()
        loadPlateInventory()
    }

    fun refreshCounts() {
        viewModelScope.launch { _counts.value = repo.counts() }
    }

    private fun loadPlateInventory() {
        viewModelScope.launch { _plateInventory.value = repo.plateInventory() }
    }

    // --- Active workout lifecycle ---

    /** Tray-notification body when no rest is counting down: what's up next. */
    private fun workoutIdleText(): String {
        val next = _draft.value?.exercises
            ?.firstOrNull { ex -> ex.sets.any { !it.done } }?.name
        return if (next != null) "Next: $next" else "Logging — tap to return"
    }

    /**
     * Post/update the slim in-progress notification (no-op with no draft or on the JVM).
     * [restEndTimeMs] non-null hands the lock-screen Chronometer a target so the countdown
     * keeps ticking without another notify() call every second (F5).
     */
    private fun notifyWorkout(text: String, restEndTimeMs: Long? = null) {
        val d = _draft.value ?: return
        workoutNotifier?.show(d.name.ifBlank { "Workout" }, text, restEndTimeMs)
    }

    fun startEmpty(nowMillis: Long) {
        stopRest()  // a fresh workout must never inherit a stale rest timer from a prior session
        startElapsedTicker(nowMillis)
        _draft.value = DraftWorkout(name = "Workout", startedAtMillis = nowMillis)
        notifyWorkout(workoutIdleText())
    }

    /** Build a draft by COPYING a template's exercises (each prefilled from history). The
     *  template itself is never touched by what happens in the session -- that is Fix 2. */
    fun startFromTemplate(templateId: Long, nowMillis: Long, onReady: () -> Unit) {
        stopRest()  // clear any stale rest timer so a freshly started workout starts clean
        viewModelScope.launch {
            val detail = repo.templateDetail(templateId) ?: return@launch
            val exercises = ArrayList<DraftExercise>(detail.exerciseDetails.size)
            for (exDetail in detail.exerciseDetails) {
                exercises.add(buildDraftExercise(exDetail.exercise, exDetail))
            }
            startElapsedTicker(nowMillis)
            _draft.value = DraftWorkout(
                name = detail.name,
                startedAtMillis = nowMillis,
                templateId = templateId,
                exercises = exercises,
            )
            notifyWorkout(workoutIdleText())
            onReady()
        }
    }

    private fun startElapsedTicker(startMillis: Long) {
        elapsedJob?.cancel()
        durationTracker = DurationTracker(startMillis)
        _elapsedSec.value = 0
        elapsedJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val tracker = durationTracker ?: break
                _elapsedSec.value = tracker.elapsedSec(System.currentTimeMillis())
            }
        }
    }

    private fun stopElapsedTicker(): Int {
        val elapsed = durationTracker?.elapsedSec(System.currentTimeMillis()) ?: 0
        elapsedJob?.cancel()
        elapsedJob = null
        durationTracker = null
        _elapsedSec.value = 0
        return elapsed
    }

    fun setName(name: String) = _draft.update { it?.copy(name = name) }
    fun setWorkoutNote(note: String) = _draft.update { it?.copy(notes = note.ifBlank { null }) }

    fun addExercise(exercise: Exercise) {
        viewModelScope.launch {
            val built = buildDraftExercise(exercise)
            _draft.update { it?.addExercise(built) }
        }
    }

    fun createExerciseAndAdd(name: String, type: ExerciseType) {
        viewModelScope.launch {
            val created = repo.addExercise(name.trim(), type)
            val built = buildDraftExercise(created)
            _draft.update { it?.addExercise(built) }
        }
    }

    fun addSet(exerciseIndex: Int) = _draft.update { it?.addSet(exerciseIndex) }
    fun normalizeFromSet(exerciseIndex: Int, setIndex: Int) =
        _draft.update { it?.normalizeFromSet(exerciseIndex, setIndex) }
    fun addNextTimeNote(note: String) = _draft.update { it?.addNextTimeNote(note) }
    fun removeExercise(exerciseIndex: Int) = _draft.update { it?.removeExercise(exerciseIndex) }
    fun removeSet(exerciseIndex: Int, setIndex: Int) =
        _draft.update { it?.removeSet(exerciseIndex, setIndex) }

    fun removeSetById(exerciseIndex: Int, setId: Long) =
        _draft.update { it?.removeSetById(exerciseIndex, setId) }

    fun updateSet(exerciseIndex: Int, setIndex: Int, transform: (DraftSet) -> DraftSet) =
        _draft.update { it?.updateSet(exerciseIndex, setIndex, transform) }

    fun markWarmup(exerciseIndex: Int, setIndex: Int, on: Boolean) =
        _draft.update { it?.markWarmup(exerciseIndex, setIndex, on) }

    /** Replace exercise at [exerciseIndex] with [newExercise], carrying logged sets over. */
    fun replaceExercise(exerciseIndex: Int, newExercise: Exercise) {
        viewModelScope.launch {
            val built = buildDraftExercise(newExercise)
            _draft.update { it?.replaceExercise(exerciseIndex, built) }
        }
    }

    fun reorderExercise(from: Int, to: Int) = _draft.update { it?.reorderExercise(from, to) }

    /** Pair this exercise with the NEXT one as a superset (joins its group if one exists). */
    fun supersetWithNext(exerciseIndex: Int) {
        _draft.update { d ->
            if (d == null || exerciseIndex >= d.exercises.size - 1) return@update d
            val current = d.exercises[exerciseIndex].supersetGroup
            val next = d.exercises[exerciseIndex + 1].supersetGroup
            val group = current ?: next
                ?: ((d.exercises.mapNotNull { it.supersetGroup }.maxOrNull() ?: 0) + 1)
            d.setSuperset(listOf(exerciseIndex, exerciseIndex + 1), group)
        }
    }

    fun removeFromSuperset(exerciseIndex: Int) =
        _draft.update { it?.setSuperset(listOf(exerciseIndex), null) }

    /** Warm-up ramp from the first working weight (rows tagged warm-up). */
    fun addWarmupRamp(exerciseIndex: Int, isBarbell: Boolean) {
        _draft.update { d ->
            if (d == null) return@update d
            val ex = d.exercises.getOrNull(exerciseIndex) ?: return@update d
            val working = ex.sets.firstOrNull { !it.isWarmup }?.weightLb ?: return@update d
            val ramp = dev.ironlog.app.metrics.WarmupCalculator
                .ramp(working, isBarbell, defaultBarLb.value)
            if (ramp.isEmpty()) return@update d
            d.addWarmupSets(exerciseIndex, ramp.map { it.weightLb to it.reps })
        }
    }

    fun setExerciseNote(exerciseIndex: Int, note: String) =
        _draft.update { it?.updateExercise(exerciseIndex) { ex -> ex.copy(note = note.ifBlank { null }) } }

    fun setStickyNote(exerciseIndex: Int, note: String) {
        val exercise = _draft.value?.exercises?.getOrNull(exerciseIndex) ?: return
        _draft.update { it?.updateExercise(exerciseIndex) { ex -> ex.copy(stickyNote = note.ifBlank { null }) } }
        viewModelScope.launch { repo.setStickyNote(exercise.exerciseId, note.ifBlank { null }) }
    }

    /** Fix 1: instant discard. Just drops the in-memory draft -- no DB write ever happened.
     *  durationSec is NOT persisted on discard. */
    fun discard() {
        stopRest()
        stopElapsedTicker()
        _draft.value = null
        workoutNotifier?.cancel()
    }

    /**
     * Completes the workout. The draft is dropped SYNCHRONOUSLY so the caller can navigate away
     * immediately (no blank frame while Room writes); persistence continues in the background.
     * Template updates are strictly OPT-IN via the two dialog checkboxes — with both false
     * (the default) the source template is never touched, no matter what was added/removed
     * during the session.
     */
    fun finish(
        updateTemplateValues: Boolean = false,
        updateTemplateStructure: Boolean = false,
        onSaved: (Long) -> Unit = {},
    ) {
        val current = _draft.value ?: return
        stopRest()
        val elapsedSec = stopElapsedTicker()
        _draft.value = null
        workoutNotifier?.cancel()
        viewModelScope.launch {
            val id = repo.finish(current, elapsedSec)
            if (updateTemplateValues || updateTemplateStructure) {
                repo.updateTemplateFromDraft(current, updateTemplateValues, updateTemplateStructure)
            }
            refreshCounts()
            onSaved(id)
            // Recompute suggestions against the just-saved workout, THEN persist the coach
            // summary from that fresh feed — otherwise the progression/volume lines describe
            // the pre-workout state (PR/coverage lines were already pulled fresh from DB).
            val now = System.currentTimeMillis()
            val freshSuggestions = repo.progressionSuggestions(defaultGoalPreset.value, now)
            _suggestions.value = freshSuggestions
            repo.generateAndSaveCoachSummary(id, freshSuggestions, now)
            refreshLastCoachSummary()
        }
    }

    // --- Rest timer ---

    /**
     * Starts (or restarts) the rest timer. Schedules an exact alarm at [endTimeMs] so the
     * end-of-rest buzz fires even if the app is backgrounded or killed. The on-screen countdown
     * is a cosmetic ticker driven by [endTimeMs] - now; it self-heals on [resumeRest].
     *
     * F5: the tray notification is posted ONCE here with the end time handed to a lock-screen
     * Chronometer (setChronometerCountDown) -- Android itself ticks that display every second,
     * on the lock screen, with the screen off, with no further notify() calls from us. The old
     * approach re-posted the notification from [launchCosmeticTicker]'s coroutine every second,
     * which only runs reliably while the app is in the foreground (no foreground service backs
     * it), so the lock screen could stall or lag once the screen turned off.
     */
    fun startRest(seconds: Int = DEFAULT_REST_SECONDS, nowMs: Long = System.currentTimeMillis()) {
        // F3: "No rest timer" (0 seconds) -- no bar, no alarm; just clear anything already running.
        if (!dev.ironlog.app.timer.RestTimerConfig.isEnabled(seconds)) {
            stopRest()
            return
        }
        restJob?.cancel()
        val totalMs = seconds.toLong() * 1000L
        val endTime = nowMs + totalMs
        restEndTimeMs = endTime
        restScheduler.schedule(endTime)
        _rest.value = RestState(remainingMs = totalMs, totalMs = totalMs)
        notifyWorkout(workoutIdleText(), restEndTimeMs = endTime)
        launchCosmeticTicker(endTime = endTime, totalMs = totalMs)
    }

    /**
     * Recomputes remaining time from [restEndTimeMs] and restarts the cosmetic ticker.
     * Call from [MainActivity.onResume] so the bar self-heals after the app is backgrounded.
     */
    fun resumeRest(nowMs: Long = System.currentTimeMillis()) {
        val endTime = restEndTimeMs ?: return
        val remaining = endTime - nowMs
        if (remaining <= 0) {
            // Timer already expired while we were away; alarm has already fired.
            restEndTimeMs = null
            _rest.value = null
            return
        }
        restJob?.cancel()
        _rest.value = RestState(remainingMs = remaining, totalMs = _rest.value?.totalMs ?: remaining)
        notifyWorkout(workoutIdleText(), restEndTimeMs = endTime)
        launchCosmeticTicker(endTime = endTime, totalMs = _rest.value?.totalMs ?: remaining)
    }

    private fun launchCosmeticTicker(endTime: Long, totalMs: Long) {
        restJob = viewModelScope.launch {
            var lastSec = Int.MAX_VALUE
            while (true) {
                delay(TICK_MS)
                val remaining = (endTime - System.currentTimeMillis()).coerceAtLeast(0)
                _rest.value = RestState(remainingMs = remaining, totalMs = totalMs)
                val sec = ((remaining + 999) / 1000).toInt()
                if (sec != lastSec) {
                    lastSec = sec
                    if (sec in 1..3) _restCue.tryEmit(RestCue.Countdown(sec))
                    // Notification text/chronometer are set once in startRest/resumeRest; the
                    // lock-screen Chronometer ticks itself, so no per-second notify() here.
                }
                if (remaining == 0L) break
            }
            restEndTimeMs = null
            _rest.value = null
            _restCue.tryEmit(RestCue.Done)
            notifyWorkout(workoutIdleText())
        }
    }

    fun addRest(deltaSeconds: Int, nowMs: Long = System.currentTimeMillis()) {
        val current = _rest.value ?: return
        val newSeconds = ((current.remainingMs + 999) / 1000).toInt() + deltaSeconds
        startRest(newSeconds.coerceAtLeast(1), nowMs)
    }

    fun minusRest(deltaSeconds: Int, nowMs: Long = System.currentTimeMillis()) {
        val current = _rest.value ?: return
        val newSeconds = ((current.remainingMs + 999) / 1000).toInt() - deltaSeconds
        startRest(newSeconds.coerceAtLeast(1), nowMs)
    }

    fun stopRest() {
        restScheduler.cancel()
        restJob?.cancel()
        restJob = null
        restEndTimeMs = null
        _rest.value = null
        notifyWorkout(workoutIdleText()) // skip/stop: countdown out of the tray line
    }

    /** Set an exercise's rest for this session and remember it on the catalog exercise. */
    fun setExerciseRest(exerciseIndex: Int, seconds: Int) {
        val exercise = _draft.value?.exercises?.getOrNull(exerciseIndex) ?: return
        _draft.update { it?.updateExercise(exerciseIndex) { e -> e.copy(restSeconds = seconds) } }
        viewModelScope.launch { repo.setExerciseRest(exercise.exerciseId, seconds) }
    }

    // --- Plate calculator ---

    fun calculatePlates(targetLb: Double, barLb: Double): PlateMath.PlateResult {
        val stock = _plateInventory.value
            .map { PlateMath.PlateStock(it.plateLb, it.pairCount) }
            .ifEmpty { PlateMath.DEFAULT_PLATES }
        return PlateMath.calculate(targetLb, barLb, stock)
    }

    // --- M5: gym & muscle assistant ---

    suspend fun equipmentForProfile(id: Long): Set<String> = repo.equipmentForProfile(id)

    fun createGymProfile(name: String, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch { onDone(repo.createGymProfile(name)) }
    }

    fun renameGymProfile(id: Long, name: String) {
        viewModelScope.launch { repo.renameGymProfile(id, name) }
    }

    fun deleteGymProfile(id: Long) {
        viewModelScope.launch { repo.deleteGymProfile(id) }
    }

    fun setActiveGymProfile(id: Long) {
        viewModelScope.launch { repo.setActiveGymProfile(id) }
    }

    fun setEquipmentToken(profileId: Long, token: String, on: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch { repo.setEquipmentToken(profileId, token, on); onDone() }
    }

    /** Finder: catalog exercises for a muscle, filtered to the active gym. */
    suspend fun findExercisesForMuscle(muscle: String): List<Exercise> = repo.findExercisesForMuscle(muscle)

    /** Busy-station swap: ranked substitutes for [source] at the active gym. */
    suspend fun swapAlternatives(source: Exercise): List<MuscleAssistant.ScoredAlternative> =
        repo.swapAlternatives(source)

    /** Distinct primary muscles in the catalog (for the finder's muscle picker). */
    suspend fun catalogMuscles(): List<String> = repo.catalogMuscles()

    // --- Template editing ---

    suspend fun templateDetail(templateId: Long) = repo.templateDetail(templateId)

    /** For the template editor grid: an exercise's last-session sets as seed values (to prefill a
     *  newly added exercise) paired with their gray PREVIOUS-column labels. */
    suspend fun editorPrevFor(exercise: Exercise): Pair<List<TemplateSetValue>, List<String>> {
        val prev = repo.lastSessionSets(exercise.id)
        return prev.map { TemplateSetValue(it.weightLb, it.reps, it.seconds, it.distanceMeters) } to
            prev.map { previousLabel(exercise.type, it) }
    }

    fun saveTemplate(
        id: Long?,
        name: String,
        note: String?,
        exerciseDetails: List<TemplateExerciseDetail>,
        onSaved: (Long) -> Unit,
    ) {
        viewModelScope.launch { onSaved(repo.saveTemplate(id, name, note, exerciseDetails)) }
    }

    fun moveTemplate(templateId: Long, up: Boolean) {
        viewModelScope.launch { repo.moveTemplate(templateId, up) }
    }

    fun deleteTemplate(templateId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteTemplate(templateId)
            onDone()
        }
    }

    // --- Misc ---

    fun importBundledHistory(context: Context, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.importBundledHistory(context)
            refreshCounts()
            onDone()
        }
    }

    suspend fun setsForWorkout(workoutId: Long): List<SetEntry> = repo.setsForWorkout(workoutId)

    fun deleteWorkout(workoutId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteWorkout(workoutId)
            refreshCounts()
            onDone()
        }
    }

    /** All sets across all workouts (used by muscle-coverage on Profile screen). */
    suspend fun allSets(): List<SetEntry> = repo.allSets()

    /** M5 (d): per-muscle coverage status (volume band + never-isolated + stale) for Profile. */
    suspend fun muscleCoverageStatus(nowMillis: Long) = repo.muscleCoverageStatus(nowMillis)

    // --- Reconciliation ---

    suspend fun loadReconcileProposals(
        context: Context,
    ): List<ExerciseReconciler.ReconcileProposal> = withContext(Dispatchers.IO) {
        val json = context.assets.open("exercises.json").bufferedReader().readText()
        val freeDb = ExerciseReconciler.parseDb(json)
        val customs = repo.unmappedExerciseIdNamePairs()
        ExerciseReconciler.propose(customs, freeDb)
            .sortedWith(compareBy({ it.matchType.ordinal }, { it.exerciseName }))
    }

    fun confirmReconcileMatch(
        exerciseId: Long,
        candidate: ExerciseReconciler.FreeDbEntry,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            repo.applyConfirmedMetadata(exerciseId, candidate)
            onDone()
        }
    }

    /** Map a logged exercise to a hand-picked catalog exercise (manual reconcile fallback). */
    fun mapExerciseToCatalog(loggedId: Long, catalogId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.copyMetadataFromCatalog(loggedId, catalogId)
            onDone()
        }
    }

    // --- M4: progression engine / Coach feed ---

    /** App default goal preset (HYPERTROPHY by design; chooser surfaced in the Coach card). */
    val defaultGoalPreset: StateFlow<GoalPreset> =
        settings.defaultGoalPreset.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), GoalPreset.DEFAULT,
        )

    private val _suggestions = MutableStateFlow<List<SuggestionEngine.Suggestion>>(emptyList())
    val suggestions: StateFlow<List<SuggestionEngine.Suggestion>> = _suggestions.asStateFlow()

    private val _backtest = MutableStateFlow<Backtest.Result?>(null)
    val backtest: StateFlow<Backtest.Result?> = _backtest.asStateFlow()

    /** Seed the volume landmarks (one-time) then refresh the Coach feed. */
    fun ensureLandmarksSeededAndRefresh(context: Context, nowMillis: Long) {
        viewModelScope.launch {
            repo.seedVolumeLandmarksIfEmpty(context)
            refreshSuggestions(nowMillis)
        }
    }

    fun refreshSuggestions(nowMillis: Long) {
        viewModelScope.launch {
            _suggestions.value = repo.progressionSuggestions(defaultGoalPreset.value, nowMillis)
        }
    }

    fun acceptSuggestion(suggestion: SuggestionEngine.Suggestion, nowMillis: Long) {
        viewModelScope.launch {
            repo.recordSuggestionVerdict(suggestion, accepted = true, nowMillis = nowMillis)
            refreshSuggestions(nowMillis)
        }
    }

    fun dismissSuggestion(suggestion: SuggestionEngine.Suggestion, nowMillis: Long) {
        viewModelScope.launch {
            repo.recordSuggestionVerdict(suggestion, accepted = false, nowMillis = nowMillis)
            refreshSuggestions(nowMillis)
        }
    }

    fun setDefaultGoalPreset(preset: GoalPreset, nowMillis: Long) {
        viewModelScope.launch {
            settings.setDefaultGoalPreset(preset)
            refreshSuggestions(nowMillis)
        }
    }

    fun setExerciseProgression(
        exerciseId: Long,
        goalPreset: GoalPreset?,
        incrementLb: Double?,
        progressionEnabled: Boolean,
        nowMillis: Long,
    ) {
        viewModelScope.launch {
            repo.setExerciseProgression(exerciseId, goalPreset, incrementLb, progressionEnabled)
            refreshSuggestions(nowMillis)
        }
    }

    /** F2: persist a per-exercise plate-calc override (mode + base/bar weight). */
    fun setExercisePlateCalcConfig(exerciseId: Long, mode: String?, barWeightLb: Double?) {
        viewModelScope.launch { repo.setExercisePlateCalcConfig(exerciseId, mode, barWeightLb) }
    }

    fun runBacktest() {
        viewModelScope.launch { _backtest.value = repo.runBacktest(defaultGoalPreset.value) }
    }

    /** Build a draft exercise: one DraftSet per previous-session set, prefilled, plus the
     *  gray PREVIOUS labels. Falls back to a single blank set when there is no history. */
    private suspend fun buildDraftExercise(
        exercise: Exercise,
        templateDetail: TemplateExerciseDetail? = null,
    ): DraftExercise {
        val previous = repo.lastSessionSets(exercise.id)
        val tplSets = templateDetail?.sets ?: emptyList()
        val sets = when {
            // A template with saved per-set rows defines the set STRUCTURE (how many sets exist).
            // Removing a set mid-workout can therefore never shrink the next session. Prefill
            // VALUES still track the last session where available (fresher than the template's
            // stored targets); the template's values are the fallback for slots with no history.
            tplSets.isNotEmpty() -> tplSets.mapIndexed { i, s ->
                val p = previous.getOrNull(i)
                DraftSet(
                    weightLb = p?.weightLb ?: s.weightLb,
                    reps = p?.reps ?: s.reps,
                    seconds = p?.seconds ?: s.seconds,
                    distanceMeters = p?.distanceMeters ?: s.distanceMeters,
                    id = (i + 1).toLong(),
                )
            }
            previous.isEmpty() -> listOf(DraftSet(id = 1))
            else -> previous.mapIndexed { i, p ->
                DraftSet(
                    weightLb = p.weightLb,
                    reps = p.reps,
                    seconds = p.seconds,
                    distanceMeters = p.distanceMeters,
                    id = (i + 1).toLong(),
                )
            }
        }
        return DraftExercise(
            exerciseId = exercise.id,
            name = exercise.name,
            type = exercise.type,
            sets = sets,
            restSeconds = templateDetail?.targetRestSec ?: exercise.restSeconds,
            previous = previous.map { previousLabel(exercise.type, it) },
            stickyNote = exercise.stickyNote,
            targetSets = templateDetail?.targetSets,
            targetRepsLow = templateDetail?.targetRepsLow,
            targetRepsHigh = templateDetail?.targetRepsHigh,
            coachHint = repo.coachHintFor(exercise.id),
        )
    }

    private fun previousLabel(type: ExerciseType, s: SetEntry): String = when (type) {
        ExerciseType.WEIGHTED -> "${fmtNum(s.weightLb)} lb × ${s.reps ?: 0}"
        ExerciseType.BODYWEIGHT -> "${s.reps ?: 0} reps"
        ExerciseType.TIMED -> CardioFormat.formatDurationLabel(s.seconds)
        ExerciseType.DISTANCE -> "${fmtNum(s.distanceMeters)} m"
        ExerciseType.CARDIO -> CardioFormat.cardioSummary(s.distanceMeters, s.seconds)
    }

    private fun fmtNum(v: Double?): String =
        v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "0"

    // --- M3: catalog seeding + browse + edit ---

    /** Seed the 873-row free-exercise-db catalog (one-time; no-op on subsequent calls). */
    fun ensureCatalogSeeded(context: Context) {
        viewModelScope.launch { repo.seedCatalogIfNeeded(context) }
    }

    /** Backfill per-set rows for legacy templates so the template owns its set structure. */
    fun ensureTemplateSetsBackfilled() {
        viewModelScope.launch { repo.backfillTemplateSetsIfNeeded() }
    }

    /** F1 one-time repair: purge imported "Rest Timer"/"Note" phantom sets + fallout. */
    fun ensureImportedMetadataRepaired(context: Context) {
        viewModelScope.launch { repo.repairImportedMetadataIfNeeded(context) }
    }

    /** One-time repair: re-type distance+time exercises (treadmill, bike) to CARDIO. */
    fun ensureCardioTypesRepaired(context: Context) {
        viewModelScope.launch { repo.repairCardioExerciseTypesIfNeeded(context) }
    }

    /**
     * Per-exercise "Logs as" override.  Persists to the exercise and, when that exercise is in
     * the open draft, switches its columns immediately -- already-entered values stay put.
     */
    fun setExerciseType(exerciseId: Long, type: ExerciseType) {
        viewModelScope.launch {
            repo.setExerciseType(exerciseId, type)
            _draft.update { d ->
                d?.copy(exercises = d.exercises.map {
                    if (it.exerciseId == exerciseId) it.copy(type = type) else it
                })
            }
        }
    }

    /** Auto-map unmapped logged exercises to muscle metadata (confident matches), so the muscle
     *  finder / swap / coverage work without 54 manual confirms. No-op once nothing's unmapped. */
    fun ensureLoggedExercisesReconciled(context: Context) {
        viewModelScope.launch { repo.reconcileLoggedExercisesIfNeeded(context) }
    }

    /** Sets for one exercise paired with workout start time — for History + Chart tabs. */
    suspend fun exerciseSetsWithTime(exerciseId: Long) = repo.exerciseSetsWithTime(exerciseId)

    /** All sets for an exercise across all workouts (for Records tab). */
    suspend fun allSetsForExercise(exerciseId: Long) = repo.allSetsForExercise(exerciseId)

    /** Load an existing workout into the draft buffer for editing; navigate to the edit screen. */
    fun startEditingWorkout(workoutId: Long, onReady: () -> Unit) {
        viewModelScope.launch {
            val draft = repo.loadWorkoutForEditing(workoutId) ?: return@launch
            _draft.value = draft
            onReady()
        }
    }

    /** Save the edit-mode draft back to the DB (replaces old sets, recomputes PRs). */
    fun finishEdit(onSaved: (Long) -> Unit) {
        val current = _draft.value ?: return
        check(current.editingWorkoutId != null) { "finishEdit called without editingWorkoutId" }
        viewModelScope.launch {
            val id = repo.finishEdit(current)
            _draft.value = null
            refreshCounts()
            onSaved(id)
            // Regenerate the coach summary so an edit that changes a PR/progression doesn't
            // leave the original (pre-edit) summary attached to this workout.
            val now = System.currentTimeMillis()
            val freshSuggestions = repo.progressionSuggestions(defaultGoalPreset.value, now)
            _suggestions.value = freshSuggestions
            repo.generateAndSaveCoachSummary(id, freshSuggestions, now)
            refreshLastCoachSummary()
        }
    }

    /** Quick lookup: find an exercise by id from the current exercises StateFlow. */
    fun exerciseById(id: Long): dev.ironlog.app.data.Exercise? =
        exercises.value.find { it.id == id }

    // --- M6: measurement actions ---

    /** Log a new body measurement, optionally writing to HC and storing the returned record id. */
    fun logMeasurement(
        type: String,
        value: Double,
        unit: String,
        timestampMs: Long = System.currentTimeMillis(),
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            var hcId: String? = null
            if (type == "bodyweight") hcId = hcGateway.writeWeightLb(value, timestampMs)
            else if (type == "bodyfat") hcId = hcGateway.writeBodyFatPct(value, timestampMs)
            repo.insertMeasurement(
                Measurement(
                    type = type, value = value, unit = unit,
                    timestamp = timestampMs,
                    source = if (hcId != null) "health_connect" else "manual",
                    hcRecordId = hcId,
                ),
            )
            onDone()
        }
    }

    fun deleteMeasurement(id: Long) {
        viewModelScope.launch { repo.deleteMeasurement(id) }
    }

    /** Sync bodyweight from HC since last sync, merging new rows without duplicating. */
    fun syncFromHealthConnect(lastSyncMs: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val readings = hcGateway.readWeightLbSince(lastSyncMs)
            readings.forEach { r ->
                if (repo.findMeasurementByHcId(r.hcRecordId) == null) {
                    repo.insertMeasurement(
                        Measurement(
                            type = "bodyweight", value = r.lb, unit = "lb",
                            timestamp = r.timestampMs, source = "health_connect",
                            hcRecordId = r.hcRecordId,
                        ),
                    )
                }
            }
            onDone()
        }
    }

    // --- M6: export ---

    fun exportCsv(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { onResult(repo.exportCsv()) }
    }

    fun exportJson(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { onResult(repo.exportJson()) }
    }

    fun importJsonBackup(jsonText: String, onDone: (dev.ironlog.app.data.ImportJsonResult) -> Unit) {
        viewModelScope.launch { onDone(repo.importJsonBackup(jsonText)) }
    }

    /**
     * F9: run a backup immediately into the already-picked SAF folder ("Back up now" button).
     * Shares the exact write path [dev.ironlog.app.export.AutoBackupWorker] uses on its
     * weekly schedule, so a manual run and the automated one can never drift apart.
     */
    fun backupNow(context: Context, onResult: (String) -> Unit) {
        val folderUri = autoBackupUri.value
        if (folderUri == null) {
            onResult("No backup folder chosen yet — enable auto-backup to pick one.")
            return
        }
        viewModelScope.launch {
            val status = try {
                val name = dev.ironlog.app.export.BackupRunner.runBackup(context.applicationContext, folderUri)
                "Backed up $name"
            } catch (e: Exception) {
                "Backup failed: ${e.message ?: e.javaClass.simpleName}"
            }
            settings.setLastBackupStatus(status)
            onResult(status)
        }
    }

    // --- M6: settings mutators ---

    fun setDefaultBarLb(lb: Double) { viewModelScope.launch { settings.setDefaultBarLb(lb) } }
    fun setDefaultRestSec(sec: Int) { viewModelScope.launch { settings.setDefaultRestSec(sec) } }
    // RETIRED setters (no caller) — kept with the retired flows above; see the note there + the decision doc.
    fun setLockCompletedSets(on: Boolean) { viewModelScope.launch { settings.setLockCompletedSets(on) } }
    fun setDeleteSetConfirmation(on: Boolean) { viewModelScope.launch { settings.setDeleteSetConfirmation(on) } }
    fun setPreventSleep(on: Boolean) { viewModelScope.launch { settings.setPreventSleep(on) } }
    fun setSoundEnabled(on: Boolean) { viewModelScope.launch { settings.setSoundEnabled(on) } }
    fun setXrmFormula(formula: String) { viewModelScope.launch { settings.setXrmFormula(formula) } }
    fun setAutoBackupEnabled(on: Boolean) { viewModelScope.launch { settings.setAutoBackupEnabled(on) } }
    fun setAutoBackupUri(uri: String?) { viewModelScope.launch { settings.setAutoBackupUri(uri) } }

    // --- M7: coach summary ---

    private val _lastCoachSummary = MutableStateFlow<CoachSummary?>(null)
    val lastCoachSummary: StateFlow<CoachSummary?> = _lastCoachSummary.asStateFlow()

    fun refreshLastCoachSummary() {
        viewModelScope.launch { _lastCoachSummary.value = repo.latestCoachSummary() }
    }

    // --- M7: reminder config ---

    private val _reminderConfig = MutableStateFlow(ReminderConfig())
    val reminderConfig: StateFlow<ReminderConfig> = _reminderConfig.asStateFlow()

    fun loadReminderConfig() {
        viewModelScope.launch { _reminderConfig.value = repo.reminderConfig() }
    }

    fun setNeglectNudgesEnabled(on: Boolean) {
        viewModelScope.launch {
            val current = _reminderConfig.value
            repo.setReminderConfig(neglectEnabled = on, restDayEnabled = current.restDayNudgesEnabled)
            _reminderConfig.value = repo.reminderConfig()
        }
    }

    fun setRestDayNudgesEnabled(on: Boolean) {
        viewModelScope.launch {
            val current = _reminderConfig.value
            repo.setReminderConfig(neglectEnabled = current.neglectNudgesEnabled, restDayEnabled = on)
            _reminderConfig.value = repo.reminderConfig()
        }
    }

    // --- M7: plan entries (external coach overlay) ---

    private val _planEntriesByExercise = MutableStateFlow<Map<Long, List<PlanEntry>>>(emptyMap())
    val planEntriesByExercise: StateFlow<Map<Long, List<PlanEntry>>> = _planEntriesByExercise.asStateFlow()

    fun loadPlanEntries() {
        viewModelScope.launch {
            _planEntriesByExercise.value = repo.allPlanEntries().groupBy { it.exerciseId }
        }
    }

    fun upsertPlanEntry(entry: PlanEntry, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.upsertPlanEntry(entry)
            loadPlanEntries()
            onDone()
        }
    }

    fun deletePlanEntry(id: Long) {
        viewModelScope.launch {
            repo.deletePlanEntry(id)
            loadPlanEntries()
        }
    }

    // --- M7: stretch timer (standalone; separate state from rest timer) ---

    private val _stretch = MutableStateFlow<RestState?>(null)
    val stretch: StateFlow<RestState?> = _stretch.asStateFlow()
    private var stretchJob: Job? = null

    fun startStretch(seconds: Int, nowMs: Long = System.currentTimeMillis()) {
        stretchJob?.cancel()
        val totalMs = seconds.toLong() * 1000L
        val endMs = nowMs + totalMs
        _stretch.value = RestState(totalMs, totalMs)
        stretchJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val remaining = (endMs - System.currentTimeMillis()).coerceAtLeast(0)
                _stretch.value = RestState(remaining, totalMs)
                if (remaining == 0L) break
            }
            _stretch.value = null
        }
    }

    fun stopStretch() {
        stretchJob?.cancel()
        stretchJob = null
        _stretch.value = null
    }

    class Factory(
        private val repo: IronlogRepository,
        private val settings: AppSettings,
        private val restScheduler: dev.ironlog.app.timer.RestAlarmScheduler,
        private val hcGateway: HealthConnectGateway,
        private val workoutNotifier: dev.ironlog.app.timer.WorkoutNotifier? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            IronlogViewModel(repo, settings, restScheduler, hcGateway, workoutNotifier) as T
    }

    companion object {
        const val DEFAULT_REST_SECONDS = 60 // default rest is 1:00
        const val DEFAULT_BAR_LB = 45.0
        private const val TICK_MS = 50L // fine tick so the rest bar drains smoothly
    }
}
