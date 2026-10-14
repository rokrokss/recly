package app.recly.android.ui

import app.recly.android.R
import app.recly.android.core.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.job.EnqueueResult

/**
 * D4 and D2 on the phone: what a start asks first, and what the line under the record node says after Save.
 * The ViewModel itself needs the core and a `DataStore`, so the decisions it makes are checked as the pure steps
 * it applies.
 */
class StartFlowTest {

    @Test
    fun `a start with the reminder due asks the question and not yet the permissions`() {
        val asked = RecordingUiState().startAsked(askConsent = true)

        assertTrue(asked.consentPrompt)
        assertFalse(asked.askPermissions, "the system prompts wait for the answer")
    }

    @Test
    fun `a start with the reminder settled goes straight to the permissions`() {
        val asked = RecordingUiState().startAsked(askConsent = false)

        assertFalse(asked.consentPrompt)
        assertTrue(asked.askPermissions)
    }

    @Test
    fun `yes to the question goes on to the permissions`() {
        val answered = RecordingUiState().startAsked(askConsent = true).consentAnswered(confirmed = true)

        assertFalse(answered.consentPrompt)
        assertTrue(answered.askPermissions)
    }

    @Test
    fun `cancel ends the start before any permission prompt`() {
        val answered = RecordingUiState().startAsked(askConsent = true).consentAnswered(confirmed = false)

        assertFalse(answered.consentPrompt)
        assertFalse(answered.askPermissions)
    }

    @Test
    fun `an enqueue that threw says the job could not be created`() {
        assertEquals(UiMessage.Res(R.string.enqueue_failed), enqueueMessage(null))
    }

    @Test
    fun `what the queue did is said in its own words, and a queued recording says nothing`() {
        assertEquals(UiMessage.Res(R.string.enqueue_skipped_short), enqueueMessage(EnqueueResult.SkippedShort("j")))
        assertEquals(UiMessage.Res(R.string.enqueue_no_workflow), enqueueMessage(EnqueueResult.NoWorkflow))
        assertEquals(UiMessage.Res(R.string.enqueue_parts_purged), enqueueMessage(EnqueueResult.PartsPurged))
        assertEquals(UiMessage.Res(R.string.enqueue_already_done), enqueueMessage(EnqueueResult.AlreadyDone("j")))
        assertNull(enqueueMessage(EnqueueResult.Enqueued("j")))
    }
}
