package dev.ironlog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.ironlog.app.progression.Citations
import dev.ironlog.app.progression.GoalPreset
import dev.ironlog.app.progression.SuggestionEngine

/**
 * M4 Coach feed: evidence-based, suggestion-only progression. Renders ProgressionSuggestions with
 * Accept/Dismiss, the default-goal chooser (HYPERTROPHY by design), and a read-only backtest.
 *
 * Discipline made visible: every card shows its citation; practitioner-model numbers carry the
 * "practitioner model, not peer-reviewed" tag. Accepting only records the verdict + surfaces the
 * suggested load as a hint -- it never writes a set or edits a template.
 *
 * Rendered as a set of items inside the Profile LazyColumn (call from a `item {}` block).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoachSection(
    vm: IronlogViewModel,
    nowMillis: Long,
) {
    val suggestions by vm.suggestions.collectAsState()
    val goal by vm.defaultGoalPreset.collectAsState()
    val backtest by vm.backtest.collectAsState()

    var helpOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Coach",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Evidence-based suggestions. Targets come from published standards, never your own " +
                "history. Suggestions only — nothing changes your logged sets.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- Expandable "How this works" help ---
        Text(
            if (helpOpen) "▾ How this works" else "▸ How this works",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { helpOpen = !helpOpen }.padding(vertical = 2.dp),
        )
        if (helpOpen) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HelpLine("What the cards are", "Each card is a read of your logged history against published training science. Four kinds:")
                    HelpBullet("ADD LOAD", "you beat the top of your rep range by 2+ reps twice in a row (the 2-for-2 rule) → add weight.")
                    HelpBullet("DELOAD", "your top-set strength dropped two sessions running → back off ~10% to recover.")
                    HelpBullet("TIMED", "for holds (planks etc.): you hit the target hold twice → add ~10s.")
                    HelpBullet("VOLUME LOW", "a muscle is under its weekly set target (or the cited 10-sets/week floor).")
                    HelpLine("Accept", "Records that you'll do it AND drops the number into your next workout for that exercise as a gray \"Coach:\" hint. It never fills in a set for you — you still type and confirm. The hint clears once you've logged that lift.")
                    HelpLine("Dismiss", "Clears the card without keeping a hint. Use it when you disagree or it's not relevant right now.")
                    HelpLine("Muscle Coverage (below)", "The raw picture, no advice: days since you trained each muscle and how many direct/indirect sets it got in the last 7 days.")
                    HelpLine("Why trust it", "Every card shows its source. Anything that's a coach's rule-of-thumb rather than peer-reviewed gets a red \"practitioner model\" tag. Your own logs never set the targets — they only decide which cards appear.")
                }
            }
        }

        // --- Default-goal chooser (M4 step 12: HYPERTROPHY is a stated app choice) ---
        Text(
            "Default goal (rep range)",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        GoalChipsRow(
            selected = goal,
            onSelect = { vm.setDefaultGoalPreset(it, nowMillis) },
        )
        Text(
            "★ = app default (a deliberate choice, not inferred from your logs). ${Citations.REP_RANGES}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- Suggestion feed ---
        if (suggestions.isEmpty()) {
            Text(
                "No suggestions right now. Log a few sessions and the coach will flag when to add " +
                    "load, deload, or pick up an under-trained muscle.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            suggestions.forEach { s ->
                SuggestionCard(
                    suggestion = s,
                    onAccept = { vm.acceptSuggestion(s, nowMillis) },
                    onDismiss = { vm.dismissSuggestion(s, nowMillis) },
                )
            }
        }

        // --- Backtest (read-only insight) ---
        Text(
            "Backtest",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(onClick = { vm.runBacktest() }, modifier = Modifier.fillMaxWidth()) {
            Text("Run backtest over full history")
        }
        backtest?.let { bt ->
            Text(
                "Across ${bt.totalSessions} sessions: the 2-for-2 rule would have signalled " +
                    "add-load ${bt.totalAddLoadSignals} times and stall/deload " +
                    "${bt.totalStallSignals} times. Insight only — nothing was written.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            bt.perExercise.take(8).forEach { e ->
                Text(
                    "• ${e.exerciseName}: ${e.sessionsAnalyzed} sessions, " +
                        "+load ×${e.addLoadSignals}, deload ×${e.stallSignals}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionCard(
    suggestion: SuggestionEngine.Suggestion,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hint = remember(suggestion) {
        when {
            suggestion.machineWeightLb != null -> {
                val w = suggestion.machineWeightLb
                val txt = if (w == w.toLong().toDouble()) w.toLong().toString() else "%.1f".format(w)
                "Suggested working weight: $txt lb (prefill hint only)"
            }
            suggestion.machineSeconds != null -> "Suggested hold: ${suggestion.machineSeconds}s (prefill hint only)"
            else -> null
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(suggestion.kind.name.replace('_', ' ')) },
                    modifier = Modifier.wrapContentWidth(),
                )
            }
            Text(
                suggestion.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (suggestion.practitionerModel) {
                Text(
                    Citations.PRACTITIONER_TAG,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                suggestion.citation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "Accept = hint it into next workout · Dismiss = clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                TextButton(onClick = onAccept) { Text("Accept") }
            }
        }
    }
}

/**
 * The Default-goal (rep range) chip row. Extracted as a stateless, internal composable so the
 * screenshot-test rig can render it without a ViewModel — it guards the FlowRow layout that
 * previously wrapped "Endurance 15–20" mid-word.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun GoalChipsRow(
    selected: GoalPreset,
    onSelect: (GoalPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GoalPreset.entries.forEach { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = { onSelect(preset) },
                label = {
                    Text(
                        "${preset.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                            "${preset.low}–${preset.high}" +
                            if (preset == GoalPreset.DEFAULT) " ★" else "",
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun HelpLine(heading: String, body: String) {
    Text(
        buildString { append(heading); append(" — "); append(body) },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun HelpBullet(label: String, body: String) {
    Text(
        "• $label: $body",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
    )
}
