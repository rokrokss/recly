package app.recly.android.ui

import app.recly.android.R
import app.recly.android.core.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.FillNeeds
import recly.core.chatgpt.FillResult
import recly.core.chatgpt.FillRun
import recly.core.job.EnqueueResult
import recly.core.message.CoreMessage

/**
 * docs/08 "Titles and speaker names" on the phone: why More → Fill in title and Fill in speaker names wait, each in
 * the order the reasons are checked; what each answer says under the header; what the title sheet's two boxes ask
 * for; and its Save — the title, the fill request, the job, in that order.
 */
class FillTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5", "GPT-5")), "gpt-5")

    private fun reason(
        half: FillHalf,
        hasTranscript: Boolean = true,
        transcribing: Boolean = false,
        busyReason: Int = R.string.detail_transcribing,
        connection: ChatGptConnection = signedIn,
        running: Boolean = false,
        needed: Boolean = true,
    ) = fillReason(half, hasTranscript, transcribing, busyReason, connection, running, needed)

    @Test
    fun `each item says the one reason it cannot run, in order`() {
        FillHalf.entries.forEach { half ->
            assertNull(reason(half))
            assertEquals(R.string.detail_no_transcript,
                reason(half, hasTranscript = false, transcribing = true, connection = ChatGptConnection.SignedOut, running = true, needed = false))
            // Any unsettled job, in the words the menu already uses for it.
            assertEquals(R.string.detail_not_uploaded,
                reason(half, transcribing = true, busyReason = R.string.detail_not_uploaded, connection = ChatGptConnection.SignedOut, needed = false))
            assertEquals(R.string.core_chatgpt_sign_in_required, reason(half, connection = ChatGptConnection.SignedOut, running = true, needed = false))
            assertEquals(R.string.core_chatgpt_sign_in_required, reason(half, connection = ChatGptConnection.Expired("me@example.com")))
            // A fill running may be about to fill what is missing: it says it runs, not that nothing is left.
            assertEquals(R.string.fill_running, reason(half, running = true, needed = false))
        }
        assertEquals(R.string.fill_has_title, reason(FillHalf.TITLE, needed = false))
        assertEquals(R.string.fill_no_unnamed, reason(FillHalf.NAMES, needed = false))
    }

    @Test
    fun `a run is the item's only while it asks for that half`() {
        val title = FillRun("r", title = true, names = false)
        val names = FillRun("r", title = false, names = true)
        val both = FillRun("r", title = true, names = true)
        assertTrue(FillHalf.TITLE.runs(title))
        assertFalse(FillHalf.NAMES.runs(title))
        assertFalse(FillHalf.TITLE.runs(names))
        assertTrue(FillHalf.NAMES.runs(names))
        assertTrue(FillHalf.TITLE.runs(both) && FillHalf.NAMES.runs(both))
        assertFalse(FillHalf.TITLE.runs(null) || FillHalf.NAMES.runs(null))
        // What each item is offered by.
        val lacking = FillNeeds(title = false, speakers = listOf("S1"))
        assertFalse(FillHalf.TITLE.needed(lacking))
        assertTrue(FillHalf.NAMES.needed(lacking))
        assertTrue(FillHalf.TITLE.needed(FillNeeds(title = true, speakers = emptyList())))
        assertFalse(FillHalf.NAMES.needed(FillNeeds(title = true, speakers = emptyList())))
    }

    @Test
    fun `what arrived is seen where it goes, and the half asked missing is that half's failure`() {
        assertNull(fillNotice(FillResult.Filled(title = true, names = 0), FillHalf.TITLE, R.string.detail_transcribing))
        assertNull(fillNotice(FillResult.Filled(title = false, names = 2), FillHalf.NAMES, R.string.detail_transcribing))
        // ChatGPT answered without what was asked: that half's failure line alone.
        assertEquals(FillNotice.Failed(FillHalf.TITLE, null),
            fillNotice(FillResult.Filled(title = false, names = 2), FillHalf.TITLE, R.string.detail_transcribing))
        assertEquals(FillNotice.Failed(FillHalf.NAMES, null),
            fillNotice(FillResult.Filled(title = true, names = 0), FillHalf.NAMES, R.string.detail_transcribing))
        assertEquals(R.string.fill_title_failed, FillHalf.TITLE.failed)
        assertEquals(R.string.fill_names_failed, FillHalf.NAMES.failed)
    }

    @Test
    fun `nothing to fill says the item's own reason`() {
        assertEquals(FillNotice.Refused(R.string.fill_has_title), fillNotice(FillResult.NothingToFill, FillHalf.TITLE, R.string.detail_transcribing))
        assertEquals(FillNotice.Refused(R.string.fill_no_unnamed), fillNotice(FillResult.NothingToFill, FillHalf.NAMES, R.string.detail_transcribing))
    }

    @Test
    fun `a refusal is the menu's own reason, and a failure carries the core's`() {
        FillHalf.entries.forEach { half ->
            assertEquals(FillNotice.Refused(R.string.detail_not_uploaded), fillNotice(FillResult.Busy, half, R.string.detail_not_uploaded))
            assertEquals(FillNotice.Refused(R.string.detail_no_transcript), fillNotice(FillResult.NoTranscript, half, R.string.detail_transcribing))
            val limit = CoreMessage.CHATGPT_USAGE_LIMIT.code()
            assertEquals(FillNotice.Failed(half, limit), fillNotice(FillResult.Failed(limit), half, R.string.detail_transcribing))
            // A call that threw is said too, without a reason of its own.
            assertEquals(FillNotice.Failed(half, null), fillNotice(null, half, R.string.detail_transcribing))
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
    fun `unticked boxes ask for nothing`() = runBlocking {
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
