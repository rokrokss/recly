package app.recly.android.ui

import app.recly.android.R
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.model.Track
import recly.core.transcribe.EditResult
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptVersionKind

/** docs/08 "Versions" on the phone: why a restore did not happen, and what Version history's rows say. */
class VersionHistoryTest {

    private val transcript = Transcript(
        recordingId = "r1", track = Track.MIC, language = "en", provider = TranscriptProvider("assemblyai"),
        createdAt = "2026-11-04T10:00:00Z", durationSec = 90.0, speakers = emptyList(), segments = emptyList(),
    )

    @Test
    fun `a restore that did not happen says why, a job in the way muted and anything else as a failure`() {
        assertNull(restoreRefusal(EditResult.Edited(transcript), R.string.detail_transcribing))
        assertEquals(RestoreRefusal(R.string.detail_transcribing, failed = false), restoreRefusal(EditResult.Busy, R.string.detail_transcribing))
        val failed = RestoreRefusal(R.string.version_restore_failed, failed = true)
        assertEquals(failed, restoreRefusal(EditResult.Invalid("no version 9"), R.string.detail_transcribing))
        assertEquals(failed, restoreRefusal(EditResult.NoTranscript, R.string.detail_transcribing))
        assertEquals(failed, restoreRefusal(null, R.string.detail_transcribing))
    }

    @Test
    fun `every kind of version has its own words`() {
        assertEquals(TranscriptVersionKind.entries.size, TranscriptVersionKind.entries.map { it.label() }.toSet().size)
        assertEquals(R.string.version_received, TranscriptVersionKind.RECEIVED.label())
    }

    @Test
    fun `a version's time is the locale's own, in the device's zone`() {
        val utc = ZoneId.of("UTC")
        val english = versionStamp("2026-11-04T15:04:00Z", Locale.US, utc)
        assertTrue("Nov 4, 2026" in english, english)
        assertTrue("3:04" in english, english)
        val korean = versionStamp("2026-11-04T15:04:00Z", Locale.KOREA, ZoneId.of("Asia/Seoul"))
        assertTrue("2026. 11. 5." in korean, korean)
        assertEquals("not a time", versionStamp("not a time", Locale.US, utc))
    }
}
