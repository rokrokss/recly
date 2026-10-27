package app.recly.windows.ui

import app.recly.windows.SilentLogger
import app.recly.windows.core.AppModule
import app.recly.windows.i18n.Str
import app.recly.windows.record.Completion
import app.recly.windows.record.FillAsk
import app.recly.windows.record.completeInOrder
import app.recly.windows.record.fillAsk
import app.recly.windows.record.fillTitleBoxEnabled
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import recly.core.chatgpt.FillNeeds
import recly.core.chatgpt.FillResult
import recly.core.chatgpt.FillRun
import recly.core.job.EnqueueResult
import recly.core.message.CoreMessage

/**
 * docs/08 "Titles and speaker names" on the PC: why More → Fill in title and Fill in speaker names wait, each in the
 * order the reasons are checked; what each answer says under the header; what the title dialog's two boxes ask for;
 * its Save — the name, the fill request, the job, in that order; and each box starting as it was left.
 */
class FillTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-x", "GPT X")), "gpt-x")

    private fun blocked(
        half: FillHalf,
        writing: Boolean = false,
        hasTranscript: Boolean = true,
        busy: Str? = null,
        connection: ChatGptConnection = signedIn,
        running: Boolean = false,
        needed: Boolean = true,
    ) = fillBlocked(half, writing, hasTranscript, busy, connection, running, needed)

    @Test
    fun `each item says the one reason it cannot run, in order`() {
        FillHalf.entries.forEach { half ->
            assertNull(blocked(half))
            assertEquals(Str.DETAIL_STILL_RECORDING,
                blocked(half, writing = true, hasTranscript = false, connection = ChatGptConnection.SignedOut, running = true, needed = false))
            assertEquals(Str.DETAIL_NO_TRANSCRIPT,
                blocked(half, hasTranscript = false, busy = Str.DETAIL_TRANSCRIBING, connection = ChatGptConnection.SignedOut))
            // Any unsettled job, with that job's reason, as Edit transcript gives it.
            assertEquals(Str.DETAIL_NOT_UPLOADED, blocked(half, busy = Str.DETAIL_NOT_UPLOADED, connection = ChatGptConnection.SignedOut, needed = false))
            assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(half, connection = ChatGptConnection.SignedOut, running = true, needed = false))
            assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(half, connection = ChatGptConnection.Expired("me@example.com")))
            // A fill running may be about to fill what is missing: it says it runs, not that nothing is left.
            assertEquals(Str.FILL_RUNNING, blocked(half, running = true, needed = false))
        }
        assertEquals(Str.FILL_HAS_TITLE, blocked(FillHalf.TITLE, needed = false))
        assertEquals(Str.FILL_NO_UNNAMED, blocked(FillHalf.NAMES, needed = false))
    }

    @Test
    fun `a run is the item's only while it asks for that half`() {
        val title = FillRun("r", title = true, names = false)
        val names = FillRun("r", title = false, names = true)
        assertTrue(FillHalf.TITLE.runs(title))
        assertFalse(FillHalf.NAMES.runs(title))
        assertFalse(FillHalf.TITLE.runs(names))
        assertTrue(FillHalf.NAMES.runs(names))
        assertTrue(FillHalf.TITLE.runs(FillRun("r", true, true)) && FillHalf.NAMES.runs(FillRun("r", true, true)))
        assertFalse(FillHalf.TITLE.runs(null) || FillHalf.NAMES.runs(null))
        val lacking = FillNeeds(title = false, speakers = listOf("S1"))
        assertFalse(FillHalf.TITLE.needed(lacking))
        assertTrue(FillHalf.NAMES.needed(lacking))
        assertTrue(FillHalf.TITLE.needed(FillNeeds(title = true, speakers = emptyList())))
        assertFalse(FillHalf.NAMES.needed(FillNeeds(title = true, speakers = emptyList())))
    }

    @Test
    fun `what arrived is seen where it goes, and the half asked missing is that half's failure`() {
        assertNull(fillNotice(FillResult.Filled(title = true, names = 0), FillHalf.TITLE, Str.DETAIL_TRANSCRIBING))
        assertNull(fillNotice(FillResult.Filled(title = false, names = 2), FillHalf.NAMES, Str.DETAIL_TRANSCRIBING))
        // ChatGPT answered without what was asked: that half's failure line alone.
        assertEquals(FillNotice.Failed(FillHalf.TITLE, null), fillNotice(FillResult.Filled(title = false, names = 2), FillHalf.TITLE, Str.DETAIL_TRANSCRIBING))
        assertEquals(FillNotice.Failed(FillHalf.NAMES, null), fillNotice(FillResult.Filled(title = true, names = 0), FillHalf.NAMES, Str.DETAIL_TRANSCRIBING))
        assertEquals(Str.FILL_TITLE_FAILED, FillHalf.TITLE.failed)
        assertEquals(Str.FILL_NAMES_FAILED, FillHalf.NAMES.failed)
    }

    @Test
    fun `nothing to fill says the item's own reason`() {
        assertEquals(FillNotice.Refused(Str.FILL_HAS_TITLE), fillNotice(FillResult.NothingToFill, FillHalf.TITLE, Str.DETAIL_TRANSCRIBING))
        assertEquals(FillNotice.Refused(Str.FILL_NO_UNNAMED), fillNotice(FillResult.NothingToFill, FillHalf.NAMES, Str.DETAIL_TRANSCRIBING))
    }

    @Test
    fun `a refusal is the menu's own reason, and a failure carries the core's`() {
        FillHalf.entries.forEach { half ->
            assertEquals(FillNotice.Refused(Str.DRIVE_PENDING), fillNotice(FillResult.Busy, half, Str.DRIVE_PENDING))
            assertEquals(FillNotice.Refused(Str.DETAIL_NO_TRANSCRIPT), fillNotice(FillResult.NoTranscript, half, Str.DETAIL_TRANSCRIBING))
            val limit = CoreMessage.CHATGPT_USAGE_LIMIT.code()
            assertEquals(FillNotice.Failed(half, limit), fillNotice(FillResult.Failed(limit), half, Str.DETAIL_TRANSCRIBING))
            // A call that threw is said too, without a reason of its own.
            assertEquals(FillNotice.Failed(half, null), fillNotice(null, half, Str.DETAIL_TRANSCRIBING))
        }
    }

    @Test
    fun `the title box is disabled while the field has a title`() {
        assertTrue(fillTitleBoxEnabled(""))
        assertTrue(fillTitleBoxEnabled("  "))
        assertFalse(fillTitleBoxEnabled("Weekly sync"))
    }

    @Test
    fun `the title box asks only while there is no title, and either box alone asks for its half`() {
        assertEquals(FillAsk(title = true, names = false), fillAsk(true, "", titleBox = true, namesBox = false))
        assertEquals(FillAsk(title = false, names = true), fillAsk(true, "", titleBox = false, namesBox = true))
        assertEquals(FillAsk(title = true, names = true), fillAsk(true, " ", titleBox = true, namesBox = true))
        // A typed title is never replaced: the title box does nothing, the names box still asks.
        assertNull(fillAsk(true, "Weekly sync", titleBox = true, namesBox = false))
        assertEquals(FillAsk(title = false, names = true), fillAsk(true, "Weekly sync", titleBox = true, namesBox = true))
        assertNull(fillAsk(true, "", titleBox = false, namesBox = false))
        // Boxes that were not there ask for nothing, whatever they were left as.
        assertNull(fillAsk(false, "", titleBox = true, namesBox = true))
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
    fun `unticked boxes ask for nothing, and a request that could not be kept costs the fill only`() = runBlocking {
        val calls = mutableListOf<String>()
        completeInOrder({ calls += "name"; true }, null, { calls += "enqueue"; EnqueueResult.Enqueued("j") }, { calls += "failed" })
        assertEquals(listOf("name", "enqueue"), calls)
        calls.clear()
        val completion = completeInOrder({ true }, { error("disk") }, { calls += "enqueue"; EnqueueResult.Enqueued("j") }, { calls += "failed" })
        assertEquals(listOf("failed", "enqueue"), calls)
        assertEquals(Completion(EnqueueResult.Enqueued("j")), completion)
    }

    @Test
    fun `each of the dialog's boxes starts as it was left last, on its own`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val graph = AppModule.build(dataDir = Files.createTempDirectory("recly-fill").toString().toPath())
        fun viewModel() = ChatGptViewModel(graph.core.chatGpt, graph.core.summaries, graph.core.notes, graph.loopback, scope, SilentLogger).also { it.start() }
        suspend fun until(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }
        try {
            val stored = graph.core.summaries.preferences()
            assertFalse(stored.fillTitleAfterStop || stored.fillNamesAfterStop, "unticked at first")
            val first = viewModel()
            first.rememberFillAfterStop(FillHalf.NAMES, true)
            until { graph.core.summaries.preferences().fillNamesAfterStop && first.preferences.fillNamesAfterStop }
            assertFalse(graph.core.summaries.preferences().fillTitleAfterStop, "the names box leaves the title box as it was")
            // The next dialog reads them from what this PC kept.
            val next = viewModel()
            until { next.preferences.fillNamesAfterStop }
            assertFalse(next.preferences.fillTitleAfterStop)
            next.rememberFillAfterStop(FillHalf.TITLE, true)
            until { graph.core.summaries.preferences().fillTitleAfterStop }
            next.rememberFillAfterStop(FillHalf.NAMES, false)
            until { !graph.core.summaries.preferences().fillNamesAfterStop }
            assertTrue(graph.core.summaries.preferences().fillTitleAfterStop)
        } finally {
            scope.cancel()
        }
    }
}
