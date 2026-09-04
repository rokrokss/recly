package app.recly.android.ui

import app.recly.android.ui.component.BadgeTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * docs/03 "다른 기기의 녹음": a code says what is happening, not where — an upload another device
     * is running is the same word as one of this phone's own, and that is the only pair. Anything
     * else sharing a code would be two states told apart by colour alone.
     */
    @Test
    fun `two states share a code only when they say the same thing`() {
        val shared = ItemState.entries.groupBy { it.badge().code }.filterValues { it.size > 1 }

        assertEquals(
            mapOf("UPLOADING" to listOf(ItemState.REMOTE_UPLOADING, ItemState.RUNNING)),
            shared,
        )
    }

    /** docs/03: the three things happening somewhere else, as the ledger says them. */
    @Test
    fun `what another device is doing has its own codes`() {
        assertEquals("RECEIVING", ItemState.RECEIVING.badge().code)
        assertEquals(BadgeTone.ACCENT, ItemState.RECEIVING.badge().tone)
        assertEquals("UPLOADING", ItemState.REMOTE_UPLOADING.badge().code)
        assertEquals(BadgeTone.ACCENT, ItemState.REMOTE_UPLOADING.badge().tone)
        // The same code a job of this device's waiting on a provider shows.
        assertEquals("TRANSCRIBING", ItemState.REMOTE_TRANSCRIBING.badge().code)
        assertEquals(BadgeTone.ACCENT, ItemState.REMOTE_TRANSCRIBING.badge().tone)
    }

    /**
     * docs/09 화면 원칙 2: a recording something is doing to it right now offers nothing — deleting
     * one would pull the file out from under a recorder, a transfer or another device's upload.
     * A recording another device is transcribing has arrived and is a finished row like any other.
     */
    @Test
    fun `a recording in flight elsewhere offers no row action`() {
        assertTrue(ItemState.RECEIVING.inFlight())
        assertTrue(ItemState.REMOTE_UPLOADING.inFlight())
        assertFalse(ItemState.REMOTE_TRANSCRIBING.inFlight())
        assertEquals(
            listOf(
                ItemState.RECORDING,
                ItemState.RECEIVING,
                ItemState.REMOTE_UPLOADING,
                ItemState.RUNNING,
            ),
            ItemState.entries.filter { it.inFlight() },
        )
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
        assertEquals(5, ItemState.entries.count { it.waiting() })
        assertEquals(4, ItemState.entries.count { it.failing() })
    }

    /**
     * docs/03 "다른 기기의 녹음": a recording still on its way here is one the list is waiting for,
     * whichever device is bringing it. One another device is transcribing has already arrived.
     */
    @Test
    fun `a recording still on its way counts as waiting`() {
        assertTrue(ItemState.RECEIVING.waiting())
        assertTrue(ItemState.REMOTE_UPLOADING.waiting())
        assertFalse(ItemState.REMOTE_TRANSCRIBING.waiting())
        assertFalse(ItemState.REMOTE_TRANSCRIBING.failing())
    }
}
