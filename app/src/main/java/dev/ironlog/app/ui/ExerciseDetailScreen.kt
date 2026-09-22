package dev.ironlog.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.SetWithWorkoutTime
import dev.ironlog.app.metrics.CardioFormat
import dev.ironlog.app.metrics.ExerciseAggregates
import dev.ironlog.app.metrics.OneRepMax
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * M3: 4-tab exercise detail screen.
 * About — instructions, metadata.
 * History — per-set list with est-1RM, warm-ups de-emphasized.
 * Charts — Vico line charts: est-1RM, max weight, volume over time.
 * Records — all-time bests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    vm: IronlogViewModel,
    exerciseId: Long,
    onBack: () -> Unit,
) {
    val exercises by vm.exercises.collectAsState()
    val exercise = remember(exercises, exerciseId) { exercises.find { it.id == exerciseId } }

    val setsWithTime by produceState<List<SetWithWorkoutTime>>(emptyList(), exerciseId) {
        value = vm.exerciseSetsWithTime(exerciseId)
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("About", "History", "Charts", "Records")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise?.name ?: "Exercise") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(label) },
                    )
                }
            }

            when (selectedTab) {
                0 -> exercise?.let { AboutTab(it) } ?: Text("Loading…", modifier = Modifier.padding(16.dp))
                1 -> HistoryTab(setsWithTime, exercise?.type)
                2 -> ChartsTab(setsWithTime)
                3 -> RecordsTab(setsWithTime)
            }
        }
    }
}

