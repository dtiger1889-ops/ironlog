package dev.ironlog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.LaunchedEffect
import dev.ironlog.app.data.CoachSummary
import dev.ironlog.app.data.Workout
import java.util.Calendar

/** M7 stretch timer (ACSM-cited). On the Workout tab since 2026-07-05 (was crammed in Profile). */
@Composable
internal fun StretchTimerCard(vm: IronlogViewModel) {
    val stretch by vm.stretch.collectAsState()
    var secondsText by remember { mutableStateOf("30") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stretch timer", style = MaterialTheme.typography.titleSmall)
            Text(
                "ACSM: 15–60s static hold, 2–4 sets per stretch.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stretch == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it },
                        label = { Text("Seconds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        val sec = secondsText.toIntOrNull()?.coerceIn(5, 600) ?: 30
                        vm.startStretch(sec)
                    }) { Text("Start") }
                }
            } else {
                val remaining = stretch!!.remainingMs
                val total = stretch!!.totalMs
                val remainingSec = ((remaining + 999) / 1000).toInt()
                Text(
                    "$remainingSec s remaining",
                    style = MaterialTheme.typography.headlineSmall,
                )
                LinearProgressIndicator(
                    progress = { if (total > 0) remaining.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { vm.stopStretch() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    vm: IronlogViewModel,
    onStartWorkout: () -> Unit,
    onStartTemplate: (Long) -> Unit,
    onEditTemplate: (Long) -> Unit,
    onNewTemplate: () -> Unit,
    onOpenHistory: () -> Unit,
    onResume: () -> Unit = {},
) {
    val counts by vm.counts.collectAsState()
    val templates by vm.templates.collectAsState()
    val history by vm.history.collectAsState()
    val lastSummary by vm.lastCoachSummary.collectAsState()
    val activeDraft by vm.draft.collectAsState()
    val context = LocalContext.current

    // A workout in progress must never be silently discarded by starting another. Tapping any
    // "start" while one is active routes through this confirm; the stored lambda runs on confirm.
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guardedStart(action: () -> Unit) {
        if (activeDraft != null) pendingStart = action else action()
    }

    LaunchedEffect(Unit) {
        vm.refreshLastCoachSummary()
        vm.loadReminderConfig()
        vm.loadPlanEntries()
    }

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 24.dp,
            bottom = 24.dp + navBarBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("ironlog", style = MaterialTheme.typography.headlineLarge) }
        item {
            Text(
                "${counts.sets} sets · ${counts.workouts} workouts · ${counts.exercises} exercises",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Resume banner — an in-progress workout survives navigating away; this brings it back.
        activeDraft?.let { d ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResume() },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Workout in progress", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(d.name.ifBlank { "Workout" }, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Resume", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Button(onClick = { guardedStart(onStartWorkout) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start empty workout")
            }
        }

        // M7: coach summary card (last finished workout)
        if (lastSummary != null) {
            item {
                CoachSummaryCard(summary = lastSummary!!)
            }
        }

        // M7: "On this day" card
        val onThisDay = onThisDayWorkouts(history)
        if (onThisDay.isNotEmpty()) {
            item {
                OnThisDayCard(workouts = onThisDay)
            }
        }

        item {
            Text(
                "Templates",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(templates, key = { it.id }) { template ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { guardedStart { onStartTemplate(template.id) } },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        template.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // Manual ordering (persisted): free template arrangement.
                    TextButton(
                        onClick = { vm.moveTemplate(template.id, up = true) },
                        enabled = templates.firstOrNull()?.id != template.id,
                    ) { Text("↑") }
                    TextButton(
                        onClick = { vm.moveTemplate(template.id, up = false) },
                        enabled = templates.lastOrNull()?.id != template.id,
                    ) { Text("↓") }
                    TextButton(onClick = { onEditTemplate(template.id) }) { Text("Edit") }
                    Text("Start", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            OutlinedButton(onClick = onNewTemplate, modifier = Modifier.fillMaxWidth()) {
                Text("New template")
            }
        }

        // Stretch timer lives HERE (the tab that's open at the gym), not buried in Profile
        // (moved 2026-07-05, owner note).
        item { StretchTimerCard(vm = vm) }

        item {
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("History")
            }
        }
        item {
            OutlinedButton(
                onClick = { vm.importBundledHistory(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (counts.workouts == 0) "Import training history" else "Re-import training history")
            }
        }
    }

    // Starting a new workout while one is in progress is the only non-Discard path that drops it,
    // so make it an explicit choice.
    pendingStart?.let { start ->
        AlertDialog(
            onDismissRequest = { pendingStart = null },
            title = { Text("Workout already in progress") },
            text = { Text("Starting a new workout will discard your current one. Resume it instead from the banner at the top.") },
            confirmButton = {
                TextButton(onClick = { pendingStart = null; start() }) { Text("Discard & start new") }
            },
            dismissButton = {
                TextButton(onClick = { pendingStart = null }) { Text("Keep current") }
            },
        )
    }
}

@Composable
internal fun CoachSummaryCard(summary: CoachSummary) {
    val allLines = buildList {
        summary.prLines?.forEach { add(Pair("PR", it)) }
        summary.progressionLines?.forEach { add(Pair("Progression", it)) }
        summary.volumeLines?.forEach { add(Pair("Volume", it)) }
        summary.neglectLines?.forEach { add(Pair("Neglect", it)) }
    }
    if (allLines.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last workout — coach summary", style = MaterialTheme.typography.titleSmall)
            allLines.forEach { (_, line) ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun OnThisDayCard(workouts: List<Workout>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("On this day", style = MaterialTheme.typography.titleSmall)
            workouts.forEach { wo ->
                val year = Calendar.getInstance().also { it.timeInMillis = wo.startTime }.get(Calendar.YEAR)
                Text(
                    "${wo.name} ($year)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun onThisDayWorkouts(history: List<Workout>): List<Workout> {
    val now = Calendar.getInstance()
    val todayMonth = now.get(Calendar.MONTH)
    val todayDay = now.get(Calendar.DAY_OF_MONTH)
    val thisYear = now.get(Calendar.YEAR)
    return history.filter { wo ->
        val cal = Calendar.getInstance().also { it.timeInMillis = wo.startTime }
        cal.get(Calendar.MONTH) == todayMonth &&
            cal.get(Calendar.DAY_OF_MONTH) == todayDay &&
            cal.get(Calendar.YEAR) != thisYear   // only past years, not today
    }
}
