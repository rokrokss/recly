package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.transcribe.EditResult
import recly.core.transcribe.TranscriptVersionKind

/** docs/09 "Version history" on the PC: each version's word, why a restore did not happen, and the copy. */
class VersionHistoryTest {

    private val en = StringTable.of(StringTable.BASE)
    private val ko = StringTable.of(StringTable.KOREAN)

    @Test
    fun `each version says what made it`() {
        assertEquals(
            listOf("Transcribed", "Edited", "Corrected", "Restored", "Changed on another device"),
            TranscriptVersionKind.entries.map { en[versionKindLabel(it)] },
        )
        assertEquals(listOf("전사됨", "편집됨", "교정됨", "복원됨", "다른 기기에서 바뀜"), TranscriptVersionKind.entries.map { ko[versionKindLabel(it)].plain() })
    }

    @Test
    fun `a restore that did not happen says why`() {
        assertNull(restoreRefusal(EditResult.Edited(transcript()), Str.DETAIL_TRANSCRIBING))
        assertEquals(Str.DRIVE_PENDING, restoreRefusal(EditResult.Busy, Str.DRIVE_PENDING))
        assertEquals(Str.VERSIONS_RESTORE_FAILED, restoreRefusal(EditResult.Invalid("no version 9"), Str.DETAIL_TRANSCRIBING))
        assertEquals(Str.VERSIONS_RESTORE_FAILED, restoreRefusal(EditResult.NoTranscript, Str.DETAIL_TRANSCRIBING))
        // A call that threw.
        assertEquals(Str.VERSIONS_RESTORE_FAILED, restoreRefusal(null, Str.DETAIL_TRANSCRIBING))
    }

    @Test
    fun `the copy is the spec's`() {
        assertEquals("Version history", en[Str.VERSIONS_TITLE])
        assertEquals("Restore the version from Nov 4, 2026", en[Str.VERSIONS_RESTORE_LABEL, "Nov 4, 2026"])
        assertEquals("버전 기록", ko[Str.VERSIONS_TITLE].plain())
    }

    private fun transcript() = recly.core.transcribe.Transcript(
        recordingId = "rec",
        track = recly.core.model.Track.MONO,
        language = "en",
        provider = recly.core.transcribe.TranscriptProvider("test"),
        createdAt = "2026-11-04T00:00:00Z",
        durationSec = 5.0,
        speakers = emptyList(),
        segments = listOf(recly.core.transcribe.TranscriptSegment(0.0, 5.0, "", "hello")),
    )
}
