package app.recly.windows.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.chatgpt.AskAnswer
import recly.core.chatgpt.AskChanges
import recly.core.chatgpt.AskRequest
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptConnection
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind

/**
 * docs/09 "Ask" on the PC: where the composer is, when it sends, how long a request can be, what the line under an answer
 * says it changed, which view the changes show, what a failed Undo says, and the summary's words an answer changed.
 */
class AskComposerTest {

    private val en = StringTable.of(StringTable.BASE)
    private val ko = StringTable.of(StringTable.KOREAN)
    private val signedIn = ChatGptConnection.SignedIn("me@example.com", emptyList(), "gpt-5.4")

    @Test
    fun `the composer is there where ChatGPT is offered over a transcript, and not over an editor or the memo`() {
        assertTrue(showsAsk(signedIn, loading = false, editing = false, memoShown = false, hasTranscript = true))
        // Signed out it is still there: its field says the sign-in is needed.
        assertTrue(showsAsk(ChatGptConnection.SignedOut, loading = false, editing = false, memoShown = false, hasTranscript = true))
        assertFalse(showsAsk(ChatGptConnection.Unavailable, loading = false, editing = false, memoShown = false, hasTranscript = true))
        assertFalse(showsAsk(null, loading = false, editing = false, memoShown = false, hasTranscript = true))
        assertFalse(showsAsk(signedIn, loading = true, editing = false, memoShown = false, hasTranscript = true))
        assertFalse(showsAsk(signedIn, loading = false, editing = true, memoShown = false, hasTranscript = true))
        assertFalse(showsAsk(signedIn, loading = false, editing = false, memoShown = true, hasTranscript = true))
        assertFalse(showsAsk(signedIn, loading = false, editing = false, memoShown = false, hasTranscript = false))
    }

    @Test
    fun `an answer scrolls past a third of the detail, and never past 320`() {
        assertEquals(240.dp, answerHeight(720.dp))
        assertEquals(320.dp, answerHeight(1200.dp))
        // Before the detail has been measured.
        assertEquals(320.dp, answerHeight(0.dp))
    }

    @Test
    fun `Send sends words, and only while Ask can run`() {
        assertTrue(asksNow("When do we ship?", running = false, reason = null))
        assertFalse(asksNow("  \n ", running = false, reason = null))
        assertFalse(asksNow("When do we ship?", running = true, reason = null))
        assertFalse(asksNow("When do we ship?", running = false, reason = en[Str.CORE_CHATGPT_SIGN_IN_REQUIRED]))
    }

    @Test
    fun `a request is cut at what the core takes, the cursor kept inside it`() {
        val short = TextFieldValue("Mina is Minna", TextRange(4))
        assertEquals(short, capped(short))
        val long = capped(TextFieldValue("a".repeat(AskRequest.MAX + 10), TextRange(AskRequest.MAX + 10)))
        assertEquals(AskRequest.MAX, long.text.length)
        assertEquals(TextRange(AskRequest.MAX), long.selection)
    }

    @Test
    fun `the changes line names only what changed`() {
        val both = AskChanges(segments = listOf(1, 4), names = 0, items = listOf("d1"), removed = 0, overview = false)
        assertEquals("Changed: Transcript 2 · Summary 1", askChangesLine(both, en))
        assertEquals("바뀐 곳: 전사본 2 · 요약 1", askChangesLine(both, ko).plain())
        // A speaker named counts as a place in the transcript; an item removed and the overview as places in the summary.
        assertEquals("Changed: Transcript 1", askChangesLine(AskChanges(emptyList(), 1, emptyList(), 0, false), en))
        assertEquals("Changed: Summary 2", askChangesLine(AskChanges(emptyList(), 0, emptyList(), 1, true), en))
    }

    @Test
    fun `changes to one of the two show it`() {
        val transcript = AskChanges(listOf(3), 0, emptyList(), 0, false)
        val summary = AskChanges(emptyList(), 0, listOf("t1"), 0, false)
        val both = AskChanges(listOf(3), 0, listOf("t1"), 0, false)
        assertEquals(DetailView.TRANSCRIPT, askChangesView(transcript, hasNotes = true))
        assertEquals(DetailView.NOTES, askChangesView(summary, hasNotes = true))
        // The summary emptied by its changes: nothing to show.
        assertNull(askChangesView(summary, hasNotes = false))
        assertNull(askChangesView(both, hasNotes = true))
    }

    @Test
    fun `the answer's changes are the detail's while it is ready`() {
        val changes = AskChanges(listOf(0), 0, emptyList(), 0, false)
        val answer = AskAnswer("r1", null, "Mina is Minna", "", "gpt-5.4", changes = changes)
        assertEquals(changes, RecordingDetail("r1", app.recly.windows.i18n.UiMessage.Text("Weekly"), ask = AskState.Ready(answer)).askChanges)
        assertNull(RecordingDetail("r1", app.recly.windows.i18n.UiMessage.Text("Weekly"), ask = AskState.Running(null, "Mina is Minna")).askChanges)
        assertNull(RecordingDetail("r1", app.recly.windows.i18n.UiMessage.Text("Weekly"), ask = AskState.Ready(answer.copy(changes = null))).askChanges)
    }

    @Test
    fun `an Undo that could not undo it all says so, and one that did says nothing`() {
        assertNull(askUndoNotice(undone = true))
        assertEquals(Str.ASK_UNDO_FAILED, askUndoNotice(undone = false))
        assertEquals("Could not undo", en[Str.ASK_UNDO_FAILED])
        assertEquals("되돌리지 못했습니다", ko[Str.ASK_UNDO_FAILED].plain())
    }

    @Test
    fun `the summary tints the words an answer changed, and not the deadline`() {
        val item = NoteItem("t1", NoteKind.TODO, "Send the quote", "Minji", "Friday", 75.0, "", provisional = false)
        val tinted = noteText(item, Color.Gray, Color.Red)
        assertEquals("Send the quote — Minji · Friday", tinted.text)
        val background = tinted.spanStyles.single { it.item.background == Color.Red }
        assertEquals(0 to "Send the quote — Minji".length, background.start to background.end)
        assertTrue(noteText(item, Color.Gray).spanStyles.none { it.item.background != Color.Unspecified })
    }

    @Test
    fun `the copy is the spec's`() {
        assertEquals("Ask, or say what to change", en[Str.ASK_PLACEHOLDER])
        assertEquals("Send", en[Str.ASK_SEND])
        assertEquals("Nothing changed", en[Str.ASK_NOTHING])
        assertEquals("Working…", en[Str.ASK_WORKING])
        assertEquals("질문하거나 바꿀 내용을 적어 주세요", ko[Str.ASK_PLACEHOLDER].plain())
        assertEquals("보내기", ko[Str.ASK_SEND].plain())
        assertEquals("바뀐 내용 없음", ko[Str.ASK_NOTHING].plain())
    }
}
