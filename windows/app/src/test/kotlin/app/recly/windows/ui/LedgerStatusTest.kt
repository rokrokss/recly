@file:OptIn(ExperimentalTime::class)

package app.recly.windows.ui

import app.recly.windows.job
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.jobs.Recents
import app.recly.windows.ui.component.BadgeTone
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.RecordingRecord

/**
 * docs/09 화면 원칙 2: every row state has a code and a tone, the code is the word the core and the
 * logs already use, and no two states look the same to someone who cannot tell the tones apart.
 */
class LedgerStatusTest {

    /** The mapping is only worth anything if it covers everything `Recents` can actually report. */
    @Test
    fun `every state Recents can report has a badge`() {
        statesRecentsCanReport().forEach { (name, state) ->
            assertTrue(
                (state as UiMessage.Res).key in LedgerStates,
                "$name has no badge, so its row would say UNKNOWN",
            )
        }
    }

    @Test
    fun `every badge is a code, and no two states share one`() {
        LedgerStates.values.forEach { badge ->
            assertTrue(badge.code.isNotBlank(), "a state has no code")
            assertEquals(badge.code.uppercase(Locale.ROOT), badge.code, "${badge.code} is not a code")
        }
        val codes = LedgerStates.values.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "two states are told apart only by colour")
    }

    @Test
    fun `the tone says what kind of news it is`() {
        assertEquals(BadgeTone.SUCCESS, Str.STATE_DONE.message().ledgerStatus().tone)
        assertEquals(BadgeTone.DANGER, Str.STATE_FAILED.message().ledgerStatus().tone)
        assertEquals(BadgeTone.DANGER, Str.STATUS_RECORDING.message().ledgerStatus().tone)
        assertEquals(BadgeTone.ACCENT, Str.STATE_UPLOADING.message().ledgerStatus().tone)
        // Something the user has to act on, but nothing is lost yet.
        assertEquals(BadgeTone.WARNING, Str.STATUS_SIGN_IN_NEEDED.message().ledgerStatus().tone)
        assertEquals(BadgeTone.WARNING, Str.STATE_RETRY_WAIT.message().ledgerStatus().tone)
        // Nothing is wrong and nothing is happening.
        assertEquals(BadgeTone.NEUTRAL, Str.STATUS_WAITING.message().ledgerStatus().tone)
        assertEquals(BadgeTone.NEUTRAL, Str.STATE_NO_WORKFLOW.message().ledgerStatus().tone)
        assertEquals(BadgeTone.NEUTRAL, Str.STATE_TOO_SHORT.message().ledgerStatus().tone)
    }

    /** A title the user typed is not a state, and it must not be drawn as a blank cell. */
    @Test
    fun `anything that is not a known state is still a code`() {
        assertEquals("UNKNOWN", UiMessage.Text("Weekly meeting").ledgerStatus().code)
        assertEquals("UNKNOWN", Str.UNTITLED.message().ledgerStatus().code)
    }

    /** docs/09: the time column is monospace data, so it is a fixed pattern, not a locale's date. */
    @Test
    fun `the time column is a fixed pattern and the spoken form is the locale's`() {
        val at = "2026-08-27T10:00:00.000Z"

        assertEquals(5, LedgerFormat.date(at).length, "the date column is not MM-dd")
        assertEquals(5, LedgerFormat.time(at).length, "the time column is not HH:mm")
        assertTrue(LedgerFormat.spoken(at, Locale.ENGLISH).contains("2026"))
        assertTrue(LedgerFormat.spoken(at, Locale.KOREAN).contains("2026"))
    }

    /** A timestamp the parser cannot read is data too — it is shown, not hidden. */
    @Test
    fun `an unparseable timestamp is shown as it stands`() {
        assertEquals("not a date", LedgerFormat.date("not a date"))
    }

    @Test
    fun `the timer counts hours, minutes and seconds`() {
        assertEquals("00:00:00", LedgerFormat.elapsed(0))
        assertEquals("00:00:59", LedgerFormat.elapsed(59_999))
        assertEquals("01:02:03", LedgerFormat.elapsed(3_723_000))
        // A clock that went backwards is not a negative recording.
        assertEquals("00:00:00", LedgerFormat.elapsed(-5_000))
    }

    /**
     * docs/09 화면 원칙 2: the 길이 column, in the shape the phone and the Mac write it — and the
     * placeholder all three use for a recording that has no length yet, which is a cell that says
     * "not in yet" rather than one that lost its value.
     */
    @Test
    fun `the length column is minutes, hours past the hour, and a placeholder until finalized`() {
        assertEquals("00:00", LedgerFormat.length(0.0))
        assertEquals("42:10", LedgerFormat.length(2_530.0))
        assertEquals("1:02:03", LedgerFormat.length(3_723.4))
        assertEquals(LedgerFormat.NO_LENGTH, LedgerFormat.length(null))
        assertEquals(LedgerFormat.NO_LENGTH, LedgerFormat.length(-1.0))
    }

    /** Every answer `Recents.stateLabel` has, driven through it rather than restated here. */
    private fun statesRecentsCanReport(): List<Pair<String, UiMessage>> =
        listOf("RECORDING" to Recents.stateLabel(record(RecordingStatus.RECORDING), null)) +
            listOf("no job" to Recents.stateLabel(record(), null)) +
            JobStatus.entries.map { status ->
                status.name to Recents.stateLabel(record(), job("j", status))
            }

    private fun record(status: RecordingStatus = RecordingStatus.FINALIZED) = RecordingRecord(
        id = "rec-1",
        meta = RecordingMeta(
            schema = 1,
            recordingId = "rec-1",
            source = Source.DESKTOP,
            platform = Platform.WINDOWS,
            deviceId = "device",
            deviceName = "PC",
            title = "Weekly meeting",
            startedAt = "2026-08-27T10:00:00.000Z",
            timezone = "Asia/Seoul",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MIC, Track.SYS, Track.MIX),
            parts = emptyList(),
            status = status,
        ),
        dir = "/tmp/rec-1".toPath(),
    )
}
