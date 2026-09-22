package dev.ironlog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.Measurement
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Measurement type catalogue ----------

internal enum class MeasurementType(
    val token: String,
    val label: String,
    val unit: String,
    val hcBacked: Boolean,
) {
    BODYWEIGHT("bodyweight", "Body Weight", "lb", true),
    BODYFAT("bodyfat", "Body Fat %", "%", true),
    CHEST("chest", "Chest", "in", false),
    WAIST("waist", "Waist", "in", false),
    HIPS("hips", "Hips", "in", false),
    THIGH("thigh", "Thigh", "in", false),
    ARM("arm", "Arm", "in", false),
    CALF("calf", "Calf", "in", false),
    SHOULDER("shoulder", "Shoulder", "in", false),
    NECK("neck", "Neck", "in", false),
    FOREARM("forearm", "Forearm", "in", false),
}

private val DATE_FMT = SimpleDateFormat("MMM d", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureScreen(
    vm: IronlogViewModel,
    onOpenSettings: () -> Unit,
) {
    val allMeasurements by vm.measurements.collectAsState()
    val hcAvailable by produceState(false) { value = vm.hcGateway.isAvailable() }

    var showLogDialog by remember { mutableStateOf(false) }
    var logType by remember { mutableStateOf(MeasurementType.BODYWEIGHT) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Measure") },
                actions = {
                    if (hcAvailable) {
                        TextButton(onClick = {
                            vm.syncFromHealthConnect(
                                lastSyncMs = System.currentTimeMillis() - 30L * 24 * 3600 * 1000,
                            )
                        }) { Text("Sync HC") }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showLogDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Log measurement")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hcAvailable) {
                item {
                    Text(
                        "Health Connect not installed — manual logging only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            items(MeasurementType.entries) { type ->
                val rows = allMeasurements.filter { it.type == type.token }
                MeasurementTypeCard(
                    type = type,
                    rows = rows,
                    onClick = {
                        logType = type
                        showLogDialog = true
                    },
                    onDelete = { vm.deleteMeasurement(it) },
                )
            }
        }
    }

    if (showLogDialog) {
        LogMeasurementDialog(
            initialType = logType,
            onConfirm = { type, value ->
                vm.logMeasurement(
                    type = type.token,
                    value = value,
                    unit = type.unit,
                )
                showLogDialog = false
            },
            onDismiss = { showLogDialog = false },
        )
    }
}

@Composable
internal fun MeasurementTypeCard(
    type: MeasurementType,
    rows: List<Measurement>,
    onClick: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    val latest = rows.firstOrNull()
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(type.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (latest != null) {
                        Text(
                            "%.1f %s  ·  %s".format(latest.value, type.unit, DATE_FMT.format(Date(latest.timestamp))),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("No data yet", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (rows.size >= 2) {
                Spacer(Modifier.height(8.dp))
                MeasurementSparkline(rows = rows)
            }
        }
    }
}

@Composable
private fun MeasurementSparkline(rows: List<Measurement>) {
    // Show the most recent 20 points, oldest → newest on X axis
    val points = rows.reversed().takeLast(20)
    if (points.size < 2) return
    val entries = points.mapIndexed { i, m -> entryOf(i.toFloat(), m.value.toFloat()) }
    Chart(
        chart = lineChart(),
        model = entryModelOf(entries),
        bottomAxis = rememberBottomAxis(),
        modifier = Modifier.fillMaxWidth().height(100.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogMeasurementDialog(
    initialType: MeasurementType,
    onConfirm: (MeasurementType, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(initialType) }
    var valueText by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    val valueError = valueText.isNotBlank() && valueText.toDoubleOrNull() == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Measurement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        MeasurementType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text("${t.label} (${t.unit})") },
                                onClick = {
                                    selectedType = t
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text("Value (${selectedType.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = valueError,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val v = valueText.toDoubleOrNull() ?: return@TextButton
                    onConfirm(selectedType, v)
                },
                enabled = valueText.toDoubleOrNull() != null,
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
