package app.recly.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import app.recly.wear.RecWearApp
import app.recly.wear.core.WearLogger
import app.recly.wear.data.PrefsWatchDefault
import app.recly.wear.data.WorkflowSource
import app.recly.wear.transfer.TransferScheduler

/**
 * The watch's only screen, and — docs/11 W1 — the only thing that may start the recording: a
 * `while-in-use` foreground service started from the background throws by design, so the tile
 * (W5) comes through here too, carrying [EXTRA_AUTO_START].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WearRecordingViewModel by viewModels {
        viewModelFactory {
            initializer {
                WearRecordingViewModel(
                    recorder = ServiceRecorderControl(applicationContext),
                    workflows = WorkflowSource(applicationContext, WearLogger).flow(),
                    queue = (application as RecWearApp).queue,
                    haptics = SystemHaptics(applicationContext),
                    defaults = PrefsWatchDefault(applicationContext),
                )
            }
        }
    }

    /**
     * Wear shows this as its own full-screen dialog. `POST_NOTIFICATIONS` rides along un-gated: the
     * recording runs without it, but the watch-face chip (W3) is a notification and does not.
     */
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) viewModel.start() else viewModel.micDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            MainScreen(
                state = state,
                onStart = ::startRecording,
                onStop = viewModel::stop,
                onSelect = viewModel::selectWorkflow,
            )
        }
        consumeAutoStart(intent)
    }

    /** A tile tap while this activity is already on top (`android:launchMode="singleTop"`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoStart(intent)
    }

    /**
     * docs/11 W4, the third trigger: coming back to the app is as good a reason to try the phone as
     * the phone appearing is. Cheap — the pass finds nothing waiting and returns.
     */
    override fun onStart() {
        super.onStart()
        TransferScheduler.runNow(applicationContext)
    }

    /**
     * Exactly once, and the removal is what makes it so: the intent outlives this call and comes
     * back on every configuration change and every `onNewIntent`, and a tile tap that starts a
     * second recording — or restarts one when the screen rotates — is worse than one that starts
     * none. The phone's `MainActivity` does the same thing for its tile and widget.
     */
    private fun consumeAutoStart(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_AUTO_START, false)) return
        intent.removeExtra(EXTRA_AUTO_START)
        // The service's own state, not the ViewModel's: the screen has not collected anything yet
        // when this runs from `onCreate`, and it would still say Idle for a recording that is
        // three hours in. A tile tapped while recording opens the app and leaves it alone.
        if (RecorderService.state.value != RecorderState.Idle) return
        // Not `viewModel.start()`: the tile is the one entry point that can reach a watch which has
        // never been granted the microphone, and the permission dialog belongs to the activity.
        startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
        } else {
            permissions.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    companion object {
        /** docs/11 W5: what the tile's `launchAction` asks for, as the phone's A9 entry points do. */
        const val EXTRA_AUTO_START: String = "app.recly.wear.extra.AUTO_START"
    }
}
