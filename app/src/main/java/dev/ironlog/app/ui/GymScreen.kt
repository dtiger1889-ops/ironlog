package dev.ironlog.app.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.GymProfile

/** "My Gym" — manage equipment profiles. The active profile filters the muscle finder + swap.
 *  Toggling equipment / activating a profile persists immediately (no Save button). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GymScreen(
    vm: IronlogViewModel,
    onBack: () -> Unit,
    onOpenFinder: () -> Unit = {},
) {
    val profiles by vm.gymProfiles.collectAsState()
    val tokens = vm.allEquipmentTokens
    // profileId -> its ON equipment tokens; loaded on launch, kept in sync on toggle.
    val equipment = remember { mutableStateMapOf<Long, Set<String>>() }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<GymProfile?>(null) }

    LaunchedEffect(profiles.map { it.id }) {
        for (p in profiles) {
            if (p.id !in equipment) equipment[p.id] = vm.equipmentForProfile(p.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Gym") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
                Card {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("What this is for", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Tell IronLog what equipment each gym has. Turn ON only the gear that's actually " +
                                "available, and set one profile Active. The muscle finder and the busy-station " +
                                "swap then only suggest exercises you can really do here — e.g. switch gyms and " +
                                "the machine-only lifts drop out automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (profiles.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = onOpenFinder, modifier = Modifier.fillMaxWidth()) {
                        Text("Find exercises by muscle →")
                    }
                }
            }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "No gym profile yet. Add one below to filter exercises to the equipment you actually have.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(profiles, key = { it.id }) { profile ->
                GymProfileCard(
                    profile = profile,
                    tokens = tokens,
                    onTokens = equipment[profile.id] ?: emptySet(),
                    onSetActive = { vm.setActiveGymProfile(profile.id) },
                    onRename = { renaming = profile },
                    onDelete = {
                        vm.deleteGymProfile(profile.id)
                        equipment.remove(profile.id)
                    },
                    onToggleToken = { token, on ->
                        // optimistic local update + persist
                        val cur = equipment[profile.id] ?: emptySet()
                        equipment[profile.id] = if (on) cur + token else cur - token
                        vm.setEquipmentToken(profile.id, token, on)
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ Add Profile") }
            }
        }
    }

    if (showAdd) {
        GymNameDialog(
            title = "New gym profile",
            initial = "",
            onConfirm = { name ->
                showAdd = false
                vm.createGymProfile(name)
            },
            onDismiss = { showAdd = false },
        )
    }

    renaming?.let { p ->
        GymNameDialog(
            title = "Rename profile",
            initial = p.name,
            onConfirm = { name ->
                renaming = null
                vm.renameGymProfile(p.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GymProfileCard(
    profile: GymProfile,
    tokens: List<String>,
    onTokens: Set<String>,
    onSetActive: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleToken: (String, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = profile.isActive, onClick = onSetActive)
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                if (profile.isActive) {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) { Text("✕") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tokens.forEach { token ->
                    val on = token in onTokens
                    FilterChip(
                        selected = on,
                        onClick = { onToggleToken(token, !on) },
                        label = { Text(token.replaceFirstChar { it.uppercase() }) },
                        leadingIcon = if (on) {
                            { Text("✓") }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GymNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name (e.g. Planet Fitness, Home)") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
