package app.recly.android.ui

import app.recly.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.Summary
import recly.core.chatgpt.SummaryState
import recly.core.message.CoreMessage
import recly.core.storage.StorageKind

/**
 * docs/09 "Detail header and More menu" · "Summary view": why Summarize waits, in the order the reasons are
 * checked; when it says "again"; when the Transcript | Summary chips are there; what a failed summary offers; and the
 * summary editor's item, question, note and draft.
 */
class SummaryMenuTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5", "GPT-5")), "gpt-5")
    private val summary = Summary("r1", "Summary\n- one", "gpt-5", "2026-10-09T10:00:00Z")

    @Test
    fun `no transcript comes first`() {
        assertEquals(R.string.detail_no_transcript,
            summarizeReason(false, transcribing = true, busyReason = R.string.detail_transcribing, ChatGptConnection.SignedOut, SummaryState.Running(null)))
    }

    @Test
    fun `a transcription on its way says what the menu already says about it`() {
        assertEquals(R.string.detail_transcribing, summarizeReason(true, true, R.string.detail_transcribing, ChatGptConnection.SignedOut, SummaryState.None))
        assertEquals(R.string.detail_not_uploaded, summarizeReason(true, true, R.string.detail_not_uploaded, signedIn, SummaryState.None))
    }

    @Test
    fun `signed out or expired sends the user to Settings`() {
        assertEquals(R.string.core_chatgpt_sign_in_required, summarizeReason(true, false, R.string.detail_transcribing, ChatGptConnection.SignedOut, SummaryState.None))
        assertEquals(R.string.core_chatgpt_sign_in_required,
            summarizeReason(true, false, R.string.detail_transcribing, ChatGptConnection.Expired("me@example.com"), SummaryState.Ready(summary)))
    }

    @Test
    fun `a summary already being written says so`() {
        assertEquals(R.string.summary_running, summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Running(summary)))
    }

    @Test
    fun `signed in with a transcript, it can run - again after a summary or a failure`() {
        assertNull(summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.None))
        assertNull(summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Ready(summary)))
        assertNull(summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), null)))
    }

    @Test
    fun `Summarize again once there is a summary to replace`() {
        assertFalse(hasSummary(SummaryState.None))
        assertFalse(hasSummary(SummaryState.Running(null)))
        assertFalse(hasSummary(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null)))
        assertTrue(hasSummary(SummaryState.Ready(summary)))
        assertTrue(hasSummary(SummaryState.Running(summary)))
        assertTrue(hasSummary(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
    }

    @Test
    fun `the chips are there for a summary or one just asked for, never while editing`() {
        assertFalse(showsSummaryChips(SummaryState.None, asked = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.None, asked = true, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Ready(summary), asked = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null), asked = false, editing = false))
        assertFalse(showsSummaryChips(SummaryState.Ready(summary), asked = true, editing = true))
    }

    @Test
    fun `a failed summary offers the one button that helps`() {
        assertEquals(SummaryRecovery.MANAGE_USAGE, summaryRecovery(CoreMessage.CHATGPT_USAGE_LIMIT.code()))
        assertEquals(SummaryRecovery.NONE, summaryRecovery(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()))
        assertEquals(SummaryRecovery.RETRY, summaryRecovery(CoreMessage.CHATGPT_PLAN_REQUIRED.code()))
        assertEquals(SummaryRecovery.RETRY, summaryRecovery(CoreMessage.PROVIDER_ERROR.code(detail = "500")))
        assertEquals(SummaryRecovery.RETRY, summaryRecovery("not a key"))
    }

    @Test
    fun `Edit summary is there once there is a summary, and waits while a new one is written`() {
        val edited = summary.copy(editedAt = "2026-10-09T11:00:00Z")
        assertNull(savedSummary(SummaryState.None))
        assertNull(savedSummary(SummaryState.Running(null)))
        assertEquals(summary, savedSummary(SummaryState.Ready(summary)))
        assertEquals(edited, savedSummary(SummaryState.Running(edited)))
        assertEquals(summary, savedSummary(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
        assertNull(editSummaryReason(SummaryState.Ready(summary)))
        assertNull(editSummaryReason(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
        assertEquals(R.string.summary_running, editSummaryReason(SummaryState.Running(summary)))
    }

    @Test
    fun `Summarize again asks first only over a summary the user edited`() {
        val edited = summary.copy(editedAt = "2026-10-09T11:00:00Z")
        assertFalse(asksBeforeReplacing(SummaryState.None))
        assertFalse(asksBeforeReplacing(SummaryState.Ready(summary)))
        assertTrue(asksBeforeReplacing(SummaryState.Ready(edited)))
        assertTrue(asksBeforeReplacing(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), edited)))
        assertFalse(asksBeforeReplacing(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null)))
    }

    @Test
    fun `the editor's note says the summary travels only with Drive or iCloud`() {
        assertEquals(R.string.summary_edit_note_shared, summaryEditNote(StorageKind.DRIVE))
        assertEquals(R.string.summary_edit_note_shared, summaryEditNote(StorageKind.ICLOUD))
        assertEquals(R.string.summary_edit_note_local, summaryEditNote(StorageKind.FOLDER))
        // Not uploaded yet: there is no folder for it to go to.
        assertEquals(R.string.summary_edit_note_local, summaryEditNote(null))
    }

    @Test
    fun `a draft has changed only when Save would write it`() {
        val draft = SummaryDraft.of(summary)
        assertFalse(draft.changed)
        assertTrue(draft.copy(text = "Summary\n- two").changed)
        // The core trims, and keeps the summary for blank text.
        assertFalse(draft.copy(text = "  ${summary.text}\n").changed)
        assertFalse(draft.copy(text = "   ").changed)
    }

    @Test
    fun `four reasons are said in their own words, any other as a failure with its detail`() {
        assertEquals(CoreMessage.CHATGPT_USAGE_LIMIT, spokenReason(CoreMessage.CHATGPT_USAGE_LIMIT.code()))
        assertEquals(CoreMessage.PROVIDER_REGION_RESTRICTED, spokenReason(CoreMessage.PROVIDER_REGION_RESTRICTED.code()))
        assertNull(spokenReason(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")))
        assertEquals("state mismatch", reasonDetail(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")))
        assertEquals("no transcript", reasonDetail(CoreMessage.STEP_FAILED.code("no transcript")))
        assertEquals("as it came", reasonDetail("as it came"))
    }
}
