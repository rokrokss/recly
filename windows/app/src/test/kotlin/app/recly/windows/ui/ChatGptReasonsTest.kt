package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
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

/**
 * docs/09 "Summary view" · "Recording detail": what the More menu's Summarize says, when the Transcript | Summary
 * chips are there, and how a failed sign-in or summary is worded.
 */
class ChatGptReasonsTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-x", "GPT X")), "gpt-x")
    private val summary = Summary("r1", "- one\n- two", "gpt-x", "2026-10-09T10:00:00Z")

    @Test
    fun `Summarize says the one reason it cannot run, in order`() {
        fun blocked(
            writing: Boolean = false,
            hasTranscript: Boolean = true,
            transcriptionRunning: Boolean = false,
            connection: ChatGptConnection = signedIn,
            summary: SummaryState = SummaryState.None,
        ) = summarizeBlocked(writing, hasTranscript, transcriptionRunning, connection, summary)

        assertNull(blocked())
        // A take still being written has no transcript either; it says what it is first, as Rename does.
        assertEquals(Str.DETAIL_STILL_RECORDING, blocked(writing = true, hasTranscript = false, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.DETAIL_NO_TRANSCRIPT, blocked(hasTranscript = false, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.DETAIL_TRANSCRIBING, blocked(transcriptionRunning = true, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.SignedOut))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.Expired("me@example.com")))
        assertEquals(Str.SUMMARY_RUNNING, blocked(summary = SummaryState.Running(previous = null)))
        // A failed or finished summary can be asked for again.
        assertNull(blocked(summary = SummaryState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), previous = null)))
        assertNull(blocked(summary = SummaryState.Ready(summary)))
        // The reason is the core's own sentence for it.
        assertEquals("Sign in to ChatGPT in Settings", StringTable.of(StringTable.BASE)[Str.CORE_CHATGPT_SIGN_IN_REQUIRED])
    }

    @Test
    fun `Summarize again once there is a summary to replace`() {
        assertEquals(Str.SUMMARY_SUMMARIZE, summarizeLabel(SummaryState.None))
        assertEquals(Str.SUMMARY_SUMMARIZE, summarizeLabel(SummaryState.Running(previous = null)))
        assertEquals(Str.SUMMARY_SUMMARIZE, summarizeLabel(SummaryState.Failed("PROVIDER_ERROR", previous = null)))
        assertEquals(Str.SUMMARY_AGAIN, summarizeLabel(SummaryState.Ready(summary)))
        assertEquals(Str.SUMMARY_AGAIN, summarizeLabel(SummaryState.Running(previous = summary)))
        assertEquals(Str.SUMMARY_AGAIN, summarizeLabel(SummaryState.Failed("PROVIDER_ERROR", previous = summary)))
    }

    @Test
    fun `the chips are there once there is a summary or one was asked for, and never over the editor`() {
        assertFalse(showsSummaryChips(SummaryState.None, summaryChosen = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.None, summaryChosen = true, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Ready(summary), summaryChosen = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Failed("PROVIDER_ERROR", null), summaryChosen = false, editing = false))
        assertFalse(showsSummaryChips(SummaryState.Ready(summary), summaryChosen = true, editing = true))
    }

    @Test
    fun `a failed summary is its own sentence where it has one, and Could not summarize otherwise`() {
        val limit = summaryFailure(CoreMessage.CHATGPT_USAGE_LIMIT.code())
        assertEquals(coreSentence(CoreMessage.CHATGPT_USAGE_LIMIT), limit.headline.text(base))
        assertEquals(SummaryRecovery.MANAGE_USAGE, limit.recovery)
        assertNull(limit.detail)

        // The sentence says where to go; there is nothing to press.
        assertEquals(SummaryRecovery.NONE, summaryFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()).recovery)
        assertEquals(SummaryRecovery.RETRY, summaryFailure(CoreMessage.CHATGPT_PLAN_REQUIRED.code()).recovery)
        assertEquals(SummaryRecovery.RETRY, summaryFailure(CoreMessage.PROVIDER_REGION_RESTRICTED.code()).recovery)

        // PROVIDER_ERROR's own sentence promises a retry nothing here makes: the headline is the detail's.
        val provider = summaryFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500: upstream"))
        assertEquals(Str.SUMMARY_FAILED.message(), provider.headline)
        assertEquals("HTTP 500: upstream", provider.detail)
        assertEquals(SummaryRecovery.RETRY, provider.recovery)

        val step = summaryFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        assertEquals(Str.SUMMARY_FAILED.message(), step.headline)
        assertEquals("no transcript", step.detail)
    }

    @Test
    fun `a failed sign-in says the reason's sentence or its diagnostic, and a cancelled one says nothing`() {
        assertTrue(cancelled(CoreMessage.SIGN_IN_CANCELLED.code()))
        assertFalse(cancelled(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")))

        assertEquals(coreSentence(CoreMessage.CHATGPT_PLAN_REQUIRED), signInFailureLine(CoreMessage.CHATGPT_PLAN_REQUIRED.code())?.text(base))
        assertEquals(UiMessage.Text("account changed"), signInFailureLine(CoreMessage.PROVIDER_ERROR.code(detail = "account changed")))
        // A sign-in this shell could not start at all — no browser, no port — is its diagnostic as it came.
        assertEquals(UiMessage.Text("Address already in use"), signInFailureLine("Address already in use"))
        assertNull(signInFailureLine(CoreMessage.PROVIDER_ERROR.code()))
    }

    @Test
    fun `the footer names the model the way the plan does, or by its id`() {
        assertEquals("GPT X", summaryModelLabel("gpt-x", signedIn))
        assertEquals("gpt-old", summaryModelLabel("gpt-old", signedIn))
        assertEquals("gpt-x", summaryModelLabel("gpt-x", ChatGptConnection.SignedOut))
        assertEquals("ChatGPT · GPT X", base[Str.SUMMARY_MODEL, "GPT X"])
    }

    private val base = StringTable.of(StringTable.BASE)

    private fun coreSentence(message: CoreMessage): String =
        base[app.recly.windows.i18n.CoreMessages.keyOf(message), ""]
}