@Composable
private fun AboutTab(exercise: Exercise) {
    val hasAnyDetails = !exercise.primaryMuscles.isNullOrEmpty() ||
        !exercise.secondaryMuscles.isNullOrEmpty() ||
        !exercise.equipment.isNullOrBlank() ||
        !exercise.category.isNullOrBlank() ||
        !exercise.instructions.isNullOrEmpty()
    // Bundled illustration (free-exercise-db, public domain; downscaled at build prep time).
    val context = LocalContext.current
    val imageBitmap = remember(exercise.freeDbId) {
        exercise.freeDbId?.let { id ->
            runCatching {
                context.assets.open("exercise_images/$id.jpg").use { ins ->
                    android.graphics.BitmapFactory.decodeStream(ins)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (imageBitmap != null) {
            item {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = exercise.name,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        if (!hasAnyDetails) {
            item {
                Text(
                    "No details yet — this exercise isn't mapped to the catalog.\n\n" +
                        "Map it from Exercises → \"Confirm muscle maps\" to pull in muscles, " +
                        "equipment, and instructions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        item {
            MetaRow("Primary muscles", exercise.primaryMuscles?.joinToString(", "))
            MetaRow("Secondary muscles", exercise.secondaryMuscles?.joinToString(", "))
            MetaRow("Equipment", exercise.equipment)
            MetaRow("Mechanic", exercise.mechanic)
            MetaRow("Force", exercise.force)
            MetaRow("Category", exercise.category)
            MetaRow("Movement pattern", exercise.movementPattern)
        }
        if (!exercise.instructions.isNullOrEmpty()) {
            item {
                Text(
                    "Instructions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(exercise.instructions.orEmpty().withIndex().toList()) { (i, step) ->
                Text(
                    "${i + 1}. $step",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
internal fun HistoryTab(setsWithTime: List<SetWithWorkoutTime>, type: ExerciseType? = null) {
    if (setsWithTime.isEmpty()) {
        Text("No sets logged yet.", modifier = Modifier.padding(16.dp))
        return
    }
    // Group by workout date.
    val grouped = setsWithTime.groupBy { it.workoutStartTime }.entries
        .sortedByDescending { it.key }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        grouped.forEach { (workoutMs, sets) ->
            item {
                Text(
                    fmtDate(workoutMs),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(sets) { s ->
                val est1RM = if (s.weightLb != null && s.reps != null)
                    OneRepMax.hybrid(s.weightLb, s.reps) else null
                val label = when (type) {
                    // A cardio set is distance AND time -- and the pace they imply.
                    ExerciseType.CARDIO ->
                        CardioFormat.cardioSummary(s.distanceMeters, s.seconds) +
                            if (s.isPR) " PR" else ""
                    ExerciseType.TIMED ->
                        CardioFormat.formatDurationLabel(s.seconds) + if (s.isPR) " PR" else ""
                    else -> buildString {
                        if (s.weightLb != null) append("${trimNum(s.weightLb)} lb")
                        if (s.reps != null) append(" × ${s.reps}")
                        if (est1RM != null) append("  [~${trimNum(est1RM)} lb 1RM est.]")
                        if (s.seconds != null) append(CardioFormat.formatDurationLabel(s.seconds))
                        if (s.isPR) append(" PR")
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.isWarmup)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }
            item { HorizontalDivider() }
        }
    }
}

@Composable
internal fun ChartsTab(setsWithTime: List<SetWithWorkoutTime>) {
    if (setsWithTime.isEmpty()) {
        Text("No data to chart yet.", modifier = Modifier.padding(16.dp))
        return
    }

    // Best est-1RM per workout session.
    val est1RMSeries = setsWithTime
        .groupBy { it.workoutStartTime }
        .entries
        .sortedBy { it.key }
        .mapIndexedNotNull { i, (_, sets) ->
            val best = sets.mapNotNull { s ->
                if (s.weightLb != null && s.reps != null) OneRepMax.hybrid(s.weightLb, s.reps) else null
            }.maxOrNull()
            best?.let { entryOf(i.toFloat(), it.toFloat()) }
        }

    // Max weight per workout session.
    val maxWeightSeries = setsWithTime
        .groupBy { it.workoutStartTime }
        .entries
        .sortedBy { it.key }
        .mapIndexedNotNull { i, (_, sets) ->
            sets.mapNotNull { it.weightLb }.maxOrNull()?.let {
                entryOf(i.toFloat(), it.toFloat())
            }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (est1RMSeries.isNotEmpty()) {
            item {
                Text(
                    "Est. 1RM over time",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Epley/Brzycki hybrid, capped ≤ 12 reps. Practitioner model, not peer-reviewed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Chart(
                    chart = lineChart(),
                    model = entryModelOf(est1RMSeries),
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
        }
        if (maxWeightSeries.isNotEmpty()) {
            item {
                Text("Max weight per session (lb)", style = MaterialTheme.typography.titleSmall)
                Chart(
                    chart = lineChart(),
                    model = entryModelOf(maxWeightSeries),
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
        }
    }
}

@Composable
internal fun RecordsTab(setsWithTime: List<SetWithWorkoutTime>) {
    if (setsWithTime.isEmpty()) {
        Text("No records yet.", modifier = Modifier.padding(16.dp))
        return
    }

    val agg = remember(setsWithTime) {
        ExerciseAggregates.compute(
            setsWithTime.map { s ->
                dev.ironlog.app.data.SetEntry(
                    id = s.id,
                    workoutId = s.workoutId,
                    exerciseId = s.exerciseId,
                    setOrder = s.setOrder,
                    weightLb = s.weightLb,
                    reps = s.reps,
                    seconds = s.seconds,
                    distanceMeters = s.distanceMeters,
                    isPR = s.isPR,
                    isWarmup = s.isWarmup,
                )
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("All-time bests", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
        item { MetaRow("Heaviest weight", agg.maxWeightLb?.let { "${trimNum(it)} lb" }) }
        item { MetaRow("Best set volume", agg.maxVolumeLb?.let { "${trimNum(it)} lb·reps" }) }

        if (agg.best1RMByReps.isNotEmpty()) {
            item {
                Text(
                    "Best est. 1RM by rep count",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Practitioner model — suppressed above 12 reps.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(agg.best1RMByReps.entries.sortedBy { it.key }.toList()) { (reps, est) ->
                MetaRow("${reps}-rep best", "~${trimNum(est)} lb")
            }
        }
    }
}

private fun fmtDate(epochMs: Long): String =
    SimpleDateFormat("EEE MMM d, yyyy", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochMs))

private fun trimNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
