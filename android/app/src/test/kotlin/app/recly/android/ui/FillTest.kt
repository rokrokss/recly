package app.recly.android.ui

import app.recly.android.R
import app.recly.android.core.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.FillResult
import recly.core.job.EnqueueResult
import recly.core.message.CoreMessage

/**
 * docs/08 "Titles and speaker names" on the phone: why More → Fill in title and names waits, in the order the reasons
 * are checked; what each answer says under the header; and the title sheet's Save — the title, the fill request, the
 * job, in that order.
 */
class FillTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5", "GPT-5")), "gpt-5")

    private fun reason(
        hasTranscript: Boolean = true,
        transcribing: Boolean = false,
        busyReason: Int = R.string.detail_transcribing,
        connection: ChatGptConnection = signedIn,
        filling: Boolean = false,
        needed: Boolean = true,
    ) = fillReason(hasTranscript, transcribing, busyReason, connection, filling, needed)

    @Test
    fun `the item says the one reason it cannot run, in order`() {
        assertNull(reason())
        assertEquals(R.string.detail_no_transcript,
            reason(hasTranscript = false, transcribing = true, connection = ChatGptConnection.SignedOut, filling = true, needed = false))
        // Any unsettled job, in the words the menu already uses for it.
        assertEquals(R.string.detail_not_uploaded,
            reason(transcribing = true, busyReason = R.string.detail_not_uploaded, connection = ChatGptConnection.SignedOut, needed = false))
        assertEquals(R.string.core_chatgpt_sign_in_required, reason(connection = ChatGptConnection.SignedOut, filling = true, needed = false))
        assertEquals(R.string.core_chatgpt_sign_in_required, reason(connection = ChatGptConnection.Expired("me@example.com")))
        // A fill running may be about to fill what is missing: it says it runs, not that nothing is left.
        assertEquals(R.string.fill_running, reason(filling = true, needed = false))
        assertEquals(R.string.fill_nothing, reason(needed = false))
    }

    @Test
    fun `what arrived is seen where it goes, and only nothing at all is said`() {
        assertNull(fillNotice(FillResult.Filled(title = true, names = 0), R.string.detail_transcribing))
        assertNull(fillNotice(FillResult.Filled(title = false, names = 2), R.string.detail_transcribing))
        assertNull(fillNotice(FillResult.NothingToFill, R.string.detail_transcribing))
        // ChatGPT answered with neither: the failure line alone.
        assertEquals(FillNotice.Failed(null), fillNotice(FillResult.Filled(title = false, names = 0), R.string.detail_transcribing))
    }

    @Test
    fun `a refusal is the menu's own reason, and a failure carries the core's`() {
        assertEquals(FillNotice.Refused(R.string.detail_not_uploaded), fillNotice(FillResult.Busy, R.string.detail_not_uploaded))
        assertEquals(FillNotice.Refused(R.string.detail_no_transcript), fillNotice(FillResult.NoTranscript, R.string.detail_transcribing))
        val limit = CoreMessage.CHATGPT_USAGE_LIMIT.code()
        assertEquals(FillNotice.Failed(limit), fillNotice(FillResult.Failed(limit), R.string.detail_transcribing))
        // A call that threw is said too, without a reason of its own.
        assertEquals(FillNotice.Failed(null), fillNotice(null, R.string.detail_transcribing))
    }

    @Test
    fun `Save writes the title, then asks for the fill, then makes the job`() = runBlocking {
        val calls = mutableListOf<String>()
        val (problem, enqueued) = saveInOrder(
            name = { calls += "title"; null },
            requestFill = { calls += "fill" },
            enqueue = { calls += "enqueue"; EnqueueResult.Enqueued("j") },
            failed = { event, _ -> calls += event },
        )
        assertEquals(listOf("title", "fill", "enqueue"), calls)
        assertNull(problem)
        assertEquals(EnqueueResult.Enqueued("j"), enqueued)
    }

    @Test
    fun `an unticked box asks for nothing`() = runBlocking {
        val calls = mutableListOf<String>()
        val late = UiMessage.Res(R.string.recording_title_too_late)
        val (problem, _) = saveInOrder(
            name = { calls += "title"; late },
            requestFill = null,
            enqueue = { calls += "enqueue"; EnqueueResult.Enqueued("j") },
            failed = { event, _ -> calls += event },
        )
        assertEquals(listOf("title", "enqueue"), calls)
        assertEquals(late, problem)
    }

    @Test
    fun `a request that could not be kept costs the fill, never the job`() = runBlocking {
        val calls = mutableListOf<String>()
        val (_, enqueued) = saveInOrder(
            name = { null },
            requestFill = { error("disk") },
            enqueue = { calls += "enqueue"; EnqueueResult.Enqueued("j") },
            failed = { event, _ -> calls += event },
        )
        assertEquals(listOf("shell.recording.fill.failed", "enqueue"), calls)
        assertEquals(EnqueueResult.Enqueued("j"), enqueued)
    }

    @Test
    fun `a queue that threw is logged and said as no job`() = runBlocking {
        val calls = mutableListOf<String>()
        val (_, enqueued) = saveInOrder(
            name = { null },
            requestFill = null,
            enqueue = { error("db") },
            failed = { event, _ -> calls += event },
        )
        assertEquals(listOf("shell.recording.enqueue.failed"), calls)
        assertNull(enqueued)
    }
}
