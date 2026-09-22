package dev.ironlog.app.ui

import android.widget.Toast
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ironlog.app.data.Exercise

/** Muscle-target finder: pick a muscle → exercises that train it, FILTERED to the active gym's
 *  equipment. Add straight into the current workout (if one is in progress). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FinderScreen(
    vm: IronlogViewModel,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val context = LocalContext.current
    val profiles by vm.gymProfiles.collectAsState()
    val activeDraft by vm.draft.collectAsState()
    val activeName = profiles.firstOrNull { it.isActive }?.name

    var muscles by remember { mutableStateOf<List<String>>(emptyList()) }
    // Saveable so the chosen muscle survives opening an exercise + backing out (results reload).
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Exercise>>(emptyList()) }

    LaunchedEffect(Unit) { muscles = vm.catalogMuscles() }
    LaunchedEffect(selected, profiles) {
        results = selected?.let { vm.findExercisesForMuscle(it) } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find by muscle") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    if (activeName != null) "Filtered to your active gym: $activeName" else
                        "No active gym — showing all equipment. Set one Active in My Gym.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    muscles.forEach { m ->
                        FilterChip(
                            selected = selected == m,
                            onClick = { selected = if (selected == m) null else m },
                            label = { Text(m.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
            if (selected != null && results.isEmpty()) {
                item {
                    Text(
                        "No exercises for ${selected} at this gym. Turn on more equipment in My Gym.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(results, key = { it.id }) { ex ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail(ex.id) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ex.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(ex.equipment, ex.mechanic).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (activeDraft != null) {
                        TextButton(onClick = {
                            vm.addExercise(ex)
                            Toast.makeText(context, "Added ${ex.name} to workout", Toast.LENGTH_SHORT).show()
                        }) { Text("Add") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
