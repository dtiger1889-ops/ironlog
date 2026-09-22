package dev.ironlog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.gym.MuscleAssistant
import dev.ironlog.app.metrics.MuscleCoverage
import dev.ironlog.app.metrics.WorkoutStats
import dev.ironlog.app.progression.VolumeLandmarks
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf

/**
 * M3 Profile tab: workouts/week bar chart (Vico), streak, PR feed, muscle coverage,
 * weekly-volume card (M4 VolumeLandmarks).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: IronlogViewModel, onOpenSettings: () -> Unit = {}) {
    val exercises by vm.exercises.collectAsState()
    val history by vm.history.collectAsState()

    val allSets by produceState(initialValue = emptyList<SetEntry>()) {
        value = vm.allSets()
    }

    val context = LocalContext.current
    val nowMillis = remember { System.currentTimeMillis() }
    val coverage = MuscleCoverage.compute(allSets, history, exercises, nowMillis)

    // M5 (d): volume-band + neglect status per muscle (vs the practitioner MEV/MAV/MRV model).
    val coverageStatuses by produceState(initialValue = emptyList<MuscleAssistant.MuscleStatus>(), nowMillis) {
        value = vm.muscleCoverageStatus(nowMillis)
    }
    val statusByMuscle = remember(coverageStatuses) {
        coverageStatuses.associateBy { it.muscle.lowercase() }
    }

    LaunchedEffect(Unit) {
        vm.ensureLandmarksSeededAndRefresh(context, nowMillis)
        vm.ensureCatalogSeeded(context)
    }

    // Streak + workouts/week from pure Kotlin
    val streakResult = remember(history) { WorkoutStats.streaks(history, nowMillis) }
    val weekBuckets = remember(history) { WorkoutStats.workoutsPerWeek(history, nowMillis, weeks = 12) }

    // Workouts/week Vico column chart model
    val weekChartModel = remember(weekBuckets) {
        val entries = weekBuckets.mapIndexed { i, b -> entryOf(i.toFloat(), b.count.toFloat()) }
        entryModelOf(entries)
    }

    // PR feed: most recent PRs (sets flagged isPR = true)
    val recentPRs = remember(allSets, exercises) {
        allSets.filter { it.isPR }
            .sortedByDescending { it.id }
            .take(10)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                // Settings was only reachable from the Measure tab's gear — invisible.
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Coach feed
            item {
                CoachSection(vm = vm, nowMillis = nowMillis)
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            }

            // (Stretch timer moved to the Workout home tab, 2026-07-05 — owner note: it was
            // "crammed in with a bunch of stuff under profile".)

            // Streak card
            item {
                Text(
                    "Streak",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatCard(
                        label = "Current streak",
                        value = "${streakResult.currentStreak} day${if (streakResult.currentStreak != 1) "s" else ""}",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Longest streak",
                        value = "${streakResult.longestStreak} day${if (streakResult.longestStreak != 1) "s" else ""}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Workouts/week chart
            if (weekBuckets.isNotEmpty() && weekBuckets.any { it.count > 0 }) {
                item {
                    Text(
                        "Workouts per week (last 12 weeks)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    Chart(
                        chart = columnChart(),
                        model = weekChartModel,
                        startAxis = rememberStartAxis(),
                        bottomAxis = rememberBottomAxis(),
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }

            // PR feed
            if (recentPRs.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Recent PRs",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(recentPRs) { s ->
                    val ex = exercises.find { it.id == s.exerciseId }
                    val label = when {
                        s.weightLb != null && s.reps != null ->
                            "${ex?.name ?: "?"}: ${trimNum(s.weightLb)} lb × ${s.reps}"
                        s.seconds != null -> "${ex?.name ?: "?"}: ${s.seconds}s"
                        else -> ex?.name ?: "Unknown exercise"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            // Weekly-volume card (M4 VolumeLandmarks)
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                WeeklyVolumeCard(allSets = allSets, history = history, exercises = exercises, nowMillis = nowMillis)
            }

            // Muscle heat map (tile form): every tracked muscle as a colored
            // tile — cold (untrained/below maintenance) → green (growth range) → red (over MRV).
            if (coverageStatuses.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    MuscleHeatMapCard(statuses = coverageStatuses)
                }
            }

            // Muscle coverage
            item {
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Muscle Coverage (last 7 days)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                Text(
                    "Direct = the muscle was the target; indirect = it assisted (counts half). " +
                        "The colored band rates weekly volume against the practitioner model " +
                        "(MEV–MAV = growth range). \"Only assisted\" = trained but never directly " +
                        "in 28 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (coverage.unmappedExerciseCount > 0) {
                item {
                    Text(
                        "${coverage.unmappedExerciseCount} exercise(s) not mapped to muscles " +
                            "— go to Exercises tab to confirm matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (coverage.muscles.isEmpty()) {
                item {
                    Text(
                        "No muscle data yet. Confirm exercise matches on the Exercises tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(coverage.muscles) { stats ->
                    val daysSince = MuscleCoverage.daysSince(stats.lastTrainedMillis, nowMillis)
                    val status = statusByMuscle[stats.muscle.lowercase()]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stats.muscle.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (status != null) BandBadge(status.band)
                            }
                            Text(
                                buildString {
                                    if (daysSince == null) append("Never trained")
                                    else if (daysSince == 0) append("Trained today")
                                    else append("${daysSince}d ago")
                                    append("  |  ")
                                    append("Direct: ${stats.directSets7d.toInt()} sets  ")
                                    append("Indirect: ${stats.indirectSets7d.toInt()} sets")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (status != null && (status.neverIsolated || status.stale)) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (status.neverIsolated) NeglectChip("Only assisted (28d)")
                                    if (status.stale) NeglectChip("Stale (>${MuscleAssistant.STALE_THRESHOLD_DAYS}d)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * M7: standalone stretch / hold countdown timer. Reuses rest-timer state + haptics;
 * no new analytics. ACSM static-hold default: 15–60s per set.
 */
