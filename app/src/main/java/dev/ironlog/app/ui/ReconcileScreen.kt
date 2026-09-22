package dev.ironlog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.reconcile.ExerciseReconciler

/**
 * Reconciliation screen: presents proposed free-exercise-db matches for each exercise.
 * One-tap CONFIRM copies metadata; "No match" leaves the exercise with null metadata.
 * NEVER auto-applies -- every confirm is an explicit user action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileScreen(
    vm: IronlogViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var proposals by remember {
        mutableStateOf<List<ExerciseReconciler.ReconcileProposal>?>(null)
    }
    // Track which exerciseIds have been confirmed in this session
    var confirmedIds by remember { mutableStateOf(setOf<Long>()) }
    // The proposal currently being mapped by hand (opens the catalog picker sheet).
    var manualFor by remember { mutableStateOf<ExerciseReconciler.ReconcileProposal?>(null) }
    val catalog by vm.exercises.collectAsState()

    LaunchedEffect(Unit) {
        proposals = vm.loadReconcileProposals(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm Muscle Maps") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val list = proposals
        if (list == null) {
            // Loading
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    "Loading exercise database...",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            return@Scaffold
        }

        val pending = list.filter { it.exerciseId !in confirmedIds }
        val done = list.filter { it.exerciseId in confirmedIds }

        if (pending.isEmpty() && done.isEmpty()) {
            // All exercises are already mapped (or there are none) -- show empty state.
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "All exercises are mapped ✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "${pending.size} pending, ${done.size} confirmed this session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            items(pending, key = { it.exerciseId }) { proposal ->
                ReconcileCard(
                    proposal = proposal,
                    onConfirm = { candidate ->
                        vm.confirmReconcileMatch(proposal.exerciseId, candidate) {
                            confirmedIds = confirmedIds + proposal.exerciseId
                        }
                    },
                    onSkip = {
                        // Just dismiss from this session; DB not touched
                        confirmedIds = confirmedIds + proposal.exerciseId
                    },
                    onManual = { manualFor = proposal },
                )
            }

            if (done.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Confirmed this session",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(done, key = { "done_${it.exerciseId}" }) { proposal ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Text(
                            "${proposal.exerciseName} -- confirmed",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // Manual catalog picker — for NONE matches (or to override a wrong proposal). Pick a catalog
    // exercise and its muscle/equipment metadata is copied onto the logged one.
    manualFor?.let { proposal ->
        CatalogPickerSheet(
            forName = proposal.exerciseName,
            catalog = catalog.filter { it.catalogOnly && !it.primaryMuscles.isNullOrEmpty() },
            onPick = { picked ->
                vm.mapExerciseToCatalog(proposal.exerciseId, picked.id) {
                    confirmedIds = confirmedIds + proposal.exerciseId
                }
                manualFor = null
            },
            onDismiss = { manualFor = null },
        )
    }
}

/** Full-screen catalog picker. Tap a row to expand its About details (muscles, equipment,
 *  instructions) so you can verify the match, then "Use this exercise" to map it. Full-screen
 *  Dialog instead of a bottom sheet — no drag-to-dismiss finickiness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogPickerSheet(
    forName: String,
    catalog: List<Exercise>,
    onPick: (Exercise) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val filtered = remember(query, catalog) {
        if (query.isBlank()) catalog.take(50)
        else catalog.filter { it.name.contains(query.trim(), ignoreCase = true) }.take(80)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Map \"$forName\"") },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search the exercise database") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Text(
                    "Tap an exercise to read its details, then use it if it's right.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { ex ->
                        val expanded = expandedId == ex.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedId = if (expanded) null else ex.id }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(ex.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Primary: " + (ex.primaryMuscles ?: emptyList()).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (expanded) {
                                Spacer(Modifier.height(6.dp))
                                ex.secondaryMuscles?.takeIf { it.isNotEmpty() }?.let {
                                    Text(
                                        "Secondary: " + it.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                listOfNotNull(ex.equipment, ex.mechanic, ex.force)
                                    .joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                ex.instructions?.take(4)?.forEachIndexed { i, step ->
                                    Text(
                                        "${i + 1}. $step",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Button(
                                    onClick = { onPick(ex) },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text("Use this exercise") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReconcileCard(
    proposal: ExerciseReconciler.ReconcileProposal,
    onConfirm: (ExerciseReconciler.FreeDbEntry) -> Unit,
    onSkip: () -> Unit = {},
    onManual: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                proposal.exerciseName,
                style = MaterialTheme.typography.titleSmall,
            )

            val candidate = proposal.candidate
            if (candidate == null) {
                Text(
                    "No match found in free-exercise-db",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Pick the right one from the database yourself, or dismiss to leave it unmapped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onManual, modifier = Modifier.weight(1f)) {
                        Text("Choose from catalog")
                    }
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                        Text("Dismiss")
                    }
                }
            } else {
                val matchLabel = when (proposal.matchType) {
                    ExerciseReconciler.MatchType.EXACT -> "Exact match"
                    ExerciseReconciler.MatchType.FUZZY ->
                        "Fuzzy match (${(proposal.similarity * 100).toInt()}%)"
                    ExerciseReconciler.MatchType.NONE -> ""
                }
                Text(
                    "$matchLabel: ${candidate.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (candidate.primaryMuscles.isNotEmpty()) {
                    Text(
                        "Primary: ${candidate.primaryMuscles.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (candidate.secondaryMuscles.isNotEmpty()) {
                    Text(
                        "Secondary: ${candidate.secondaryMuscles.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                if (candidate.equipment != null) {
                    Text(
                        "Equipment: ${candidate.equipment}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onConfirm(candidate) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Confirm")
                    }
                    // "Skip" -- dismiss this session, leaves metadata null in DB
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Skip")
                    }
                }
                // Override a wrong auto-match by hand.
                TextButton(onClick = onManual, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Wrong? Choose from catalog")
                }
            }
        }
    }
}
