package app.recly.wear.entry

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import app.recly.wear.R
import app.recly.wear.RecWearApp
import app.recly.wear.ui.MainActivity

/**
 * docs/11 W5: the watch face's own line about this app. SHORT_TEXT and nothing else — a
 * complication slot is a few characters wide, so it says the one thing that changes what the user
 * would do: "REC" while the microphone is open, otherwise how many recordings the phone has not
 * taken yet.
 *
 * `SuspendingComplicationDataSourceService` because the count comes off the transfer queue, which
 * reads a file the first time it is asked.
 */
class RecComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val recording = RecorderService.state.value != RecorderState.Idle
        val app = applicationContext as? RecWearApp
        val pending = app?.pendingCount() ?: 0
        // docs/11 W2: the same rule as the screen and the tile — a pass with a phone on the other
        // end is "sending", not "waiting" (Sol, 2026-09-04: the description said so, the text did not).
        val sending = app?.queue?.sending?.value == true
        return shortText(
            text = when {
                recording -> getString(R.string.complication_recording)
                pending > 0 && sending -> getString(R.string.complication_sending, pending)
                pending > 0 -> getString(R.string.complication_pending, pending)
                else -> getString(R.string.complication_idle)
            },
            description = entryStatus(),
        )
    }

    /** What the watch-face editor shows in the picker, before it has ever asked for real data. */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortText(
            text = getString(R.string.complication_recording),
            description = getString(R.string.recording_active),
        )
    }

    /**
     * Tapping it opens the app and nothing more. Unlike the tile it does not carry the auto-start
     * extra: a complication sits on the watch face under a fingertip all day, and a recording
     * started by a stray tap is a recording the user does not know is running.
     */
    private fun shortText(text: String, description: String): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .setTapAction(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
}
