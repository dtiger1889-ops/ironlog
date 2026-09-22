package dev.ironlog.app.reconcile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Matches the user's custom exercise names to free-exercise-db entries.
 *
 * The user's naming convention: "Movement Name (Equipment)" or just "Movement Name".
 * Free-exercise-db naming: "Equipment Movement Name - Grip/Style Suffix".
 *
 * Strategy (in priority order):
 *   1. Normalized-exact match on CORE name (equipment-stripped on both sides).
 *      Equipment field used to break ties when multiple candidates share the same core.
 *   2. Token-set subset match: user's core tokens are a subset of candidate's full tokens
 *      (from the full candidate name, preserving all context words like "chest" in
 *      "Dips - Chest Version"). Equipment-boosted; fewest extra tokens preferred.
 *   3. High token-Jaccard (>= 0.55, equipment-boosted to 0.10) on score token sets.
 *   4. Levenshtein similarity >= 0.75 on core names as a last resort.
 *
 * Equipment is extracted from the user's trailing paren and matched to the DB's `equipment`
 * field. Equipment boosts score and breaks ties but is NOT required for a match.
 *
 * NEVER auto-applies -- every result is a CONFIRM proposal showing candidate + primaryMuscles.
 *
 * Pure Kotlin / JVM -- no Android dependencies.
 */
object ExerciseReconciler {

    const val FUZZY_THRESHOLD = 0.75   // Levenshtein fallback threshold
    const val JACCARD_THRESHOLD = 0.50 // Token-Jaccard threshold (before equipment boost)

    // -------------------------------------------------------------------------
    // Equipment tokens that may appear as a prefix in DB names.
    // Used to strip them from the candidate core name so we can compare
    // movement words only (and let the equipment field handle the differentiation).
    // -------------------------------------------------------------------------
    private val DB_EQUIPMENT_PREFIX_TOKENS = setOf(
        "barbell", "dumbbell", "cable", "machine", "kettlebell", "kettlebells",
        "bodyweight", "band", "bands", "ez", "ezbar", "body",
        "smith", "leverage", "sled", "weighted",
    )

    // -------------------------------------------------------------------------
    // Equipment synonym table: user equipment token -> DB equipment field value(s)
    // -------------------------------------------------------------------------
    private val EQUIPMENT_SYNONYMS: Map<String, Set<String>> = mapOf(
        "barbell"    to setOf("barbell"),
        "dumbbell"   to setOf("dumbbell"),
        "cable"      to setOf("cable"),
        "machine"    to setOf("machine"),
        "kettlebell" to setOf("kettlebells"),
        "bodyweight" to setOf("body only"),
        "band"       to setOf("bands"),
        "ezbar"      to setOf("e-z curl bar"),
        "ez"         to setOf("e-z curl bar"),
        "other"      to setOf("other"),
    )

    // -------------------------------------------------------------------------
    // Synonym / stemming table: surface form -> canonical token.
    // Keep small and explicit -- only confirmed movement-name equivalences.
    // -------------------------------------------------------------------------
    private val FLY_FORMS     = setOf("fly", "flyes", "flye", "flies")
    private val BICEP_FORMS   = setOf("bicep", "biceps")
    private val TRICEP_FORMS  = setOf("tricep", "triceps")
    private val AB_FORMS      = setOf("ab", "abdominal", "abdominals")
    private val PULLDOWN_FORMS = setOf("pulldown", "pulldowns", "pull down")
    private val CALF_FORMS    = setOf("calf", "calves")
    private val SKULL_FORMS   = setOf("skullcrusher", "skullcrushers")
    private val SITUP_FORMS   = setOf("situp", "situps")
    // military press and overhead press: same movement family.
    // Map both to "overhead" for scoring. Display still shows the real candidate name.
    // NOTE: "shoulder" is intentionally excluded -- it is too broad (shoulder raise, shrug, etc.)
    // and "Dumbbell Shoulder Press" is found via full-name scoreTokens + equipment matching.
    private val MILITARY_OVERHEAD_FORMS = setOf("military", "overhead")
    // plural-to-singular for common nouns
    private val PLURAL_STEMS: Map<String, String> = mapOf(
        "rows"       to "row",
        "raises"     to "raise",
        "lifts"      to "lift",
        "dips"       to "dip",
        "presses"    to "press",
        "squats"     to "squat",
        "curls"      to "curl",
        "pulls"      to "pull",
        "walks"      to "walk",
        "extensions" to "extension",
    )

