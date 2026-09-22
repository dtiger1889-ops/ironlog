package dev.ironlog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.TemplateExerciseDetail
import dev.ironlog.app.data.TemplateSetValue
import dev.ironlog.app.metrics.CardioFormat

/** Edit an existing template (templateId > 0) or create a new one (templateId <= 0).
 *  Renders the SAME per-set grid as a live workout — SET / PREV / weight / reps per row —
 *  so editing a routine looks and feels like logging it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(
    vm: IronlogViewModel,
    templateId: Long,
    onDone: () -> Unit,
) {
    val catalog by vm.exercises.collectAsState()
    val isNew = templateId <= 0L
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var templateNote by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<TemplateExerciseDetail>>(emptyList()) }
    // Gray PREVIOUS-column labels per exercise id (last session), loaded async.
    val prevLabels = remember { mutableStateMapOf<Long, List<String>>() }
    var showPicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(templateId) {
        if (!isNew) {
            vm.templateDetail(templateId)?.let { detail ->
                name = detail.name
                templateNote = detail.note ?: ""
                items = detail.exerciseDetails.map { d ->
                    val (seed, labels) = vm.editorPrevFor(d.exercise)
                    prevLabels[d.exercise.id] = labels
                    // Legacy templates with no per-set data: seed the grid from last session
                    // (or a single blank set) so it still shows an editable workout-style grid.
                    if (d.sets.isEmpty()) {
                        val seeded = seed.ifEmpty { listOf(TemplateSetValue()) }
                        d.copy(sets = seeded)
                    } else {
                        d
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New template" else "Edit template") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                actions = {
                    if (!isNew) {
                        TextButton(onClick = { showDelete = true }) { Text("Delete") }
                    }
                    TextButton(onClick = {
                        vm.saveTemplate(
                            id = if (isNew) null else templateId,
                            name = name,
                            note = templateNote.ifBlank { null },
                            exerciseDetails = items,
                        ) { onDone() }
                    }) { Text("Save") }
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
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Template name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (items.isEmpty()) {
                item { Text("No exercises yet. Tap \"Add exercise\".") }
            }
            itemsIndexed(items, key = { _, e -> e.exercise.id }) { i, detail ->
                TemplateExerciseCard(
                    detail = detail,
                    previous = prevLabels[detail.exercise.id] ?: emptyList(),
                    index = i,
                    total = items.size,
                    onMoveUp = {
                        items = items.toMutableList().also { it.add(i - 1, it.removeAt(i)) }
                    },
                    onMoveDown = {
                        items = items.toMutableList().also { it.add(i + 1, it.removeAt(i)) }
                    },
                    onRemove = {
                        items = items.filterIndexed { idx, _ -> idx != i }
                    },
                    onUpdate = { updated ->
                        items = items.toMutableList().also { it[i] = updated }
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add exercise") }
            }
        }
    }

    if (showPicker) {
        val available = remember(catalog, items) {
            val chosen = items.map { it.exercise.id }.toSet()
            catalog.filter { it.id !in chosen }
        }
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TemplateExercisePicker(
                available = available,
                onPick = { ex ->
                    showPicker = false
                    // Seed the new exercise's grid from its last session,
                    // falling back to a single blank set when there is no history.
                    scope.launch {
                        val (seed, labels) = vm.editorPrevFor(ex)
                        prevLabels[ex.id] = labels
                        items = items + TemplateExerciseDetail(
                            exercise = ex,
                            targetSets = null,
                            targetRepsLow = null,
                            targetRepsHigh = null,
                            targetRestSec = ex.restSeconds,
                            sets = seed.ifEmpty { listOf(TemplateSetValue()) },
                        )
                    }
                },
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete template?") },
            text = { Text("\"${name.ifBlank { "This template" }}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.deleteTemplate(templateId) { onDone() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

// Column widths mirror the active-workout SetRow so the grid reads identically.
private val TPL_COL_NUM = 32.dp

/**
 * Workout-style exercise card: a per-set grid (SET / PREV / weight / reps) that looks like
 * the live workout, plus + Set / − Set and a rest field.
 */
