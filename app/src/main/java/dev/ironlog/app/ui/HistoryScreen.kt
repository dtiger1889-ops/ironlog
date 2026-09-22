package dev.ironlog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.SetEntry
import dev.ironlog.app.data.Workout
import dev.ironlog.app.metrics.CardioFormat
import dev.ironlog.app.metrics.WorkoutStats
import dev.ironlog.app.share.WorkoutReport
import dev.ironlog.app.share.WorkoutShare
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * M3: History tab with month-grouped workout cards, calendar dot-grid, and edit-past-workout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: IronlogViewModel,
    onOpenWorkout: (Long) -> Unit,
    onBack: (() -> Unit)?,
) {
    val history by vm.history.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = if (onBack != null) {
                    { TextButton(onClick = onBack) { Text("Back") } }
                } else {
                    {}
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Text(
                "No workouts yet.",
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }

        // Group workouts by year-month.
        val grouped = remember(history) {
            history.groupBy { monthKey(it.startTime) }
                .entries
                .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, List<Workout>>> {
                    it.key.first * 100 + it.key.second
                })
                .toList()
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            grouped.forEach { (monthKey, workouts) ->
                // Month header + calendar dot-grid
                item(key = "header_$monthKey") {
                    val (year, month) = monthKey
                    MonthSection(workouts = workouts, year = year, month = month)
                }

                // Workout cards for this month
                items(workouts, key = { it.id }) { workout ->
                    WorkoutCard(
                        workout = workout,
                        vm = vm,
                        onClick = { onOpenWorkout(workout.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A pair (year, 1-indexed month) used as a grouping key. */
private fun monthKey(epochMs: Long): Pair<Int, Int> {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = epochMs
    return cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonthSection(workouts: List<Workout>, year: Int, month: Int) {
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, 1, 0, 0, 0)
        }.time)

    val trainingDays = WorkoutStats.workoutDaysInMonth(workouts, year, month)

    val daysInMonth = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(year, month - 1, 1)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(monthName, style = MaterialTheme.typography.titleMedium)
        Text(
            "${workouts.size} workout${if (workouts.size != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Calendar dot-grid: 7-column grid of days
        FlowRow(
            maxItemsInEachRow = 7,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..daysInMonth).forEach { day ->
                val trained = day in trainingDays
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (trained) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$day",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (trained) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun WorkoutCard(
    workout: Workout,
    vm: IronlogViewModel,
    onClick: () -> Unit,
) {
    val sets by produceState(initialValue = emptyList<SetEntry>(), workout.id) {
        value = vm.setsForWorkout(workout.id)
    }
    val exercises by vm.exercises.collectAsState()
    val exerciseById = remember(exercises) { exercises.associateBy { it.id } }

    // Best set per exercise (exclude warm-ups for summary)
    val bestSetByExercise = remember(sets) {
        sets.filter { !it.isWarmup }
            .groupBy { it.exerciseId }
            .mapValues { (_, exSets) -> exSets.maxByOrNull { it.weightLb ?: 0.0 } }
    }
    val totalVolume = sets.filter { !it.isWarmup && it.weightLb != null && it.reps != null }
        .sumOf { (it.weightLb ?: 0.0) * (it.reps ?: 0) }
    val prCount = sets.count { it.isPR }
    val durationMin = workout.durationSec?.let { it / 60 }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(workout.name.ifBlank { "Workout" }, style = MaterialTheme.typography.titleSmall)
            Text(
                formatDay(workout.startTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Best set per exercise (up to 4 lines)
            bestSetByExercise.entries.take(4).forEach { (exId, set) ->
                val exName = exerciseById[exId]?.name ?: "Exercise"
                val setLabel = set?.let { s ->
                    when (exerciseById[exId]?.type) {
                        ExerciseType.TIMED -> CardioFormat.formatDurationLabel(s.seconds)
                        ExerciseType.CARDIO -> CardioFormat.cardioSummary(s.distanceMeters, s.seconds)
                        ExerciseType.BODYWEIGHT -> "${s.reps ?: 0} reps"
                        else -> "${trimNum(s.weightLb)} lb × ${s.reps ?: 0}"
                    }
                } ?: "—"
                Text(
                    "$exName  $setLabel${if (set?.isPR == true) " PR" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (bestSetByExercise.size > 4) {
                Text(
                    "+${bestSetByExercise.size - 4} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Footer
            Text(
                buildString {
                    if (durationMin != null) append("${durationMin}m  ")
                    if (totalVolume > 0) append("${trimNum(totalVolume)} lb total  ")
                    if (prCount > 0) append("$prCount PR${if (prCount != 1) "s" else ""}")
                }.trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Workout detail + edit screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    vm: IronlogViewModel,
    workoutId: Long,
    onBack: () -> Unit,
    onStartEdit: () -> Unit,
) {
    val history by vm.history.collectAsState()
    val exercises by vm.exercises.collectAsState()
    val workout = remember(history, workoutId) { history.firstOrNull { it.id == workoutId } }
    val exerciseById = remember(exercises) { exercises.associateBy { it.id } }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val sets by produceState(initialValue = emptyList<SetEntry>(), workoutId) {
        value = vm.setsForWorkout(workoutId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workout?.name?.ifBlank { "Workout" } ?: "Workout") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (workout != null) {
                        TextButton(onClick = {
                            WorkoutShare.sharePdf(
                                context,
                                WorkoutReport.build(workout, sets, exerciseById),
                            )
                        }) { Text("Share") }
                    }
                    TextButton(onClick = onStartEdit) { Text("Edit") }
                    TextButton(onClick = { showDeleteDialog = true }) { Text("Delete") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workout != null) {
                item { Text(formatDay(workout.startTime), style = MaterialTheme.typography.bodyMedium) }
                workout.durationSec?.let { sec ->
                    item { Text("Duration: ${sec / 60} min", style = MaterialTheme.typography.bodySmall) }
                }
            }
            val grouped = sets.groupBy { it.exerciseId }
            grouped.forEach { (exerciseId, exerciseSets) ->
                item {
                    val ex = exerciseById[exerciseId]
                    Text(
                        ex?.name ?: "Exercise #$exerciseId",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                itemsIndexed(exerciseSets) { i, s ->
                    Text(
                        "  ${i + 1}.  ${formatSet(s, exerciseById[s.exerciseId]?.type)}${if (s.isWarmup) " (warm-up)" else ""}${if (s.isPR) " PR" else ""}",
                    )
                }
            }
            if (sets.isEmpty()) {
                item { Text("No sets recorded for this workout.") }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete workout?") },
            text = { Text("This permanently removes the workout and its sets.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.deleteWorkout(workoutId) { onBack() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatDay(epochMillis: Long): String {
    val fmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return fmt.format(Date(epochMillis))
}

/** One source of truth for set wording, shared with the PDF report. */
private fun formatSet(s: SetEntry, type: ExerciseType?): String = WorkoutReport.setSummary(s, type)

private fun trimNum(v: Double?): String =
    v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it) } ?: "0"
