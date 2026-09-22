package dev.ironlog.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.ironlog.app.coach.NudgeNotificationHelper
import dev.ironlog.app.data.AppSettings
import dev.ironlog.app.data.IronlogDatabase
import dev.ironlog.app.data.IronlogRepository
import dev.ironlog.app.healthconnect.NoOpHealthConnectGateway
import dev.ironlog.app.timer.AndroidRestAlarmScheduler
import dev.ironlog.app.timer.AndroidWorkoutNotifier
import dev.ironlog.app.timer.RestNotificationHelper

class MainActivity : ComponentActivity() {

    // HC toolchain stub: NoOp until compileSdk/AGP are upgraded (see RealHealthConnectGateway).
    private val hcGateway = NoOpHealthConnectGateway()

    private val viewModel: IronlogViewModel by viewModels {
        IronlogViewModel.Factory(
            IronlogRepository(IronlogDatabase.get(applicationContext)),
            AppSettings(applicationContext),
            AndroidRestAlarmScheduler(applicationContext),
            hcGateway,
            AndroidWorkoutNotifier(applicationContext),
        )
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RestNotificationHelper.createChannel(this)
        NudgeNotificationHelper.createChannel(this)
        AndroidWorkoutNotifier.createChannel(this)
        // A process kill mid-workout can leave a stale in-progress notification; the draft is
        // gone on a cold relaunch, so the notification must go too. Guarded on the draft so an
        // Activity recreation DURING a live workout doesn't wipe the live notification.
        if (viewModel.draft.value == null) AndroidWorkoutNotifier(applicationContext).cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Seed the 873-row free-exercise-db catalog at startup so it's present no matter
        // which tab the user opens first. No-op after first run (countCatalogOnly()>0 guard).
        viewModel.ensureCatalogSeeded(applicationContext)
        // Auto-map logged exercises to muscle metadata (confident name matches) so finder/swap/
        // coverage work without hand-confirming each. No-op once nothing is unmapped.
        viewModel.ensureLoggedExercisesReconciled(applicationContext)
        // F1 one-time repair: purge phantom sets an earlier import created from the previous tracker's
        // "Rest Timer"/"Note" metadata rows (and the type/template damage they caused).
        viewModel.ensureImportedMetadataRepaired(applicationContext)
        viewModel.ensureCardioTypesRepaired(applicationContext)
        // Legacy templates carried only an exercise list; snapshot per-set rows once so the
        // template — not the previous session — defines each draft's set structure.
        viewModel.ensureTemplateSetsBackfilled()
        setContent {
            val preventSleep by viewModel.preventSleep.collectAsState()
            val activeDraft by viewModel.draft.collectAsState()
            // Keep the screen on only DURING an active workout (the setting's documented scope),
            // not app-wide — otherwise browsing History/Exercises would never let the screen sleep.
            if (preventSleep && activeDraft != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            IronlogTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IronlogApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeRest()
    }
}
