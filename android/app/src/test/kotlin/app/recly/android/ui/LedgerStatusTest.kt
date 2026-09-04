package app.recly.android.ui

import app.recly.android.ui.component.BadgeTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 2: every row state has a code and a tone, the code is the word the core and the
 * logs already use, and no two states look the same to someone who cannot tell the tones apart.
 */
class LedgerStatusTest {

    @Test
    fun `every state has a code and a tone`() {
        ItemState.entries.forEach { state ->
            val badge = state.badge()
            assertTrue(badge.code.isNotBlank(), "$state has no code")
            assertEquals(badge.code.uppercase(), badge.code, "$state's code is not a code")
        }
    }

    @Test
    fun `no two states share a code`() {
        val codes = ItemState.entries.map { it.badge().code }
        assertEquals(codes.size, codes.toSet().size, "two states are told apart only by colour")
    }

    @Test
    fun `the tone says what kind of news it is`() {
        assertEquals(BadgeTone.SUCCESS, ItemState.DONE.badge().tone)
        assertEquals(BadgeTone.DANGER, ItemState.FAILED.badge().tone)
        assertEquals(BadgeTone.DANGER, ItemState.RECORDING.badge().tone)
        assertEquals(BadgeTone.ACCENT, ItemState.RUNNING.badge().tone)
        // Something the user has to act on, but nothing is lost yet.
        assertEquals(BadgeTone.WARNING, ItemState.NEEDS_AUTH.badge().tone)
        assertEquals(BadgeTone.WARNING, ItemState.NEEDS_SPACE.badge().tone)
        assertEquals(BadgeTone.WARNING, ItemState.WAITING.badge().tone)
        // Nothing is wrong and nothing is happening.
        assertEquals(BadgeTone.NEUTRAL, ItemState.PENDING.badge().tone)
        assertEquals(BadgeTone.NEUTRAL, ItemState.NO_JOB.badge().tone)
        assertEquals(BadgeTone.NEUTRAL, ItemState.SKIPPED_SHORT.badge().tone)
    }

    /** docs/10 "Drive 용량 초과": its own code, not a FAILED it would be mistaken for. */
    @Test
    fun `out of Drive space is its own row state`() {
        assertEquals("NO_SPACE", ItemState.NEEDS_SPACE.badge().code)
        assertTrue(ItemState.NEEDS_SPACE.failing(), "it is not waiting for anything on its own")
    }

    /** The header's two counts: every state is waiting, failing, or neither — never both. */
    @Test
    fun `the header counts do not overlap`() {
        ItemState.entries.forEach { state ->
            assertTrue(!(state.waiting() && state.failing()), "$state is counted twice")
        }
        assertEquals(3, ItemState.entries.count { it.waiting() })
        assertEquals(4, ItemState.entries.count { it.failing() })
    }
}