/**
 * Muscle heat map, tile form: one colored tile per tracked muscle. Same signal as the previous tracker's
 * body-figure heat map (which muscles are hot/cold this week) without custom body art —
 * color comes from the weekly-volume band, dimmed further when the muscle is stale.
 */
@Composable
internal fun MuscleHeatMapCard(statuses: List<MuscleAssistant.MuscleStatus>) {
    Text(
        "Muscle heat map (weekly volume)",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        statuses.sortedBy { it.muscle }.chunked(3).forEach { rowStatuses ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowStatuses.forEach { st ->
                    val heat = when (st.band) {
                        VolumeLandmarks.Band.NO_TARGET -> MaterialTheme.colorScheme.surfaceVariant
                        VolumeLandmarks.Band.BELOW_MV -> Color(0xFF37474F)          // cold slate
                        VolumeLandmarks.Band.MV_TO_MEV -> Color(0xFF7B6A2F)         // warming amber
                        VolumeLandmarks.Band.MEV_TO_MAV -> Color(0xFF1B5E20)        // growth green
                        VolumeLandmarks.Band.MAV_TO_MRV -> Color(0xFF0D47A1)        // high blue
                        VolumeLandmarks.Band.ABOVE_MRV -> Color(0xFF8B1E1E)         // over red
                    }
                    Surface(
                        color = if (st.stale) heat.copy(alpha = 0.45f) else heat,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(
                                st.muscle.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                            )
                            Text(
                                st.daysSince?.let { d -> if (d == 0) "today" else "${d}d ago" } ?: "never",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                }
                // Pad the last row so tiles keep equal width.
                repeat(3 - rowStatuses.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** M5 (d): colored pill rating a muscle's weekly volume against the practitioner landmark model. */
@Composable
internal fun BandBadge(band: VolumeLandmarks.Band) {
    val (label, container, content) = when (band) {
        VolumeLandmarks.Band.BELOW_MV ->
            Triple("Below maintenance", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        VolumeLandmarks.Band.MV_TO_MEV ->
            Triple("Maintaining", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        VolumeLandmarks.Band.MEV_TO_MAV ->
            // Ideal "sweet spot" — green, matching the app's green = good/done semantics.
            Triple("Growth range", IronlogColors.Green, Color(0xFF06230F))
        VolumeLandmarks.Band.MAV_TO_MRV ->
            Triple("High volume", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        VolumeLandmarks.Band.ABOVE_MRV ->
            Triple("Over MRV", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        VolumeLandmarks.Band.NO_TARGET ->
            Triple("No target", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Small outlined-style chip flagging a neglect condition (never-isolated / stale). */
@Composable
internal fun NeglectChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WeeklyVolumeCard(
    allSets: List<SetEntry>,
    history: List<dev.ironlog.app.data.Workout>,
    exercises: List<dev.ironlog.app.data.Exercise>,
    nowMillis: Long,
) {
    val weeklyCounts = remember(allSets, history, exercises, nowMillis) {
        VolumeLandmarks.weeklySets(allSets, history, exercises, nowMillis)
    }

    Text(
        "Weekly volume",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        ">=10 sets/muscle/week is the evidence-based floor for hypertrophy (Schoenfeld 2017).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    val topMuscles = weeklyCounts.sortedByDescending { it.weightedSets }.take(8)

    if (topMuscles.isEmpty()) {
        Text(
            "Log workouts to see per-muscle weekly sets.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        topMuscles.forEach { wsc ->
            val sets = wsc.weightedSets
            val floor = VolumeLandmarks.CITED_WEEKLY_FLOOR
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(wsc.muscle.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                val indicator = if (sets >= floor) "ok ${trimNum(sets)}" else "low ${trimNum(sets)}"
                Text(
                    indicator,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sets >= floor) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Practitioner model, not peer-reviewed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun trimNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
