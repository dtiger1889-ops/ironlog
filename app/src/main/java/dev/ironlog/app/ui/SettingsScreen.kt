package dev.ironlog.app.ui

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.ironlog.app.data.RestSoundPref
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: IronlogViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect settings
    val defaultBarLb by vm.defaultBarLb.collectAsState()
    val defaultRestSec by vm.defaultRestSec.collectAsState()
    val preventSleep by vm.preventSleep.collectAsState()
    val soundEnabled by vm.soundEnabled.collectAsState()
    val xrmFormula by vm.xrmFormula.collectAsState()
    val autoBackupEnabled by vm.autoBackupEnabled.collectAsState()
    val autoBackupUri by vm.autoBackupUri.collectAsState()
    val lastBackupStatus by vm.lastBackupStatus.collectAsState()
    val defaultGoalPreset by vm.defaultGoalPreset.collectAsState()
    val restSoundUri by vm.restSoundUri.collectAsState()

    // Local edit state for numeric fields
    var barLbText by remember(defaultBarLb) { mutableStateOf("%.1f".format(defaultBarLb)) }
    var restSecText by remember(defaultRestSec) { mutableStateOf(defaultRestSec.toString()) }

    // lb ↔ kg converter state
    var converterLb by remember { mutableStateOf("") }
    var converterKg by remember { mutableStateOf("") }

    // SAF backup picker — F9: picking a folder is also what actually arms the weekly worker;
    // previously nothing ever called a scheduler so the toggle did nothing.
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setAutoBackupUri(uri.toString())
            dev.ironlog.app.export.AutoBackupWorker.schedule(context)
        }
    }

    // F6: system ringtone picker for the rest-over alert sound, preselected to the current pick.
    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            vm.setRestSoundUri(RestSoundPref.fromPickerResult(picked?.toString()))
        }
    }

    // JSON export share
    var exportSnackbar by remember { mutableStateOf("") }
    val snackbarHost = remember { SnackbarHostState() }

    // Restore-from-backup: pick a JSON file → confirm → import (dedup-safe).
    var pendingRestoreText by remember { mutableStateOf<String?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                scope.launch { snackbarHost.showSnackbar("Couldn't read that file.") }
            } else {
                pendingRestoreText = text
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            // --- Backup & Export: FIRST section — user testing couldn't find it below the fold
            // (2026-07-05: "I don't actually see a button to export/backup"). ---
            item { SectionHeader("Backup & Export") }

            item {
                SwitchRow("Auto-backup (weekly)",
                    "Writes a JSON backup to a folder you pick — pick a Google Drive folder to back up to Drive",
                    autoBackupEnabled,
                ) { on ->
                    vm.setAutoBackupEnabled(on)
                    if (on) {
                        if (autoBackupUri.isNullOrBlank()) {
                            backupLauncher.launch(null)
                        } else {
                            dev.ironlog.app.export.AutoBackupWorker.schedule(context)
                        }
                    } else {
                        dev.ironlog.app.export.AutoBackupWorker.cancel(context)
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            vm.backupNow(context) { status ->
                                android.widget.Toast.makeText(context, status, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Back up now") }
                    OutlinedButton(
                        onClick = { backupLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (autoBackupUri.isNullOrBlank()) "Choose folder" else "Change folder") }
                }
            }
            if (!lastBackupStatus.isNullOrBlank()) {
                item {
                    Text(
                        lastBackupStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            vm.exportCsv { csv ->
                                shareText(context, csv, "ironlog_export.csv", "text/csv")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export CSV") }
                    OutlinedButton(
                        onClick = {
                            vm.exportJson { json ->
                                shareText(context, json, "ironlog_backup.json", "application/json")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export JSON") }
                }
            }
            item {
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore from backup (JSON)") }
            }

            // --- Workout section ---
            item { SectionHeader("Workout") }

            item {
                SwitchRow("Prevent screen sleep",
                    "Screen stays on during a workout",
                    preventSleep,
                ) { vm.setPreventSleep(it) }
            }
            item {
                SwitchRow("Rest-timer sound",
                    "Plays a buzz when rest ends (vibration always on)",
                    soundEnabled,
                ) { vm.setSoundEnabled(it) }
            }
            item {
                NumericRow(
                    label = "Default rest (seconds)",
                    text = restSecText,
                    onTextChange = { restSecText = it },
                    onCommit = { it.toIntOrNull()?.let { s -> vm.setDefaultRestSec(s) } },
                )
            }
            item {
                RestSoundRow(
                    context = context,
                    storedValue = restSoundUri,
                    onPick = {
                        val existing = when {
                            RestSoundPref.isSilent(restSoundUri) -> null
                            RestSoundPref.isSystemDefault(restSoundUri) ->
                                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
                            else -> runCatching { Uri.parse(restSoundUri) }.getOrNull()
                        }
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Rest-over sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            )
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                        }
                        ringtonePickerLauncher.launch(intent)
                    },
                )
            }

            // --- Plate Calculator section ---
            item { SectionHeader("Plate Calculator") }

            item {
                NumericRow(
                    label = "Default bar weight (lb)",
                    text = barLbText,
                    onTextChange = { barLbText = it },
                    onCommit = { it.toDoubleOrNull()?.let { lb -> vm.setDefaultBarLb(lb) } },
                )
            }
            item {
                Text(
                    "Per-exercise bar overrides can be set from the exercise detail screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }

            // --- Coach section ---
            item { SectionHeader("Coach") }

            item {
                GoalPresetRow(
                    current = defaultGoalPreset.name,
                    onChange = { vm.setDefaultGoalPreset(dev.ironlog.app.progression.GoalPreset.valueOf(it), System.currentTimeMillis()) },
                )
            }
            item {
                XrmFormulaRow(current = xrmFormula, onChange = { vm.setXrmFormula(it) })
            }

            // --- lb ↔ kg Converter ---
            // F2 investigation (owner note: "the ratio is fixed???"): this is a plain two-way unit
            // calculator, not an app setting -- there is no stored/editable ratio to change (it's
            // the exact international pound, 0.45359237 kg/lb; see WeightConversion.KG_PER_LB).
            // ironlog always LOGS in lb regardless of what you type here; this is only for
            // looking up what a kg-labeled machine stack equals in lb, or vice versa.
            item { SectionHeader("Unit Calculator (lb ↔ kg)") }
            item {
                Text(
                    "A quick two-way calculator, not a setting — ironlog always logs in lb. " +
                        "The lb/kg ratio is a fixed physical constant, so there's nothing to edit here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = converterLb,
                        onValueChange = { v ->
                            converterLb = v
                            converterKg = v.toDoubleOrNull()
                                ?.let { "%.2f".format(dev.ironlog.app.data.importer.WeightConversion.lbToKg(it)) } ?: ""
                        },
                        label = { Text("lb") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Text("=", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = converterKg,
                        onValueChange = { v ->
                            converterKg = v
                            converterLb = v.toDoubleOrNull()
                                ?.let { "%.2f".format(dev.ironlog.app.data.importer.WeightConversion.kgToLbRaw(it)) } ?: ""
                        },
                        label = { Text("kg") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // M7: coach nudges
            item { SectionHeader("Coach nudges") }
            item {
                val reminderConfig by vm.reminderConfig.collectAsState()
                SwitchRow(
                    "Neglected-muscle nudges",
                    "Daily reminder when a muscle group hasn't been trained recently",
                    checked = reminderConfig.neglectNudgesEnabled,
                ) { on ->
                    vm.setNeglectNudgesEnabled(on)
                    if (on) dev.ironlog.app.coach.NeglectNudgeWorker.schedule(context)
                    else dev.ironlog.app.coach.NeglectNudgeWorker.cancel(context)
                }
            }
            item {
                val reminderConfig by vm.reminderConfig.collectAsState()
                SwitchRow(
                    "Rest-day nudges",
                    "Suggests a rest day after you've trained 6 days in a row",
                    checked = reminderConfig.restDayNudgesEnabled,
                ) { on ->
                    vm.setRestDayNudgesEnabled(on)
                    if (on) dev.ironlog.app.coach.RestDayNudgeWorker.schedule(context)
                    else dev.ironlog.app.coach.RestDayNudgeWorker.cancel(context)
                }
            }
            item {
                // "Send test notification" — Toasts on both fire AND denial (hingewing gotcha)
                OutlinedButton(
                    onClick = {
                        val sent = dev.ironlog.app.coach.NudgeNotificationHelper.postNeglectNudge(
                            context, listOf("chest", "rear delts"),
                        )
                        android.widget.Toast.makeText(
                            context,
                            if (sent) "Test notification sent" else "Notifications blocked — grant permission in Settings",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send test notification") }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Restore confirmation — restore is additive + dedup-safe, but say so before writing.
    pendingRestoreText?.let { restoreText ->
        AlertDialog(
            onDismissRequest = { pendingRestoreText = null },
            title = { Text("Restore from backup?") },
            text = {
                Text(
                    "Adds exercises, workouts, sets, and measurements from the file. " +
                        "Workouts already in the app are skipped — no duplicates.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingRestoreText = null
                    vm.importJsonBackup(restoreText) { res ->
                        scope.launch {
                            snackbarHost.showSnackbar(
                                "Restored: ${res.insertedWorkouts} workouts, ${res.insertedSets} sets, " +
                                    "${res.insertedExercises} exercises, ${res.insertedMeasurements} measurements",
                            )
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreText = null }) { Text("Cancel") }
            },
        )
    }
}

private fun shareText(context: Context, text: String, filename: String, mimeType: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, filename)
    }
    context.startActivity(Intent.createChooser(intent, "Share $filename"))
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** F6: "Rest-over sound" row -- shows the current pick, taps into the system ringtone picker. */
@Composable
internal fun RestSoundRow(
    context: Context,
    storedValue: String?,
    onPick: () -> Unit,
) {
    val label = remember(storedValue) {
        when {
            RestSoundPref.isSilent(storedValue) -> "None"
            RestSoundPref.isSystemDefault(storedValue) -> "System default"
            else -> runCatching {
                Uri.parse(storedValue)?.let { uri ->
                    RingtoneManager.getRingtone(context, uri)?.getTitle(context)
                }
            }.getOrNull() ?: "System default"
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Rest-over sound", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Sound played when a rest timer finishes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun NumericRow(
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = { onCommit(text) }) { Text("Set") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalPresetRow(current: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("HYPERTROPHY", "STRENGTH", "ENDURANCE")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Default goal preset") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = { onChange(opt); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XrmFormulaRow(current: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("EPLEY", "BRZYCKI", "LOMBARDI")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text("1RM formula (REP_CAP = 12)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onChange(opt); expanded = false },
                )
            }
        }
    }
}
