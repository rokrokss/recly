package app.recly.android.entry

import android.app.PendingIntent
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.recly.android.R
import app.recly.android.ui.MainActivity
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState

/**
 * docs/11 A9: the quick settings tile. It cannot start the recorder itself — a `microphone`
 * foreground service started from a tile is a background start and the platform throws — so it
 * opens the app and lets it start, which is also what keeps the microphone indicator honest.
 *
 * `startActivityAndCollapse(PendingIntent)` is the only form that exists on API 34+, which is this
 * app's `minSdk`; the `Intent` overload throws `UnsupportedOperationException` there.
 */
class RecTileService : TileService() {

    /**
     * The tile is on screen, so say what the recorder is doing. This runs in the app's own process,
     * so a recording in progress is a live `RecorderService.state` rather than a guess.
     */
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val idle = RecorderService.state.value == RecorderState.Idle
        tile.state = if (idle) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = getString(if (idle) R.string.tile_idle else R.string.tile_recording)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                // Stamped here, which is the tap: docs/11 A9's "spend or drop" measures the age of
                // the request, and the cold start the user waits through is part of it.
                // `FLAG_UPDATE_CURRENT` is what puts each new stamp on the intent — a PendingIntent
                // matches on everything but its extras, so the old one would otherwise stand.
                MainActivity.startRecording(this, requestedAt = SystemClock.elapsedRealtime()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }
}
