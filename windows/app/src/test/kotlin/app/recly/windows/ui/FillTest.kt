package app.recly.windows.ui

import app.recly.windows.SilentLogger
import app.recly.windows.core.AppModule
import app.recly.windows.i18n.Str
import app.recly.windows.record.Completion
import app.recly.windows.record.completeInOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.FillResult
import recly.core.job.EnqueueResult
import recly.core.message.CoreMessage

/**
 * docs/08 "Titles and speaker names" on the PC: why More → Fill in title and names waits, in the order the reasons
 * are checked; what each answer says under the header; the title dialog's Save — the name, the fill request, the
 * job, in that order; and the dialog's box starting as it was left.
 */
class FillTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-x", "GPT X")), "gpt-x")

    private fun blocked(
        writing: Boolean = false,
        hasTranscript: Boolean = true,
        busy: Str? = null,
        connection: ChatGptConnection = signedIn,
        filling: Boolean = false,
        needed: Boolean = true,
    ) = fillBlocked(writing, hasTranscript, busy, connection, filling, needed)

    @Test
    fun `the item says the one reason it cannot run, in order`() {
        assertNull(blocked())
        assertEquals(Str.DETAIL_STILL_RECORDING,
            blocked(writing = true, hasTranscript = false, connection = ChatGptConnection.SignedOut, filling = true, needed = false))
        assertEquals(Str.DETAIL_NO_TRANSCRIPT, blocked(hasTranscript = false, busy = Str.DETAIL_TRANSCRIBING, connection = ChatGptConnection.SignedOut))
        // Any unsettled job, with that job's reason, as Edit transcript gives it.
        assertEquals(Str.DETAIL_NOT_UPLOADED, blocked(busy = Str.DETAIL_NOT_UPLOADED, connection = ChatGptConnection.SignedOut, needed = false))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.SignedOut, filling = true, needed = false))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.Expired("me@example.com")))
        // A fill running may be about to fill what is missing: it says it runs, not that nothing is left.
        assertEquals(Str.FILL_RUNNING, blocked(filling = true, needed = false))
        assertEquals(Str.FILL_NOTHING, blocked(needed = false))
    }

    @Test
    fun `what arrived is seen where it goes, and only nothing at all is said`() {
        assertNull(fillNotice(FillResult.Filled(title = true, names = 0), Str.DETAIL_TRANSCRIBING))
        assertNull(fillNotice(FillResult.Filled(title = false, names = 2), Str.DETAIL_TRANSCRIBING))
        assertNull(fillNotice(FillResult.NothingToFill, Str.DETAIL_TRANSCRIBING))
        // ChatGPT answered with neither: the failure line alone.
        assertEquals(FillNotice.Failed(null), fillNotice(FillResult.Filled(title = false, names = 0), Str.DETAIL_TRANSCRIBING))
    }

    @Test
    fun `a refusal is the menu's own reason, and a failure carries the core's`() {
        assertEquals(FillNotice.Refused(Str.DRIVE_PENDING), fillNotice(FillResult.Busy, Str.DRIVE_PENDING))
        assertEquals(FillNotice.Refused(Str.DETAIL_NO_TRANSCRIPT), fillNotice(FillResult.NoTranscript, Str.DETAIL_TRANSCRIBING))
        val limit = CoreMessage.CHATGPT_USAGE_LIMIT.code()
        assertEquals(FillNotice.Failed(limit), fillNotice(FillResult.Failed(limit), Str.DETAIL_TRANSCRIBING))
        // A call that threw is said too, without a reason of its own.
        assertEquals(FillNotice.Failed(null), fillNotice(null, Str.DETAIL_TRANSCRIBING))
    }

    @Test
    fun `Save writes the name, then asks for the fill, then makes the job`() = runBlocking {
        val calls = mutableListOf<String>()
        val completion = completeInOrder(
            name = { calls += "name"; false },
            requestFill = { calls += "fill" },
            enqueue = { calls += "enqueue"; EnqueueResult.Enqueued("j") },
            fillFailed = { calls += "failed" },
        )
        assertEquals(listOf("name", "fill", "enqueue"), calls)
        assertEquals(Completion(EnqueueResult.Enqueued("j"), titleTooLate = true), completion)
    }

    @Test
    fun `an unticked box asks for nothing, and a request that could not be kept costs the fill only`() = runBlocking {
        val calls = mutableListOf<String>()
        completeInOrder({ calls += "name"; true }, null, { calls += "enqueue"; EnqueueResult.Enqueued("j") }, { calls += "failed" })
        assertEquals(listOf("name", "enqueue"), calls)
        calls.clear()
        val completion = completeInOrder({ true }, { error("disk") }, { calls += "enqueue"; EnqueueResult.Enqueued("j") }, { calls += "failed" })
        assertEquals(listOf("failed", "enqueue"), calls)
        assertEquals(Completion(EnqueueResult.Enqueued("j")), completion)
    }

    @Test
    fun `the dialog's box starts as it was left last`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val graph = AppModule.build(dataDir = Files.createTempDirectory("recly-fill").toString().toPath())
        fun viewModel() = ChatGptViewModel(graph.core.chatGpt, graph.core.summaries, graph.loopback, scope, SilentLogger).also { it.start() }
        try {
            assertFalse(graph.core.summaries.preferences().fillTitleAfterStop, "unticked at first")
            val first = viewModel()
            first.rememberFillAfterStop(true)
            withTimeout(10_000) { while (!graph.core.summaries.preferences().fillTitleAfterStop || !first.preferences.fillTitleAfterStop) delay(20) }
            // The next dialog reads it from what this PC kept.
            val next = viewModel()
            withTimeout(10_000) { while (!next.preferences.fillTitleAfterStop) delay(20) }
            next.rememberFillAfterStop(false)
            withTimeout(10_000) { while (graph.core.summaries.preferences().fillTitleAfterStop) delay(20) }
        } finally {
            scope.cancel()
        }
    }
}
