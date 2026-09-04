package app.recly.windows.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.model.Track

/**
 * docs/14 "캡처": the three things the recording mode decides, held still. Every one of them is a
 * branch the Mac takes too (`RecorderTypes.RecordingMode`, `MenuModel.start(mode:)`), and the two
 * desktops must not answer them differently — a Windows user who has used the Mac would find a
 * microphone-only memo with the whole room in it, or a memo asking about participants there are none of.
 */
class RecordingModeTest {

    /** ADR-006: `mono` alone, or the three tracks that share one start time and one boundary. */
    @Test
    fun `each mode names the tracks it records`() {
        assertEquals(listOf(Track.MONO), RecordingMode.MICROPHONE.tracks)
        assertEquals(listOf(Track.MIC, Track.SYS, Track.MIX), RecordingMode.MEETING.tracks)
    }

    /** docs/12 "종료 감지": a memo's own idle microphone is not a meeting that has ended. */
    @Test
    fun `only a meeting has an end worth detecting`() {
        assertFalse(RecordingMode.MICROPHONE.detectsEnd)
        assertTrue(RecordingMode.MEETING.detectsEnd)
    }

    /** docs/12 M8 · ADR-011: the reminder is about the other participants, and a memo has none. */
    @Test
    fun `only a meeting reminds the user about consent`() {
        assertFalse(RecordingMode.MICROPHONE.remindsConsent)
        assertTrue(RecordingMode.MEETING.remindsConsent)
    }

    /**
     * The stored key is the Mac's own (`"microphone"` / `"meeting"`), and a store that has never
     * been written keeps recording what every Windows recording before this setting recorded.
     */
    @Test
    fun `an unwritten store still records the meeting`() {
        assertEquals(RecordingMode.MEETING, RecordingMode.of(null))
        assertEquals(RecordingMode.MEETING, RecordingMode.of(""))
        assertEquals(RecordingMode.MEETING, RecordingMode.of("meeting"))
        assertEquals(RecordingMode.MICROPHONE, RecordingMode.of("microphone"))
    }
}
