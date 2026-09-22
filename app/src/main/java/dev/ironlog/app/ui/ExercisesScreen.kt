package dev.ironlog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.importer.WeightConversion
import dev.ironlog.app.metrics.PlateCalcConfig
import dev.ironlog.app.progression.GoalPreset
import dev.ironlog.app.progression.IncrementSeeder

/**
 * Exercises tab (M3): full 873-row catalog with search, filter, sort.
 * The user's logged exercises appear first; catalog-only rows follow.
 * Tap → 4-tab detail sheet (About / History / Charts / Records).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExercisesScreen(
    vm: IronlogViewModel,
    onOpenReconcile: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenGym: () -> Unit = {},
) {
    val exercises by vm.exercises.collectAsState()
    val defaultBarLb by vm.defaultBarLb.collectAsState()
    val unmappedCount = exercises.count { !it.catalogOnly && it.primaryMuscles.isNullOrEmpty() }

    // rememberSaveable so the search + filters survive opening an exercise and backing out.
    var query by rememberSaveable { mutableStateOf("") }
    var filterCategory by rememberSaveable { mutableStateOf<String?>(null) }
    // Default OFF = open to your own exercises (not buried under the 873-row catalog);
    // flip on to browse the full catalog. (Name is historical: it means "include the catalog".)
    var showCatalogOnly by rememberSaveable { mutableStateOf(false) }
    var sheetExercise by remember { mutableStateOf<Exercise?>(null) }

    // Collect distinct categories for filter chips.
    val categories = remember(exercises) {
        exercises.mapNotNull { it.category }.toSortedSet().toList()
    }

    val filtered = remember(exercises, query, filterCategory, showCatalogOnly) {
        exercises.filter { ex ->
            (showCatalogOnly || !ex.catalogOnly) &&
                (filterCategory == null || ex.category == filterCategory) &&
                (query.isBlank() || ex.name.contains(query, ignoreCase = true) ||
                    ex.primaryMuscles?.any { m -> m.contains(query, ignoreCase = true) } == true ||
                    ex.bodyPart?.contains(query, ignoreCase = true) == true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exercises (${filtered.size})") },
                actions = { TextButton(onClick = onOpenGym) { Text("My Gym") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Search + reconcile header
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search by name or muscle…") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Show full catalog", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showCatalogOnly, onCheckedChange = { showCatalogOnly = it })
                    }
                }
            }

            // Category filter chips
            if (categories.isNotEmpty()) {
                item {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = filterCategory == null,
                            onClick = { filterCategory = null },
                            label = { Text("All") },
                        )
                        categories.forEach { cat ->
                            FilterChip(
                                selected = filterCategory == cat,
                                onClick = {
                                    filterCategory = if (filterCategory == cat) null else cat
                                },
                                label = { Text(cat.replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                }
            }

            // Reconcile button (only for the user's unmapped logged exercises)
            if (unmappedCount > 0) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Button(
                            onClick = onOpenReconcile,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Confirm muscle maps ($unmappedCount unmapped)") }
                        Text(
                            "$unmappedCount of your exercises have no muscle mapping. " +
                                "Confirm matches to unlock coverage stats.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }

            // Section header: his exercises
            if (!showCatalogOnly) {
                // only logged visible — no section label needed
            } else {
                val loggedInView = filtered.count { !it.catalogOnly }
                val catalogInView = filtered.count { it.catalogOnly }
                if (loggedInView > 0 && catalogInView > 0) {
                    item {
                        Text(
                            "Your exercises",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Exercise list: logged first, catalog-only second (already sorted by DAO)
            var catalogHeaderShown = false
            items(filtered, key = { it.id }) { exercise ->
                // Inject catalog section header before the first catalog-only item
                if (exercise.catalogOnly && !catalogHeaderShown && showCatalogOnly) {
                    // Can't inject items mid-items() block; we handle this via sticky header below
                    catalogHeaderShown = true
                }
                ExerciseRow(
                    exercise = exercise,
                    onTap = { onOpenDetail(exercise.id) },
                    onSettings = { sheetExercise = exercise },
                )
                HorizontalDivider()
            }
        }
    }

    sheetExercise?.let { ex ->
        ExerciseSettingsSheet(
            exercise = ex,
            defaultBarLb = defaultBarLb,
            onDismiss = { sheetExercise = null },
            onSave = { goal, increment, enabled, plateCalcMode, barWeightLb, loggedAs ->
                vm.setExerciseProgression(
                    exerciseId = ex.id,
                    goalPreset = goal,
                    incrementLb = increment,
                    progressionEnabled = enabled,
                    nowMillis = System.currentTimeMillis(),
                )
                vm.setExercisePlateCalcConfig(ex.id, plateCalcMode, barWeightLb)
                if (loggedAs != ex.type) vm.setExerciseType(ex.id, loggedAs)
                sheetExercise = null
            },
        )
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    onTap: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(exercise.name, style = MaterialTheme.typography.titleSmall)
            val subtitle = when {
                !exercise.primaryMuscles.isNullOrEmpty() ->
                    exercise.primaryMuscles.joinToString(", ")
                exercise.bodyPart != null -> exercise.bodyPart
                else -> "No muscle data"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (exercise.catalogOnly) {
                Text(
                    "Catalog",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!exercise.catalogOnly && !exercise.progressionEnabled) {
            Text(
                "Off",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        TextButton(onClick = onSettings) { Text("⋯") }
    }
}

/**
 * Per-exercise settings sheet, reachable from the Exercises library's "⋯" row action.
 * Started as progression-only (M4); F2 (2026-07-18) added the plate-calc override section so
 * this is a second, non-in-workout entry point for the same config the in-workout "Plate
 * calculator…" menu item edits.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExerciseSettingsSheet(
    exercise: Exercise,
    defaultBarLb: Double,
    onDismiss: () -> Unit,
    onSave: (
        goal: GoalPreset?,
        incrementLb: Double?,
        enabled: Boolean,
        plateCalcMode: String?,
        barWeightLb: Double?,
        loggedAs: ExerciseType,
    ) -> Unit,
) {
    var goal by remember {
        mutableStateOf(exercise.goalPreset?.let { runCatching { GoalPreset.valueOf(it) }.getOrNull() })
    }
    var enabled by remember { mutableStateOf(exercise.progressionEnabled) }
    val seeded = remember(exercise) { IncrementSeeder.seedIncrementLb(exercise) }
    var incrementText by remember {
        mutableStateOf(exercise.incrementLb?.let { fmt(it) } ?: "")
    }
    var plateCalcMode by remember { mutableStateOf(exercise.plateCalcMode) }
    var loggedAs by remember { mutableStateOf(exercise.type) }
    var barWeightLbText by remember {
        mutableStateOf(exercise.barWeightLb?.let { fmt(it) } ?: "")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(exercise.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "Progression settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Progression enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Text(
                "Goal (rep range)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = goal == null,
                    onClick = { goal = null },
                    label = { Text("App default") },
                )
                GoalPreset.entries.forEach { p ->
                    FilterChip(
                        selected = goal == p,
                        onClick = { goal = p },
                        label = { Text("${p.name.lowercase().replaceFirstChar { it.uppercase() }} ${p.low}–${p.high}") },
                    )
                }
            }

            OutlinedTextField(
                value = incrementText,
                onValueChange = { incrementText = it },
                label = { Text("Load increment (lb)") },
                placeholder = {
                    Text(seeded?.let { "auto: ${fmt(it)} lb" } ?: "machine: next pin")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                "Blank = use the seeded lift-class increment (practitioner model, ACSM 2–10% cap).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            Text(
                "Logs as",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                "Which columns this exercise shows. Changing it never deletes anything you "
                    + "have already logged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                ExerciseType.entries.forEach { t ->
                    FilterChip(
                        selected = loggedAs == t,
                        onClick = { loggedAs = t },
                        label = { Text(t.typeLabel()) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            Text(
                "Plate calculator",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                "Off by default for machines/cables; on by default for barbells. Force it either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(null, PlateCalcConfig.MODE_ON, PlateCalcConfig.MODE_OFF).forEach { m ->
                    val label = when (m) {
                        null -> "Auto"
                        PlateCalcConfig.MODE_ON -> "On"
                        else -> "Off"
                    }
                    FilterChip(
                        selected = plateCalcMode == m,
                        onClick = { plateCalcMode = m },
                        label = { Text(label) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = barWeightLbText,
                    onValueChange = { barWeightLbText = it },
                    label = { Text("Base weight (lb)") },
                    placeholder = { Text("default: ${fmt(defaultBarLb)}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = barWeightLbText.toDoubleOrNull()
                        ?.let { "%.1f".format(WeightConversion.lbToKg(it)) } ?: "",
                    onValueChange = { kg ->
                        barWeightLbText = kg.toDoubleOrNull()
                            ?.let { fmt(WeightConversion.kgToLbRaw(it)) } ?: ""
                    },
                    label = { Text("kg") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "e.g. a plate-loaded machine with a 95 kg base — blank = the Settings default bar weight.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    val inc = incrementText.trim().toDoubleOrNull()
                    val barWeight = barWeightLbText.trim().toDoubleOrNull()
                    onSave(goal, inc, enabled, plateCalcMode, barWeight, loggedAs)
                }) { Text("Save") }
            }
        }
    }
}

/** Plain-words name for each logging style, matching the in-workout "Logs as" chooser. */
private fun ExerciseType.typeLabel(): String = when (this) {
    ExerciseType.WEIGHTED -> "Weight × reps"
    ExerciseType.BODYWEIGHT -> "Reps only"
    ExerciseType.TIMED -> "Time"
    ExerciseType.DISTANCE -> "Distance"
    ExerciseType.CARDIO -> "Distance + time"
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
