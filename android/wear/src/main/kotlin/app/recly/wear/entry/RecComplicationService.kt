@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.wear.entry

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import app.recly.wear.R
import app.recly.wear.RecWearApp
import app.recly.wear.ui.MainActivity

/**
 * docs/11 W5: the watch face's own line about this app. The record motif, and beside it the one thing
 * that changes what the user would do: while the microphone is open, how long it has been (N4, UX
 * decisions of 2026-10-11 — the face counts it, so this process never wakes to redraw), otherwise
 * whether recordings are still waiting for the phone. A slot with no room for words (MONOCHROMATIC_IMAGE)
 * keeps the square alone.
 *
 * The face tints the glyph: a complication's monochrome image has no colour of its own to give, so the
 * recording is told by the filled square and the running time rather than by red.
 *
 * `SuspendingComplicationDataSourceService` because the count comes off the transfer queue, which
 * reads a file the first time it is asked.
 */
class RecComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val app = applicationContext as? RecWearApp
        val look = complicationLook(
            recorder = RecorderService.state.value,
            pending = app?.pendingCount() ?: 0,
            // docs/11 W2: the same rule as the screen and the tile — a pass with a phone on the other
            // end is "sending", not "waiting" (Sol, 2026-09-04: the description said so, the text did not).
            sending = app?.queue?.sending?.value == true,
        )
        return data(request.complicationType, look)
    }

    /** What the watch-face editor shows in the picker, before it has ever asked for real data: a recording. */
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val preview = getString(R.string.complication_recording_description, PREVIEW_ELAPSED)
        return when (type) {
            ComplicationType.SHORT_TEXT -> shortText(plain(PREVIEW_ELAPSED), plain(preview), R.drawable.ic_complication_recording)
            ComplicationType.MONOCHROMATIC_IMAGE -> glyphOnly(plain(preview), R.drawable.ic_complication_recording)
            else -> null
        }
    }

    private fun data(type: ComplicationType, look: ComplicationLook): ComplicationData? {
        val (text, description) = when (look) {
            is ComplicationLook.Elapsed -> {
                val since = CountUpTimeReference(java.time.Instant.ofEpochMilli(look.sinceMillis))
                // docs/09 "Time format": under an hour the face writes 02:35, as the watch's own timer does.
                elapsed(since, null) to elapsed(since, getString(R.string.complication_recording_description, "^1"))
            }
            is ComplicationLook.Words -> plain(getString(look.text)) to plain(getString(look.description))
        }
        return when (type) {
            ComplicationType.SHORT_TEXT -> shortText(text, description, look.glyph)
            ComplicationType.MONOCHROMATIC_IMAGE -> glyphOnly(description, look.glyph)
            else -> null
        }
    }

    private fun elapsed(since: CountUpTimeReference, around: String?): ComplicationText =
        TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.STOPWATCH, since)
            .apply { around?.let(::setText) }
            .build()

    private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

    private fun glyph(@DrawableRes id: Int): MonochromaticImage =
        MonochromaticImage.Builder(Icon.createWithResource(this, id)).build()

    private fun shortText(text: ComplicationText, description: ComplicationText, @DrawableRes glyph: Int): ComplicationData =
        ShortTextComplicationData.Builder(text = text, contentDescription = description)
            .setMonochromaticImage(glyph(glyph))
            .setTapAction(openApp())
            .build()

    private fun glyphOnly(description: ComplicationText, @DrawableRes glyph: Int): ComplicationData =
        MonochromaticImageComplicationData.Builder(glyph(glyph), description)
            .setTapAction(openApp())
            .build()

    /**
     * Tapping it opens the app and nothing more. Unlike the tile it does not carry the auto-start
     * extra: a complication sits on the watch face under a fingertip all day, and a recording
     * started by a stray tap is a recording the user does not know is running. So its idle label says
     * what it is (the app, and whether anything waits) rather than "Start recording".
     */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        /** The picker's preview is a still: a recording twelve seconds in. */
        const val PREVIEW_ELAPSED = "00:12"
    }
}

/** What the complication shows, before it becomes complication data — the whole mapping, checked without a watch. */
internal sealed interface ComplicationLook {
    @get:DrawableRes val glyph: Int

    /** Recording: the time since [sinceMillis], which the face counts up on its own. */
    data class Elapsed(val sinceMillis: Long) : ComplicationLook {
        override val glyph: Int get() = R.drawable.ic_complication_recording
    }

    /** Anything else: a word, and the sentence a screen reader says for it. */
    data class Words(
        @param:StringRes val text: Int,
        @param:StringRes val description: Int,
        @param:DrawableRes override val glyph: Int,
    ) : ComplicationLook
}

/**
 * A recording being made outranks recordings waiting to go: it is the one the user is wearing the
 * watch for. The moments before the first sample and while the take is saved have no start time to
 * count from, and say so with the recorder's own words.
 */
internal fun complicationLook(recorder: RecorderState, pending: Int, sending: Boolean): ComplicationLook = when (recorder) {
    is RecorderState.Recording -> ComplicationLook.Elapsed(recorder.startedAt.toEpochMilliseconds())
    RecorderState.Starting ->
        ComplicationLook.Words(R.string.recording_busy, R.string.recording_active, R.drawable.ic_complication_recording)
    RecorderState.Stopping ->
        ComplicationLook.Words(R.string.recording_busy, R.string.recording_stopping, R.drawable.ic_complication_recording)
    RecorderState.Idle -> when {
        pending > 0 && sending -> ComplicationLook.Words(R.string.complication_sending, R.string.sending_badge, R.drawable.ic_complication_record)
        pending > 0 -> ComplicationLook.Words(R.string.complication_pending, R.string.pending_badge, R.drawable.ic_complication_record)
        else -> ComplicationLook.Words(R.string.complication_idle, R.string.entry_ready, R.drawable.ic_complication_record)
    }
}