    private fun canonicalToken(tok: String): String {
        val stemmed = PLURAL_STEMS[tok] ?: tok
        return when {
            stemmed in FLY_FORMS                -> "fly"
            stemmed in BICEP_FORMS              -> "bicep"
            stemmed in TRICEP_FORMS             -> "tricep"
            stemmed in AB_FORMS                 -> "ab"
            stemmed in PULLDOWN_FORMS           -> "pulldown"
            stemmed in CALF_FORMS               -> "calf"
            stemmed in SKULL_FORMS              -> "skullcrusher"
            stemmed in SITUP_FORMS              -> "situp"
            stemmed in MILITARY_OVERHEAD_FORMS  -> "overhead"
            else                                -> stemmed
        }
    }

    // Tokens dropped from scoring (DB-added noise, articles, equipment words).
    // Posture/position words (standing, seated, lying, incline, flat, decline) are KEPT.
    // Body-part words (chest, back, shoulder, glute, hip, leg) are KEPT.
    private val SCORE_NOISE_TOKENS = setOf(
        // grip / style descriptors
        "medium", "wide", "close", "narrow", "grip", "version", "style",
        // articles / prepositions
        "with", "a", "the", "on", "to", "from", "an", "in", "at", "of", "for", "and",
        // equipment words (differentiation handled via equipment field)
        "barbell", "dumbbell", "cable", "machine", "kettlebell", "kettlebells",
        "bodyweight", "band", "bands", "ez", "ezbar", "body", "only",
        "smith", "leverage", "sled",
        // DB modifiers the user doesn't use
        "long", "bar", "attachment", "single", "double",
    )

    // =========================================================================
    // Public data types
    // =========================================================================

    data class FreeDbEntry(
        val id: String,
        val name: String,
        val category: String?,
        val equipment: String?,
        val mechanic: String?,
        val force: String?,
        val primaryMuscles: List<String>,
        val secondaryMuscles: List<String>,
        val instructions: List<String>,
        val imageRef: String?,
    )

    data class ReconcileProposal(
        val exerciseId: Long,
        val exerciseName: String,
        val candidate: FreeDbEntry?,
        val matchType: MatchType,
        val similarity: Double,
    )

    enum class MatchType { EXACT, FUZZY, NONE }

    // =========================================================================
    // Public API
    // =========================================================================