@Composable
private fun TemplateExerciseCard(
    detail: TemplateExerciseDetail,
    previous: List<String>,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onUpdate: (TemplateExerciseDetail) -> Unit,
) {
    val type = detail.exercise.type
    var restMinText by remember(detail.targetRestSec) {
        mutableStateOf(detail.targetRestSec?.let { "%.0f".format(it / 60.0) } ?: "")
    }

    fun updateSets(transform: (MutableList<TemplateSetValue>) -> Unit) {
        val newSets = detail.sets.toMutableList().also(transform)
        onUpdate(detail.copy(sets = newSets))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Exercise name + move/remove controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    detail.exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Row {
                    TextButton(enabled = index > 0, onClick = onMoveUp) { Text("↑") }
                    TextButton(enabled = index < total - 1, onClick = onMoveDown) { Text("↓") }
                    TextButton(onClick = onRemove) { Text("✕") }
                }
            }

            if (detail.sets.isNotEmpty()) {
                TemplateHeaderRow(type)
                detail.sets.forEachIndexed { i, set ->
                    TemplateSetRow(
                        index = i,
                        type = type,
                        set = set,
                        previous = previous.getOrNull(i),
                        onChange = { transform -> updateSets { it[i] = transform(it[i]) } },
                    )
                }
            }

            // Footer: + Set / − Set + rest
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {
                    // Copy the last row (like the workout's + Set), else a blank set.
                    updateSets { it.add(it.lastOrNull() ?: TemplateSetValue()) }
                }) { Text("+ Set") }
                if (detail.sets.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        updateSets { if (it.isNotEmpty()) it.removeAt(it.lastIndex) }
                    }) { Text("− Set") }
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = restMinText,
                    onValueChange = {
                        restMinText = it
                        onUpdate(
                            detail.copy(
                                targetRestSec = it.trim().toDoubleOrNull()?.let { m -> (m * 60).toInt() },
                            ),
                        )
                    },
                    label = { Text("Rest (min)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

/** Column header above an exercise's sets — mirrors the workout's SetHeaderRow. */
@Composable
private fun TemplateHeaderRow(type: ExerciseType) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TplHeaderCell("SET", Modifier.width(TPL_COL_NUM))
        TplHeaderCell("PREV", Modifier.weight(1f))
        when (type) {
            ExerciseType.WEIGHTED -> {
                TplHeaderCell("LB", Modifier.weight(1f))
                TplHeaderCell("REPS", Modifier.weight(1f))
            }
            ExerciseType.BODYWEIGHT -> TplHeaderCell("REPS", Modifier.weight(1f))
            ExerciseType.TIMED -> TplHeaderCell("TIME", Modifier.weight(1f))
            ExerciseType.DISTANCE -> TplHeaderCell("M", Modifier.weight(1f))
            ExerciseType.CARDIO -> {
                TplHeaderCell("MI", Modifier.weight(1f))
                TplHeaderCell("TIME", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TplHeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** One target set row — SET number, gray PREVIOUS, and editable value field(s) per type.
 *  Mirrors the workout's SetRow minus the check / plate-calc / warm-up controls. */
@Composable
private fun TemplateSetRow(
    index: Int,
    type: ExerciseType,
    set: TemplateSetValue,
    previous: String?,
    onChange: ((TemplateSetValue) -> TemplateSetValue) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IronlogColors.SurfaceVariant)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(TPL_COL_NUM), contentAlignment = Alignment.Center) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            previous ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (type) {
            ExerciseType.WEIGHTED -> {
                TplField(fmtNum(set.weightLb), "lb", Modifier.weight(1f)) {
                    onChange { s -> s.copy(weightLb = it.toDoubleTrimmedTpl()) }
                }
                TplField(fmtInt(set.reps), "reps", Modifier.weight(1f)) {
                    onChange { s -> s.copy(reps = it.toIntTrimmedTpl()) }
                }
            }
            ExerciseType.BODYWEIGHT -> TplField(fmtInt(set.reps), "reps", Modifier.weight(1f)) {
                onChange { s -> s.copy(reps = it.toIntTrimmedTpl()) }
            }
            ExerciseType.TIMED -> TplField(
                value = CardioFormat.formatClock(set.seconds).takeIf {
                    (set.seconds ?: 0) >= CardioFormat.CLOCK_THRESHOLD_SECONDS
                } ?: fmtInt(set.seconds),
                placeholder = "m:ss",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Text,
            ) {
                onChange { s -> s.copy(seconds = CardioFormat.parseClockToSeconds(it, bareIsMinutes = false)) }
            }
            ExerciseType.DISTANCE -> TplField(fmtNum(set.distanceMeters), "m", Modifier.weight(1f)) {
                onChange { s -> s.copy(distanceMeters = it.toDoubleTrimmedTpl()) }
            }
            ExerciseType.CARDIO -> {
                TplField(
                    value = CardioFormat.formatMiles(set.distanceMeters),
                    placeholder = "mi",
                    modifier = Modifier.weight(1f),
                ) {
                    onChange { s -> s.copy(distanceMeters = CardioFormat.parseMilesToMeters(it)) }
                }
                TplField(
                    value = CardioFormat.formatClock(set.seconds),
                    placeholder = "m:ss",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Text,
                ) {
                    onChange { s -> s.copy(seconds = CardioFormat.parseClockToSeconds(it, bareIsMinutes = true)) }
                }
            }
        }
    }
}

/** Compact centered numeric input — same focus-clear / restore behavior as the workout grid. */
@Composable
private fun TplField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!isFocused) text = value
    }

    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(46.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (state.isFocused && !isFocused) {
                        isFocused = true
                        text = ""
                    } else if (!state.isFocused && isFocused) {
                        isFocused = false
                        if (text.isEmpty()) text = value
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        Text(
                            if (isFocused) value else placeholder,
                            color = if (isFocused)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

private fun fmtNum(v: Double?): String =
    v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""

private fun fmtInt(v: Int?): String = v?.toString() ?: ""

private fun String.toDoubleTrimmedTpl(): Double? = trim().toDoubleOrNull()

private fun String.toIntTrimmedTpl(): Int? = trim().toDoubleOrNull()?.toInt()

@Composable
private fun TemplateExercisePicker(
    available: List<Exercise>,
    onPick: (Exercise) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, available) {
        if (query.isBlank()) available
        else available.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add exercise", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
        ) {
            itemsIndexed(filtered) { _, ex ->
                Text(
                    text = ex.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(ex) }
                        .padding(vertical = 12.dp),
                )
                HorizontalDivider()
            }
        }
    }
}
