package dev.ironlog.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Required by Health Connect: displayed when the user taps "App permissions" in the HC
 * permissions screen. Explains what data is read/written and that it stays on-device.
 */
class PrivacyPolicyActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IronlogTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Privacy Policy") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("IronLog Privacy Policy", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "IronLog is a fully offline app. Your data never leaves your device " +
                                "or connects to any server.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Health Connect usage:\n" +
                                "• Body weight — read and write with your permission.\n" +
                                "• Body fat % — read and write with your permission.\n" +
                                "• Exercise sessions — write one record per finished workout.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "All data synced with Health Connect remains on-device and is never " +
                                "transmitted to a third party by IronLog.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