    fun parseDb(json: String): List<FreeDbEntry> {
        val arr = Json.parseToJsonElement(json).jsonArray
        return arr.mapNotNull { el ->
            runCatching {
                val obj = el.jsonObject
                FreeDbEntry(
                    id = obj["id"]?.jsonPrimitive?.content ?: return@runCatching null,
                    name = obj["name"]?.jsonPrimitive?.content ?: return@runCatching null,
                    category = obj["category"]?.jsonPrimitive?.content,
                    equipment = obj["equipment"]?.jsonPrimitive?.content,
                    mechanic = obj["mechanic"]?.jsonPrimitive?.content,
                    force = obj["force"]?.jsonPrimitive?.content,
                    primaryMuscles = obj["primaryMuscles"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList(),
                    secondaryMuscles = obj["secondaryMuscles"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList(),
                    instructions = obj["instructions"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList(),
                    imageRef = obj["images"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
                )
            }.getOrNull()
        }
    }

    fun propose(
        customExercises: List<Pair<Long, String>>,
        freeDbEntries: List<FreeDbEntry>,
    ): List<ReconcileProposal> {
        val candidates = freeDbEntries.map { CandidateInfo(it) }
        // Index by movement-core (equipment stripped from BOTH the DB name prefix and the
        // DB " - suffix"). This lets "Bench Press (Barbell)" find "Barbell Bench Press".
        val byMovementCore = candidates.groupBy { it.movementCore }

        return customExercises.map { (id, name) ->
            val user = UserInfo(name)
            matchOne(id, name, user, candidates, byMovementCore)
        }
    }

    // =========================================================================
    // Matching
    // =========================================================================

    private fun matchOne(
        exerciseId: Long,
        exerciseName: String,
        user: UserInfo,
        candidates: List<CandidateInfo>,
        byMovementCore: Map<String, List<CandidateInfo>>,
    ): ReconcileProposal {

        // --- 1. Exact movement-core match ---
        val exactMatches = byMovementCore[user.movementCore]
        if (!exactMatches.isNullOrEmpty()) {
            val best = equipmentBest(exactMatches, user)
            // Only commit to EXACT if equipment matches (or user didn't specify equipment).
            // If user specified equipment and nothing in the exact group matches it,
            // the exact candidates are preserved as a fallback but we continue looking
            // for a better equipment-matched candidate via Jaccard/Levenshtein.
            val equipmentOk = user.equipment == null || equipmentMatches(user, best)
            if (equipmentOk) {
                return ReconcileProposal(exerciseId, exerciseName, best.entry, MatchType.EXACT, 1.0)
            }
            // Equipment mismatch in exact group: remember as fallback, keep searching.
            // (Handled below: if no better candidate found, fall back to this exact match.)
        }

        // --- 2. Token-subset match ---
        // User's score tokens must ALL appear in the candidate's full score tokens.
        // Guard: skip if user has zero score tokens (nothing to match against).
        if (user.scoreTokens.isNotEmpty()) {
            val subsetCandidates = candidates.filter { c ->
                user.scoreTokens.all { it in c.scoreTokens }
            }
            if (subsetCandidates.isNotEmpty()) {
                val best = subsetCandidates.minWith(
                    Comparator
                        .comparingInt<CandidateInfo> { c -> if (equipmentMatches(user, c)) 0 else 1 }
                        .thenComparingInt { c -> c.scoreTokens.size }
                )
                val score = 0.90 + if (equipmentMatches(user, best)) 0.09 else 0.0
                return ReconcileProposal(
                    exerciseId, exerciseName,
                    best.entry, MatchType.FUZZY, score.coerceAtMost(1.0),
                )
            }
        }

        // --- 3. Token Jaccard >= threshold (equipment-boosted by 0.10) ---
        // Require at least 2 tokens in the intersection OR equipment match to avoid
        // single-shared-token false positives (e.g. "heel raise" matching "Dumbbell Raise").
        var bestJaccard = 0.0
        var bestJaccardCandidate: CandidateInfo? = null
        for (c in candidates) {
            val intersection = user.scoreTokens.intersect(c.scoreTokens)
            val eqMatch = equipmentMatches(user, c)
            // Guard: at least 2 shared tokens, or 1 shared token + equipment match.
            if (intersection.size < 2 && !(intersection.size >= 1 && eqMatch)) continue
            val jaccard = intersection.size.toDouble() / user.scoreTokens.union(c.scoreTokens).size
            val boosted = if (eqMatch) jaccard + 0.10 else jaccard
            if (boosted > bestJaccard) {
                bestJaccard = boosted
                bestJaccardCandidate = c
            }
        }
        if (bestJaccard >= JACCARD_THRESHOLD && bestJaccardCandidate != null) {
            return ReconcileProposal(
                exerciseId, exerciseName,
                bestJaccardCandidate.entry, MatchType.FUZZY,
                bestJaccard.coerceAtMost(0.99),
            )
        }

        // --- 4. Levenshtein fallback on movement core names ---
        var bestLev = 0.0
        var bestLevCandidate: CandidateInfo? = null
        for (c in candidates) {
            val sim = levenshteinSimilarity(user.movementCore, c.movementCore)
            val boosted = if (equipmentMatches(user, c)) sim + 0.05 else sim
            if (boosted > bestLev) {
                bestLev = boosted
                bestLevCandidate = c
            }
        }
        if (bestLev >= FUZZY_THRESHOLD && bestLevCandidate != null) {
            return ReconcileProposal(
                exerciseId, exerciseName,
                bestLevCandidate.entry, MatchType.FUZZY,
                bestLev.coerceAtMost(0.99),
            )
        }

        // If all four stages failed but we had an exact-core match (just wrong equipment),
        // return that as a FUZZY fallback at score 0.85 (better than NONE).
        if (!exactMatches.isNullOrEmpty()) {
            val best = equipmentBest(exactMatches, user)
            return ReconcileProposal(exerciseId, exerciseName, best.entry, MatchType.FUZZY, 0.85)
        }

        return ReconcileProposal(exerciseId, exerciseName, null, MatchType.NONE, bestLev)
    }

    // =========================================================================
    // Pre-computed info wrappers
    // =========================================================================

    data class UserInfo(val rawName: String) {
        /** Equipment token from trailing parens, e.g. "barbell", "dumbbell", "cable". */
        val equipment: String?
        /**
         * Movement-only core: trailing parens stripped, leading equipment-prefix tokens
         * stripped (if present), normalised.
         * e.g. "Bench Press (Barbell)"  -> "bench press"
         * e.g. "Barbell Back Squat"     -> "back squat"   (old-style prefix naming)
         */
        val movementCore: String
        /**
         * Scoring tokens: from movementCore, noise + equipment words removed, synonyms applied.
         * e.g. "Lat Pulldown" -> {"lat","pulldown"}
         */
        val scoreTokens: Set<String>

        init {
            val parenMatch = Regex("""\(([^)]+)\)$""").find(rawName.trim())
            equipment = parenMatch?.groupValues?.get(1)?.let { extractEquipmentToken(it) }

            val stripped = rawName.trim().let {
                if (parenMatch != null) it.substringBeforeLast("(").trim() else it
            }
            // Strip leading equipment prefix tokens (handles old-style "Barbell X" names
            // and the test helper makeDb which uses prefix-style DB names as user input).
            movementCore = stripEquipmentPrefix(normalizeBase(stripped))
            scoreTokens = computeScoreTokens(movementCore)
        }
    }

    data class CandidateInfo(val entry: FreeDbEntry) {
        /**
         * Movement-only core: DB equipment prefix words AND " - suffix" stripped, normalised.
         * e.g. "Barbell Bench Press - Medium Grip" -> "bench press"
         * e.g. "Dumbbell Bicep Curl" -> "bicep curl"
         * This is the key index for exact matching.
         */
        val movementCore: String
        /**
         * Score tokens derived from the FULL normalised name (not just movementCore).
         * Keeps context words like "chest" from "Dips - Chest Version" that would be
         * lost in the suffix-stripped movementCore.
         */
        val scoreTokens: Set<String>

        init {
            // Strip " - suffix" first, then strip leading equipment prefix tokens.
            val noSuffix = entry.name.replace(Regex(""" - .+$"""), "").trim()
            val normNoSuffix = normalizeBase(noSuffix)
            movementCore = stripEquipmentPrefix(normNoSuffix)

            // Score tokens use the FULL raw-normalised name (NOT suffix-stripped).
            // This preserves context words like "chest" in "Dips - Chest Version" and
            // "rear" in "Cable Rear Delt Fly" that the suffix-strip would erase.
            scoreTokens = computeScoreTokens(normalize(entry.name))
        }
    }

    // =========================================================================
    // Normalisation helpers
    // =========================================================================

    /**
     * Lowercase, hyphens to spaces, strip non-alphanumeric-or-spaces, collapse whitespace.
     */
    fun normalize(name: String): String =
        name.lowercase()
            .replace("-", " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** normalizeBase: normalize() + strip trailing "(…)" parentheticals and " - suffix". */
    private fun normalizeBase(name: String): String {
        var s = name
        s = s.replace(Regex(""" - .+$"""), "")
        s = s.replace(Regex("""\([^)]*\)\s*$"""), "")
        return normalize(s)
    }

    /**
     * Strip any leading equipment-prefix tokens from a normalised string.
     * e.g. "barbell bench press" -> "bench press"
     * e.g. "dumbbell bicep curl" -> "bicep curl"
     * e.g. "smith machine upright row" -> "upright row"
     */
    private fun stripEquipmentPrefix(normalizedName: String): String {
        val tokens = normalizedName.split(" ").toMutableList()
        while (tokens.isNotEmpty() && tokens[0] in DB_EQUIPMENT_PREFIX_TOKENS) {
            tokens.removeAt(0)
        }
        return tokens.joinToString(" ")
    }

    /**
     * Compute canonical scoring tokens from a normalised string.
     * Removes noise tokens; applies plural stemming and synonym canonicalisation.
     */
    private fun computeScoreTokens(normalizedName: String): Set<String> =
        normalizedName.split(" ")
            .filter { it.isNotBlank() }
            .map { canonicalToken(it) }
            .filter { it !in SCORE_NOISE_TOKENS }
            .toSet()

    /** Extract the equipment type from a parenthetical, e.g. "(Cable - Straight Bar)" -> "cable". */
    private fun extractEquipmentToken(parenContent: String): String? {
        val lower = parenContent.lowercase()
        return when {
            "barbell"    in lower -> "barbell"
            "dumbbell"   in lower -> "dumbbell"
            "cable"      in lower -> "cable"
            "machine"    in lower -> "machine"
            "kettlebell" in lower -> "kettlebell"
            "band"       in lower -> "band"
            "bodyweight" in lower -> "bodyweight"
            "ez"         in lower -> "ez"
            "assisted"   in lower -> null  // "Assisted" is not an equipment type
            else                  -> null
        }
    }

    private fun equipmentMatches(user: UserInfo, candidate: CandidateInfo): Boolean {
        val userEq = user.equipment ?: return false
        val dbEq   = candidate.entry.equipment?.lowercase() ?: return false
        val dbSynonyms = EQUIPMENT_SYNONYMS[userEq] ?: setOf(userEq)
        return dbSynonyms.any { it in dbEq } || dbEq in dbSynonyms
    }

    private fun equipmentBest(matches: List<CandidateInfo>, user: UserInfo): CandidateInfo =
        matches.minWith(
            Comparator
                .comparingInt<CandidateInfo> { c -> if (equipmentMatches(user, c)) 0 else 1 }
                .thenComparingInt { c -> c.scoreTokens.size }
        )

    // =========================================================================
    // Similarity metrics
    // =========================================================================

    private fun jaccardScore(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return inter.toDouble() / union.toDouble()
    }

    fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshteinDistance(a, b).toDouble() / maxLen
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[m][n]
    }
}
