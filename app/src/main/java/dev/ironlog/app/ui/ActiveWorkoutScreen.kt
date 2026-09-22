package dev.ironlog.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ironlog.app.data.Exercise
import dev.ironlog.app.data.ExerciseType
import dev.ironlog.app.data.importer.WeightConversion
import dev.ironlog.app.gym.MuscleAssistant
import dev.ironlog.app.metrics.CardioFormat
import dev.ironlog.app.metrics.PlateCalcConfig
import dev.ironlog.app.metrics.PlateMath
import dev.ironlog.app.session.DraftExercise
import dev.ironlog.app.session.DraftSet
import dev.ironlog.app.timer.RestTimerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    vm: IronlogViewModel,
    onLeave: () -> Unit,
    onOpenExerciseDetail: (Long) -> Unit = {},
) {
    val draft by vm.draft.collectAsState()
    val exercises by vm.exercises.collectAsState()
    val rest by vm.rest.collectAsState()
    val elapsedSec by vm.elapsedSec.collectAsState()
    // Settings that drive active-workout behavior (each previously declared but unconsumed).
    val soundEnabled by vm.soundEnabled.collectAsState()
    val defaultRestSec by vm.defaultRestSec.collectAsState()
    val defaultBarLb by vm.defaultBarLb.collectAsState()
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showWorkoutNoteDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.restCue.collect { cue ->
            when (cue) {
                // Mario-Kart escalating cadence: buzz grows longer as countdown approaches zero.
                is RestCue.Countdown -> buzz(context, (4 - cue.secondsLeft) * 80L + 80L)
                is RestCue.Done -> { if (soundEnabled) ding(context); buzz(context, 350) }
            }
        }
    }

    val d = draft
    if (d == null) {
        LaunchedEffect(Unit) { onLeave() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Leaves the workout running (nothing is discarded) — resume it from the Workout tab.
                    TextButton(onClick = onLeave) { Text("‹ Back") }
                },
                title = {
                    Column {
                        Text(
                            d.name.ifBlank { "Workout" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Elapsed: ${clock(elapsedSec)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showWorkoutNoteDialog = true }) { Text("Note") }
                    TextButton(onClick = { showDiscardDialog = true }) { Text("Discard") }
                    TextButton(onClick = { showFinishDialog = true }) { Text("Finish") }
                },
            )
        },
        bottomBar = {
            val r = rest
            if (r != null) {
                RestBar(
                    remainingMs = r.remainingMs,
                    totalMs = r.totalMs,
                    onMinus30 = { vm.minusRest(30) },
                    onSkip = { vm.stopRest() },
                    onAdd30 = { vm.addRest(30) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = vm::setName,
                    label = { Text("Workout name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (d.notes != null) {
                item {
                    Text(
                        "Note: ${d.notes}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }
            if (d.exercises.isEmpty()) {
                item { Text("No exercises yet. Tap \"Add exercise\" to begin.") }
            }
            itemsIndexed(d.exercises) { i, ex ->
                ExerciseCard(
                    exerciseIndex = i,
                    exercise = ex,
                    totalExercises = d.exercises.size,
                    onAddSet = { vm.addSet(i) },
                    onSkip = { vm.removeExercise(i) },
                    onSetChange = { j, transform -> vm.updateSet(i, j, transform) },
                    onRemoveSetById = { setId -> vm.removeSetById(i, setId) },
                    onMarkWarmup = { j, on -> vm.markWarmup(i, j, on) },
                    onSetCompleted = {
                        if (soundEnabled) playSetComplete(context)
                        vm.startRest(ex.restSeconds ?: defaultRestSec)
                    },
                    onRestChange = { seconds -> vm.setExerciseRest(i, seconds) },
                    onReplace = { newEx -> vm.replaceExercise(i, newEx) },
                    onSwapAlternatives = { src -> vm.swapAlternatives(src) },
                    onMoveUp = { if (i > 0) vm.reorderExercise(i, i - 1) },
                    onMoveDown = { if (i < d.exercises.size - 1) vm.reorderExercise(i, i + 1) },
                    onSetExerciseNote = { note -> vm.setExerciseNote(i, note) },
                    onSetStickyNote = { note -> vm.setStickyNote(i, note) },
                    onNormalizeFromSet = { j -> vm.normalizeFromSet(i, j) },
                    onAddNextTimeNote = { note -> vm.addNextTimeNote(note) },
                    exercises = exercises,
                    calculatePlates = { target, barLb -> vm.calculatePlates(target, barLb) },
                    defaultBarLb = defaultBarLb,
                    onSetPlateCalcConfig = { mode, barWeightLb ->
                        vm.setExercisePlateCalcConfig(ex.exerciseId, mode, barWeightLb)
                    },
                    onSetExerciseType = { type -> vm.setExerciseType(ex.exerciseId, type) },
                    onOpenDetail = { onOpenExerciseDetail(ex.exerciseId) },
                    onSupersetWithNext = { vm.supersetWithNext(i) },
                    onRemoveFromSuperset = { vm.removeFromSuperset(i) },
                    onAddWarmupRamp = {
                        val isBarbellEx = exercises.firstOrNull { it.id == ex.exerciseId }
                            ?.equipment.equals("barbell", ignoreCase = true) ||
                            ex.name.contains("barbell", ignoreCase = true)
                        vm.addWarmupRamp(i, isBarbellEx)
                    },
                )
            }
            // Inline footer instead of a FAB — a floating button covered the last exercise's rows.
            item {
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ Add exercise") }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ExercisePickerContent(
                exercises = exercises,
                onPick = { vm.addExercise(it); showPicker = false },
                onCreate = { name, type -> vm.createExerciseAndAdd(name, type); showPicker = false },
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard workout?") },
            text = { Text("Nothing will be saved.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    vm.discard()
                    onLeave()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep logging") }
            },
        )
    }

    if (showWorkoutNoteDialog) {
        var noteText by remember { mutableStateOf(d.notes ?: "") }
        AlertDialog(
            onDismissRequest = { showWorkoutNoteDialog = false },
            title = { Text("Workout note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setWorkoutNote(noteText)
                    showWorkoutNoteDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showWorkoutNoteDialog = false }) { Text("Cancel") } },
        )
    }

    if (showFinishDialog) {
        val unfinished = d.unfinishedExerciseNames()
        val pendingNotes = d.pendingTemplateNotes
        // Template updates are OPT-IN and deliberately small/off-by-default — accidental
        // template edits were the previous tracker's worst habit and the reason this app exists.
        var saveTemplateValues by remember { mutableStateOf(false) }
        var saveTemplateStructure by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("Complete workout?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (unfinished.isNotEmpty()) {
                        Text("Unchecked sets:")
                        unfinished.forEach { name -> Text("• $name") }
                    } else {
                        Text("All sets are checked off.")
                    }
                    if (pendingNotes.isNotEmpty()) {
                        Text("Next session reminders:", style = MaterialTheme.typography.labelMedium)
                        pendingNotes.forEach { note -> Text("• $note", style = MaterialTheme.typography.bodySmall) }
                    }
                    // #21 (use-testing 2026-07-20): the old two checkboxes read as jargon and never
                    // said where each change actually landed. Spell out every scope in plain words:
                    // the workout always saves to history; rest-timer/plate tweaks already saved to
                    // the exercise; only the template checkboxes below change the named routine, and
                    // both stay OFF unless ticked (accidental template edits were the previous tracker's worst habit).
                    HorizontalDivider()
                    Text(
                        "This workout is always saved to your history.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Rest-timer and plate changes you made are already saved to each exercise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (d.templateId != null) {
                        val templateLabel = d.name.ifBlank { "this template" }
                        Text(
                            "Also update the “$templateLabel” template? (off by default)",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { saveTemplateValues = !saveTemplateValues },
                        ) {
                            Checkbox(
                                checked = saveTemplateValues,
                                onCheckedChange = { saveTemplateValues = it },
                            )
                            Text(
                                "Match its weights & reps to today's numbers",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { saveTemplateStructure = !saveTemplateStructure },
                        ) {
                            Checkbox(
                                checked = saveTemplateStructure,
                                onCheckedChange = { saveTemplateStructure = it },
                            )
                            Text(
                                "Match its exercises & set count to today's",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                // Big primary action; navigation is immediate — persistence runs in background.
                Button(onClick = {
                    showFinishDialog = false
                    vm.finish(
                        updateTemplateValues = saveTemplateValues,
                        updateTemplateStructure = saveTemplateStructure,
                    )
                    onLeave()
                }) { Text("Complete workout") }
            },
            dismissButton = { TextButton(onClick = { showFinishDialog = false }) { Text("Keep logging") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseCard(
    exerciseIndex: Int,
    exercise: DraftExercise,
    totalExercises: Int,
    onAddSet: () -> Unit,
    onSkip: () -> Unit,
    onSetChange: (Int, (DraftSet) -> DraftSet) -> Unit,
    onRemoveSetById: (Long) -> Unit,
    onMarkWarmup: (Int, Boolean) -> Unit,
    onSetCompleted: () -> Unit,
    onRestChange: (Int) -> Unit,
    onReplace: (Exercise) -> Unit,
    onSwapAlternatives: suspend (Exercise) -> List<MuscleAssistant.ScoredAlternative>,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetExerciseNote: (String) -> Unit,
    onSetStickyNote: (String) -> Unit,
    onNormalizeFromSet: (Int) -> Unit,
    onAddNextTimeNote: (String) -> Unit,
    exercises: List<Exercise>,
    calculatePlates: (Double, Double) -> PlateMath.PlateResult,
    defaultBarLb: Double,
    onSetPlateCalcConfig: (mode: String?, barWeightLb: Double?) -> Unit = { _, _ -> },
    onSetExerciseType: (ExerciseType) -> Unit = {},
    onOpenDetail: () -> Unit = {},
    onSupersetWithNext: () -> Unit = {},
    onRemoveFromSuperset: () -> Unit = {},
    onAddWarmupRamp: () -> Unit = {},
) {
    var showRestPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showReplacePicker by remember { mutableStateOf(false) }
    var showSwapSheet by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showStickyNoteDialog by remember { mutableStateOf(false) }
    var showPlateCalcConfig by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }
    var plateTap by remember { mutableStateOf<Double?>(null) }

    val restSeconds = exercise.restSeconds ?: IronlogViewModel.DEFAULT_REST_SECONDS

    // F2: the plate calculator is shown by explicit per-exercise override (plateCalcMode)
    // when set, else auto-detected: barbell equipment/name → calculator, everything else
    // (machines, cables, dumbbells) → a lb→kg readout (machine stacks are often labeled in kg,
    // owner note, 2026-07-05). The base/bar weight is likewise the exercise's own override
    // (including 0.0 = "no bar") or the global Settings default.
    val catalogExercise = remember(exercise.exerciseId, exercises) {
        exercises.firstOrNull { it.id == exercise.exerciseId }
    }
    val showPlateCalc = remember(catalogExercise, exercise.name) {
        PlateCalcConfig.shouldShow(catalogExercise?.plateCalcMode, catalogExercise?.equipment, exercise.name)
    }
    val effectiveBarLb = remember(catalogExercise, defaultBarLb) {
        PlateCalcConfig.effectiveBarWeight(catalogExercise?.barWeightLb, defaultBarLb)
    }

    // Target hint string (shown under exercise name when template has targets)
    val targetHint = if (exercise.targetRepsLow != null && exercise.targetRepsHigh != null) {
        val setsStr = exercise.targetSets?.let { "${it}×" } ?: ""
        "${setsStr}${exercise.targetRepsLow}–${exercise.targetRepsHigh} reps"
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = IronlogColors.SurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Tap the name to open the exercise's About/History/Charts/Records detail.
                    Text(
                        exercise.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenDetail() },
                    )
                    Text(exercise.type.label(), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    if (exercise.supersetGroup != null) {
                        Text(
                            "Superset ${exercise.supersetGroup}",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = IronlogColors.Blue,
                        )
                    }
                    if (targetHint != null) {
                        Text(
                            "Target: $targetHint",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!exercise.coachHint.isNullOrBlank()) {
                        Text(
                            "Coach: ${exercise.coachHint}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (!exercise.stickyNote.isNullOrBlank()) {
                        Text(
                            "★ ${exercise.stickyNote}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (!exercise.note.isNullOrBlank()) {
                        Text(
                            "Note: ${exercise.note}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Box {
                    TextButton(onClick = { showMenu = true }) { Text("···") }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Replace exercise") },
                            onClick = { showMenu = false; showReplacePicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Swap — station busy") },
                            onClick = { showMenu = false; showSwapSheet = true },
                        )
                        if (exerciseIndex > 0) {
                            DropdownMenuItem(
                                text = { Text("Move up") },
                                onClick = { showMenu = false; onMoveUp() },
                            )
                        }
                        if (exerciseIndex < totalExercises - 1) {
                            DropdownMenuItem(
                                text = { Text("Move down") },
                                onClick = { showMenu = false; onMoveDown() },
                            )
                        }
                        if (exerciseIndex < totalExercises - 1) {
                            DropdownMenuItem(
                                text = { Text("Superset with next") },
                                onClick = { showMenu = false; onSupersetWithNext() },
                            )
                        }
                        if (exercise.supersetGroup != null) {
                            DropdownMenuItem(
                                text = { Text("Remove from superset") },
                                onClick = { showMenu = false; onRemoveFromSuperset() },
                            )
                        }
                        if (exercise.type == ExerciseType.WEIGHTED) {
                            DropdownMenuItem(
                                text = { Text("Add warm-up sets") },
                                onClick = { showMenu = false; onAddWarmupRamp() },
                            )
                            DropdownMenuItem(
                                text = { Text("Plate calculator…") },
                                onClick = { showMenu = false; showPlateCalcConfig = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Logs as…") },
                            onClick = { showMenu = false; showTypePicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Rest timer…") },
                            onClick = { showMenu = false; showRestPicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Add note") },
                            onClick = { showMenu = false; showNoteDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Sticky note") },
                            onClick = { showMenu = false; showStickyNoteDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Skip exercise") },
                            onClick = { showMenu = false; onSkip() },
                        )
                    }
                }
            }

            SetHeaderRow(exercise.type)
            exercise.sets.forEachIndexed { j, set ->
                key(set.id) {
                    // Swipe a set left to delete it (space saver; no per-row ✕).
                    // #20 (use-testing 2026-07-20): the default commit is 50% of the row width, so
                    // a little horizontal drift during a vertical scroll could delete a set. Require
                    // a longer, deliberate drag (SWIPE_DELETE_COMMIT_FRACTION of the row) to commit.
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                onRemoveSetById(set.id)
                                true
                            } else {
                                false
                            }
                        },
                        positionalThreshold = { totalDistance -> totalDistance * SWIPE_DELETE_COMMIT_FRACTION },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = { SwipeDeleteBackground() },
                    ) {
                        SetRow(
                            index = j,
                            type = exercise.type,
                            set = set,
                            previous = exercise.previous.getOrNull(j),
                            onChange = { transform -> onSetChange(j, transform) },
                            onCompleted = onSetCompleted,
                            onWarmupToggle = { on -> onMarkWarmup(j, on) },
                            onTapWeight = { weightLb -> plateTap = weightLb },
                            isBarbell = showPlateCalc,
                            onNormalize = { onNormalizeFromSet(j) },
                            onAddSet = onAddSet,
                            onAddSetNextTime = {
                                val note = "${exercise.name}: bump to ${exercise.sets.size + 1} sets next session"
                                onAddNextTimeNote(note)
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // "+ Set" copies the last row's values (S3 behavior), so the old "Repeat last"
                // button was byte-for-byte identical and was removed (F7, 2026-07-22).
                OutlinedButton(onClick = onAddSet) { Text("+ Set") }
                OutlinedButton(onClick = { showRestPicker = true }) { Text("Rest ${RestTimerConfig.label(restSeconds, ::clock)}") }
            }
        }
    }

    if (showRestPicker) {
        RestPickerDialog(
            current = restSeconds,
            onPick = { seconds -> onRestChange(seconds); showRestPicker = false },
            onDismiss = { showRestPicker = false },
        )
    }

    if (showReplacePicker) {
        ModalBottomSheet(
            onDismissRequest = { showReplacePicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ExercisePickerContent(
                exercises = exercises,
                title = "Replace \"${exercise.name}\" with:",
                onPick = { onReplace(it); showReplacePicker = false },
                onCreate = { _, _ -> showReplacePicker = false }, // replace doesn't create
            )
        }
    }

    if (showSwapSheet) {
        val source = remember(exercise.exerciseId) { exercises.firstOrNull { it.id == exercise.exerciseId } }
        var alternatives by remember { mutableStateOf<List<MuscleAssistant.ScoredAlternative>?>(null) }
        LaunchedEffect(source) {
            // Only the best handful are useful for a busy-station swap.
            alternatives = source?.let { onSwapAlternatives(it).take(8) } ?: emptyList()
        }
        ModalBottomSheet(
            onDismissRequest = { showSwapSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Swap \"${exercise.name}\"", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Same muscle, different equipment — filtered to your active gym. Logged sets carry over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val alts = alternatives
                when {
                    alts == null -> Text("Finding alternatives…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    alts.isEmpty() -> Text(
                        "No alternatives at this gym. Turn on more equipment in My Gym.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(alts) { _, alt ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onReplace(alt.exercise); showSwapSheet = false }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(alt.exercise.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(alt.exercise.equipment, alt.exercise.mechanic).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        var noteText by remember { mutableStateOf(exercise.note ?: "") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Note for ${exercise.name}") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note (this session)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onSetExerciseNote(noteText); showNoteDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") } },
        )
    }

    if (showStickyNoteDialog) {
        var noteText by remember { mutableStateOf(exercise.stickyNote ?: "") }
        AlertDialog(
            onDismissRequest = { showStickyNoteDialog = false },
            title = { Text("Sticky note for ${exercise.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Shown every session (cues, form notes, etc.)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Sticky note") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSetStickyNote(noteText); showStickyNoteDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showStickyNoteDialog = false }) { Text("Cancel") } },
        )
    }

    if (showTypePicker) {
        ExerciseTypeDialog(
            exerciseName = exercise.name,
            current = exercise.type,
            onPick = { picked ->
                onSetExerciseType(picked)
                showTypePicker = false
            },
            onDismiss = { showTypePicker = false },
        )
    }

    if (showPlateCalcConfig) {
        PlateCalcConfigDialog(
            currentMode = catalogExercise?.plateCalcMode,
            currentBarWeightLb = catalogExercise?.barWeightLb,
            defaultBarLb = defaultBarLb,
            onSave = { mode, barWeightLb ->
                onSetPlateCalcConfig(mode, barWeightLb)
                showPlateCalcConfig = false
            },
            onDismiss = { showPlateCalcConfig = false },
        )
    }

    val tappedWeight = plateTap
    if (tappedWeight != null) {
        if (showPlateCalc) {
            val result = remember(tappedWeight) { calculatePlates(tappedWeight, effectiveBarLb) }
            PlateCalcSheet(
                targetLb = tappedWeight,
                barLb = effectiveBarLb,
                result = result,
                onDismiss = { plateTap = null },
            )
        } else {
            KgSheet(targetLb = tappedWeight, onDismiss = { plateTap = null })
        }
    }
}

/** lb→kg readout for non-barbell lifts (machine/cable stacks are often labeled in kg). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KgSheet(
    targetLb: Double,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Kilograms",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(
                "${fmtNum(targetLb)} lb = ${"%.1f".format(WeightConversion.lbToKg(targetLb))} kg",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Machine and cable stacks are often labeled in kg.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlateCalcSheet(
    targetLb: Double,
    barLb: Double,
    result: PlateMath.PlateResult,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Plate calculator — ${fmtNum(targetLb)} lb",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text("Bar: ${fmtNum(barLb)} lb")
            if (result.platesPerSide.isEmpty()) {
                Text("Bar only — no plates needed.")
            } else {
                Text("Per side: ${result.platesPerSide.joinToString(" + ") { fmtNum(it) }} lb")
                val achieved = result.achievedLb
                val delta = result.deltaLb
                val suffix = when {
                    delta == 0.0 -> "exact"
                    delta > 0 -> "+${fmtNum(delta)} lb over"
                    else -> "${fmtNum(delta)} lb under"
                }
                Text("Total: ${fmtNum(achieved)} lb ($suffix)")
            }
        }
    }
}

/** F2: per-exercise plate-calc override — Auto/On/Off + base/bar weight (lb, with a live kg readout). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlateCalcConfigDialog(
    currentMode: String?,
    currentBarWeightLb: Double?,
    defaultBarLb: Double,
    onSave: (mode: String?, barWeightLb: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(currentMode) }
    var lbText by remember {
        mutableStateOf(currentBarWeightLb?.let { fmtNum(it) } ?: "")
    }
    val kgText = lbText.toDoubleOrNull()
        ?.let { "%.1f".format(WeightConversion.lbToKg(it)) } ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plate calculator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Off by default for machines/cables; on by default for barbells. " +
                        "Force it either way here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null, PlateCalcConfig.MODE_ON, PlateCalcConfig.MODE_OFF).forEach { m ->
                        val label = when (m) {
                            null -> "Auto"
                            PlateCalcConfig.MODE_ON -> "On"
                            else -> "Off"
                        }
                        androidx.compose.material3.FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "Base weight (bar for a barbell; the fixed starting weight for a machine — " +
                        "e.g. a plate-loaded machine with a 95 kg base):",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = lbText,
                        onValueChange = { lbText = it },
                        label = { Text("lb") },
                        placeholder = { Text("default: ${fmtNum(defaultBarLb)}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = kgText,
                        onValueChange = { kg ->
                            lbText = kg.toDoubleOrNull()
                                ?.let { fmtNum(WeightConversion.kgToLbRaw(it)) } ?: ""
                        },
                        label = { Text("kg") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Blank = use the Settings default bar weight.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(mode, lbText.trim().toDoubleOrNull())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestPickerDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rest timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick a duration (remembered for this exercise), or turn it off entirely.")
                OutlinedButton(
                    onClick = { onPick(RestTimerConfig.OFF_SECONDS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("No rest timer") }
                listOf(60, 90, 120, 180, 240, 300).forEach { seconds ->
                    OutlinedButton(
                        onClick = { onPick(seconds) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(clock(seconds)) }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mins = custom.trim().toDoubleOrNull()
                if (mins != null && mins > 0) onPick((mins * 60).toInt()) else onDismiss()
            }) { Text("Set custom") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Set-row column widths -- shared by SetHeaderRow and SetRow so headers line up with cells.
// PREV uses weight(1f) so it gets proportional space and never clips wide values (e.g. "225 lb × 15").
private val COL_NUM = 32.dp     // set number / warm-up toggle
private val COL_CALC = 34.dp    // plate-calculator button (weighted only)
private val COL_DONE = 44.dp    // checkbox

/** One-time column header above an exercise's sets (keeps units visible after typing). */
@Composable
internal fun SetHeaderRow(type: ExerciseType) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("SET", Modifier.width(COL_NUM))
        HeaderCell("PREV", Modifier.weight(1f))
        when (type) {
            ExerciseType.WEIGHTED -> {
                HeaderCell("LB", Modifier.weight(1f))
                HeaderCell("REPS", Modifier.weight(1f))
                Spacer(Modifier.width(COL_CALC))
            }
            ExerciseType.BODYWEIGHT -> HeaderCell("REPS", Modifier.weight(1f))
            ExerciseType.TIMED -> HeaderCell("TIME", Modifier.weight(1f))
            ExerciseType.DISTANCE -> HeaderCell("M", Modifier.weight(1f))
            ExerciseType.CARDIO -> {
                HeaderCell("MI", Modifier.weight(1f))
                HeaderCell("TIME", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.width(COL_DONE))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SetRow(
    index: Int,
    type: ExerciseType,
    set: DraftSet,
    previous: String?,
    onChange: ((DraftSet) -> DraftSet) -> Unit,
    onCompleted: () -> Unit,
    onWarmupToggle: (Boolean) -> Unit,
    onTapWeight: (Double) -> Unit,
    onNormalize: () -> Unit,
    onAddSet: () -> Unit,
    onAddSetNextTime: () -> Unit,
    /** Barbell rows show the plate-calc glyph; others a kg-readout glyph (same tap target). */
    isBarbell: Boolean = true,
) {
    var showSetMenu by remember { mutableStateOf(false) }
    // The row foreground MUST be opaque -- it sits on top of the swipe-to-delete red background,
    // which should only be visible while actively swiping. A translucent tint layers on top of the
    // opaque card color for warm-up / completed rows.
    val tint = when {
        set.isWarmup -> IronlogColors.Blue.copy(alpha = 0.12f)
        set.done -> IronlogColors.Green.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IronlogColors.SurfaceVariant)
            .background(tint)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Set number; tap = warm-up toggle, long-press = set actions menu.
        Box(
            modifier = Modifier.width(COL_NUM),
            contentAlignment = Alignment.Center,
        ) {
            // Tag letter: F = to failure, D = drop set (the familiar set tags).
            val tagSuffix = when (set.setTag) {
                "failure" -> "F"
                "drop" -> "D"
                else -> ""
            }
            Text(
                (if (set.isWarmup) "W" else "${index + 1}") + tagSuffix,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    set.setTag == "failure" -> MaterialTheme.colorScheme.error
                    set.isWarmup -> IronlogColors.Blue
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onWarmupToggle(!set.isWarmup) },
                    onLongClick = { showSetMenu = true },
                ),
            )
            DropdownMenu(expanded = showSetMenu, onDismissRequest = { showSetMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Add a set") },
                    onClick = { showSetMenu = false; onAddSet() },
                )
                DropdownMenuItem(
                    text = { Text("Normalize remaining sets") },
                    onClick = { showSetMenu = false; onNormalize() },
                )
                DropdownMenuItem(
                    text = { Text("Increase sets next session") },
                    onClick = { showSetMenu = false; onAddSetNextTime() },
                )
                DropdownMenuItem(
                    text = { Text(if (set.setTag == "failure") "Untag: failure" else "Tag: to failure") },
                    onClick = {
                        showSetMenu = false
                        onChange { s -> s.copy(setTag = if (s.setTag == "failure") null else "failure") }
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (set.setTag == "drop") "Untag: drop set" else "Tag: drop set") },
                    onClick = {
                        showSetMenu = false
                        onChange { s -> s.copy(setTag = if (s.setTag == "drop") null else "drop") }
                    },
                )
            }
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
                CompactField(fmtNum(set.weightLb), "lb", Modifier.weight(1f)) {
                    onChange { s -> s.copy(weightLb = it.toDoubleTrimmed()) }
                }
                CompactField(fmtInt(set.reps), "reps", Modifier.weight(1f)) {
                    onChange { s -> s.copy(reps = it.toIntTrimmed()) }
                }
                // Barbell: plate calculator. Non-barbell: lb→kg readout (stacks labeled in kg).
                // Only meaningful once a weight is entered.
                Box(
                    modifier = Modifier
                        .width(COL_CALC)
                        .clickable(enabled = set.weightLb != null) {
                            set.weightLb?.let { onTapWeight(it) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isBarbell) "▦" else "kg",
                        style = if (isBarbell) LocalTextStyle.current else MaterialTheme.typography.labelSmall,
                        color = if (set.weightLb != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                }
            }
            ExerciseType.BODYWEIGHT -> CompactField(fmtInt(set.reps), "reps", Modifier.weight(1f)) {
                onChange { s -> s.copy(reps = it.toIntTrimmed()) }
            }
            // A hold is entered in seconds ("30") but shown as a clock past a minute ("1:30").
            ExerciseType.TIMED -> CompactField(
                value = CardioFormat.formatClock(set.seconds).takeIf {
                    (set.seconds ?: 0) >= CardioFormat.CLOCK_THRESHOLD_SECONDS
                } ?: fmtInt(set.seconds),
                placeholder = "m:ss",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Text,
            ) {
                onChange { s -> s.copy(seconds = CardioFormat.parseClockToSeconds(it, bareIsMinutes = false)) }
            }
            ExerciseType.DISTANCE -> CompactField(fmtNum(set.distanceMeters), "m", Modifier.weight(1f)) {
                onChange { s -> s.copy(distanceMeters = it.toDoubleTrimmed()) }
            }
            // Cardio keeps BOTH halves of the set: miles and elapsed time. Miles are the unit
            // the log is kept in; meters are what the database stores.
            ExerciseType.CARDIO -> {
                CompactField(
                    value = CardioFormat.formatMiles(set.distanceMeters),
                    placeholder = "mi",
                    modifier = Modifier.weight(1f),
                ) {
                    onChange { s -> s.copy(distanceMeters = CardioFormat.parseMilesToMeters(it)) }
                }
                CompactField(
                    value = CardioFormat.formatClock(set.seconds),
                    placeholder = "m:ss",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Text,
                ) {
                    onChange { s -> s.copy(seconds = CardioFormat.parseClockToSeconds(it, bareIsMinutes = true)) }
                }
            }
        }
        Box(modifier = Modifier.width(COL_DONE), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = set.done,
                onCheckedChange = { chk ->
                    onChange { s -> s.copy(done = chk) }
                    if (chk) onCompleted()
                },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = IronlogColors.Green),
            )
        }
    }
}

/** Red background revealed when swiping a set left to delete it. */
@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IronlogColors.Red)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text("🗑", fontSize = 20.sp, color = Color.White)
    }
}

/** Compact, centered numeric input.
 *  Focus behavior: tapping clears the field so the user types fresh; if the user tabs/unfocuses
 *  without typing, the original value is restored (so checking the set still records the prefill). */
/**
 * "Logs as" -- which columns an exercise shows.  Changing it never deletes a thing: the set
 * rows keep every value they had, and the columns for the new type simply take over.
 */
@Composable
internal fun ExerciseTypeDialog(
    exerciseName: String,
    current: ExerciseType,
    onPick: (ExerciseType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How $exerciseName is logged") },
        text = {
            Column {
                Text(
                    "Pick the columns you want for this exercise. Nothing you have already "
                        + "logged is changed or deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExerciseType.entries.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(type) },
                    ) {
                        RadioButton(selected = type == current, onClick = { onPick(type) })
                        Text(type.label())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CompactField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    // Time fields need a colon, which the number pad has no key for -- they ask for the full
    // keyboard instead. Everything else stays on the number pad.
    keyboardType: KeyboardType = KeyboardType.Number,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }

    // Sync external value changes only when not focused (don't overwrite in-progress edits).
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
                        text = ""  // clear; original value shows as dim hint below
                    } else if (!state.isFocused && isFocused) {
                        isFocused = false
                        if (text.isEmpty()) text = value  // restore if nothing typed
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        // While focused: dim the original value as a ghost hint.
                        // While unfocused: show the unit placeholder.
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

// #20: fraction of a set row that must be swiped left before a delete commits. The Material3
// default is 0.5f; raising it makes an accidental scroll-drift delete much harder while a real,
// deliberate swipe still deletes.
private const val SWIPE_DELETE_COMMIT_FRACTION = 0.72f

@Composable
internal fun RestBar(
    remainingMs: Long,
    totalMs: Long,
    onMinus30: () -> Unit,
    onSkip: () -> Unit,
    onAdd30: () -> Unit,
) {
    val fraction = if (totalMs > 0) (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val seconds = ((remainingMs + 999) / 1000).toInt()
    // #22 (use-testing 2026-07-20): tonalElevation lit the bar a shade lighter than the app
    // background, and that color step read as a thin bevel/divider along the bar's top edge.
    // Paint the bar with the SAME Background color (no elevation) so there is no seam — the
    // draining blue fill is the bar's only visual boundary. navigationBarsPadding stays OUTSIDE
    // the Surface so the gesture-nav inset below keeps the app background (the fixed-inset fix).
    Surface(
        color = IronlogColors.Background,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(IronlogColors.Blue.copy(alpha = 0.30f)),
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onMinus30) { Text("−30s") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        clock(seconds),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onSkip, modifier = Modifier.height(28.dp)) {
                        Text("Skip", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onAdd30) { Text("+30s") }
            }
        }
    }
}

private fun playSetComplete(context: android.content.Context) {
    ding(context)
    buzz(context, 70)
}

private fun ding(context: android.content.Context) {
    runCatching {
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
        tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
    }
}

private fun buzz(context: android.content.Context, ms: Long) {
    runCatching {
        val vibrator = vibrator(context)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val amplitude =
                if (vibrator.hasAmplitudeControl()) 255 else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, amplitude))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(ms)
        }
    }
}

private fun vibrator(context: android.content.Context): android.os.Vibrator =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.os.Vibrator::class.java)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePickerContent(
    exercises: List<Exercise>,
    title: String = "Add exercise",
    onPick: (Exercise) -> Unit,
    onCreate: (String, ExerciseType) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Blank search = your own exercises only (not the full 873-row catalog, which buries them).
    // As soon as you type, search the whole catalog so any exercise is reachable.
    val filtered = remember(query, exercises) {
        if (query.isBlank()) exercises.filter { !it.catalogOnly }
        else exercises.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val showCreate = query.isNotBlank() &&
        filtered.none { it.name.equals(query.trim(), ignoreCase = true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search or name a new exercise") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // "Create …" pinned at the TOP of the results as you type — not buried below the list.
        if (showCreate) {
            Text("Create \"${query.trim()}\" as:")
            // Five types no longer fit one row at phone width -- wrap at three.
            ExerciseType.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { type ->
                        OutlinedButton(
                            onClick = { onCreate(query.trim(), type) },
                            modifier = Modifier.weight(1f),
                        ) { Text(type.shortLabel()) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            HorizontalDivider()
        }
        if (query.isBlank() && filtered.isEmpty()) {
            Text(
                "No exercises yet — type a name to search the catalog or create one.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
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

internal fun ExerciseType.label(): String = when (this) {
    ExerciseType.WEIGHTED -> "Weight × reps"
    ExerciseType.BODYWEIGHT -> "Reps only"
    ExerciseType.TIMED -> "Time"
    ExerciseType.DISTANCE -> "Distance"
    ExerciseType.CARDIO -> "Distance + time"
}

private fun ExerciseType.shortLabel(): String = when (this) {
    ExerciseType.WEIGHTED -> "Weight"
    ExerciseType.BODYWEIGHT -> "Reps"
    ExerciseType.TIMED -> "Time"
    ExerciseType.DISTANCE -> "Dist"
    ExerciseType.CARDIO -> "Dist+time"
}

private fun fmtNum(v: Double?): String =
    v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""

private fun fmtInt(v: Int?): String = v?.toString() ?: ""

internal fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun String.toDoubleTrimmed(): Double? = trim().toDoubleOrNull()

private fun String.toIntTrimmed(): Int? = trim().toDoubleOrNull()?.toInt()
