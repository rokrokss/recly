@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import app.recly.android.ui.component.BadgeTone
import app.recly.android.ui.component.LedgerStatus
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.message.CoreMessage

/**
 * docs/09 screen principle 2: every row state has a code and a tone, the code is the word the core and the
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
     * UX decisions of 2026-10-08: the badge says every code the ledger can show as a word of the app's
     * language — none falls through to the code itself.
     */
    @Test
    fun `every code the ledger can show has a word`() {
        BADGE_CODES.forEach { code ->
            assertTrue(app.recly.android.ui.component.badgeWord(code) != null, "$code has no word")
        }
        assertEquals(app.recly.android.R.string.drive_pending, app.recly.android.ui.component.badgeWord("NEEDS_AUTH"))
    }

    /** docs/03 "Storage location": a wait for the folder is its own word, in the warning a retry wait is. */
    @Test
    fun `a job waiting for the local folder says so`() {
        val waiting = JobItem(
            recordingId = "r", jobId = "j", title = null, startedAt = "2026-10-08T00:00:00Z", durationSec = 1.0,
            state = ItemState.WAITING, error = CoreMessage.FOLDER_UNAVAILABLE.code(), waitingMinutes = null, link = null, nextRunAt = null,
        )

        assertEquals(LedgerStatus("WAITING_FOLDER", BadgeTone.WARNING), waiting.badge())
        assertEquals(ItemState.WAITING.badge(), waiting.copy(error = null).badge())
    }

    /**
     * docs/03 "Recordings from other devices": a code says what is happening, not where — an upload another device
     * is running is the same word as one of this phone's own. A finalized recording with no job
     * history also shares the completed state. Other states must remain distinct.
     */
    @Test
    fun `two states share a code only when they say the same thing`() {
        val shared = ItemState.entries.groupBy { it.badge().code }.filterValues { it.size > 1 }

        assertEquals(
            mapOf(
                "UPLOADING" to listOf(ItemState.REMOTE_UPLOADING, ItemState.RUNNING),
                "DONE" to listOf(ItemState.NO_JOB, ItemState.DONE),
            ),
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
     * docs/09 screen principle 2: only a recording whose file is still being written — by the recorder, an
     * import or a transfer — withholds Delete. An upload, this device's or another's, offers it:
     * the core stops the upload first, and another device's folder goes from Drive. A recording
     * another device is transcribing has arrived and is a finished row like any other.
     */
    @Test
    fun `only a recording still being written withholds Delete`() {
        assertTrue(ItemState.RECEIVING.inFlight())
        assertFalse(ItemState.REMOTE_UPLOADING.inFlight())
        assertFalse(ItemState.RUNNING.inFlight())
        assertFalse(ItemState.REMOTE_TRANSCRIBING.inFlight())
        assertEquals(
            listOf(
                ItemState.RECORDING,
                ItemState.IMPORTING,
                ItemState.RECEIVING,
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
        assertEquals(BadgeTone.NEUTRAL, ItemState.NEEDS_AUTH.badge().tone)
        assertEquals(BadgeTone.WARNING, ItemState.NEEDS_SPACE.badge().tone)
        assertEquals(BadgeTone.WARNING, ItemState.WAITING.badge().tone)
        // Nothing is wrong and nothing is happening.
        assertEquals(BadgeTone.NEUTRAL, ItemState.PENDING.badge().tone)
        assertEquals(BadgeTone.SUCCESS, ItemState.NO_JOB.badge().tone)
        assertEquals(BadgeTone.NEUTRAL, ItemState.SKIPPED_SHORT.badge().tone)
    }

    /** A recording waiting for the speech model: its own code, in the tone of the other waits. */
    @Test
    fun `waiting for the speech model is its own row state`() {
        assertEquals(LedgerStatus("NEEDS_MODEL", BadgeTone.WARNING), ItemState.NEEDS_MODEL.badge())
        assertEquals(ItemState.NEEDS_CONSENT.badge().tone, ItemState.NEEDS_MODEL.badge().tone)
        assertFalse(ItemState.NEEDS_MODEL.failing(), "a wait, not a failure")
        assertFalse(ItemState.NEEDS_MODEL.inFlight())
    }

    /** An expanded row's reason is red only for a failure; a wait takes its badge's warning. */
    @Test
    fun `only a failure's reason is red`() {
        assertEquals(BadgeTone.DANGER, ItemState.FAILED.reasonTone())
        listOf(ItemState.NEEDS_MODEL, ItemState.NEEDS_CONSENT, ItemState.NEEDS_SPACE, ItemState.WAITING).forEach { state ->
            assertEquals(BadgeTone.WARNING, state.reasonTone(), "$state")
        }
        assertEquals(BadgeTone.NEUTRAL, ItemState.NEEDS_AUTH.reasonTone())
        assertEquals(BadgeTone.NEUTRAL, ItemState.DONE.reasonTone())
    }

    /** The status column is measured with every code the ledger can show, so none is clipped. */
    @Test
    fun `the status column is measured with every code`() {
        assertTrue("NEEDS_MODEL" in BADGE_CODES)
        ItemState.entries.forEach { assertTrue(it.badge().code in BADGE_CODES, "$it") }
    }

    /** docs/03 "Naming rules": an import being transcoded — accent, the square loader, and nothing to do to it yet. */
    @Test
    fun `an import is its own row state, busy and in flight`() {
        assertEquals(LedgerStatus("IMPORTING", BadgeTone.ACCENT, busy = true), ItemState.IMPORTING.badge())
        assertTrue(ItemState.IMPORTING.inFlight())
        assertTrue(ItemState.IMPORTING.waiting())
    }

    /** docs/10 "Drive out of space": its own code, not a FAILED it would be mistaken for. */
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
        assertEquals(8, ItemState.entries.count { it.waiting() })
        assertFalse(ItemState.NO_JOB.waiting())
        assertEquals(3, ItemState.entries.count { it.failing() })
    }

    /**
     * The iPhone's `Recents.summary`: a job parked until the user allows a transfer or downloads
     * the speech model is waiting, not failed — and so is one waiting for Drive (`NEEDS_AUTH`, badge
     * "Upload waiting"), on both phones.
     */
    @Test
    fun `a job parked on the user counts as waiting`() {
        assertTrue(ItemState.NEEDS_CONSENT.waiting())
        assertTrue(ItemState.NEEDS_MODEL.waiting())
        assertTrue(ItemState.NEEDS_AUTH.waiting())
        assertFalse(ItemState.RUNNING.waiting(), "an upload is happening, not waited for")
    }

    /**
     * docs/05 "Fixed processing settings": a transcription on this device is waited for whatever the job's
     * status says while it runs — it does not hold back Delete, and it is not an upload, so it does
     * not lend the Record screen `UPLOADING` (the iPhone's `RecentItem.canDelete`, `Recents.uploading`).
     */
    @Test
    fun `a transcription on this device is waited for and can be deleted`() {
        listOf(ItemState.PENDING, ItemState.RUNNING, ItemState.WAITING).forEach { state ->
            val local = item(state, localPending = true)
            assertTrue(local.waiting(), "$state")
            assertFalse(local.inFlight(), "$state")
        }
        assertFalse(uploading(listOf(item(ItemState.RUNNING, localPending = true))))
        assertFalse(item(ItemState.RUNNING).inFlight(), "an upload is stopped, not waited for")
        assertFalse(item(ItemState.RUNNING).waiting())
    }

    /**
     * docs/03 "Recordings from other devices": a recording still on its way here is one the list is waiting for,
     * whichever device is bringing it. One another device is transcribing has already arrived.
     */
    @Test
    fun `a recording still on its way counts as waiting`() {
        assertTrue(ItemState.RECEIVING.waiting())
        assertTrue(ItemState.REMOTE_UPLOADING.waiting())
        assertFalse(ItemState.REMOTE_TRANSCRIBING.waiting())
        assertFalse(ItemState.REMOTE_TRANSCRIBING.failing())
    }

    /**
     * docs/03 "Storage location": an upload waiting for the local folder is a wait in the warning tone,
     * never a failure, and its row says it waits for the folder rather than when it retries.
     */
    @Test
    fun `a job waiting for the local folder is a wait`() {
        val waiting = item(ItemState.WAITING).copy(error = CoreMessage.FOLDER_UNAVAILABLE.code())
        assertTrue(waiting.waitsForFolder())
        assertTrue(waiting.waiting())
        assertFalse(waiting.state.failing())
        assertEquals(BadgeTone.WARNING, waiting.state.reasonTone())
        assertFalse(item(ItemState.WAITING).waitsForFolder(), "a plain retry backoff")
        assertFalse(
            item(ItemState.WAITING).copy(error = CoreMessage.ICLOUD_UNAVAILABLE.code()).waitsForFolder(),
            "another storage's wait",
        )
    }

    private fun item(state: ItemState, localPending: Boolean = false): JobItem = JobItem(
        recordingId = "01J0${state.name}",
        jobId = "job",
        title = null,
        startedAt = "2026-09-29T09:00:00Z",
        durationSec = 60.0,
        state = state,
        error = null,
        waitingMinutes = null,
        link = null,
        nextRunAt = null,
        localPending = localPending,
        localRunning = localPending && state == ItemState.RUNNING,
    )
}
