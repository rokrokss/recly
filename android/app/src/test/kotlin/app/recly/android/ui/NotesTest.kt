@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import androidx.compose.ui.graphics.Color
import app.recly.android.R
import app.recly.android.work.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.job.StepReport
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Step
import recly.core.model.Track
import recly.core.notes.NoteEdit
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NoteLine
import recly.core.notes.NoteSection
import recly.core.notes.NoteSectionItems
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes
import recly.core.recording.RecordingRecord
import recly.core.storage.StorageKind

/**
 * docs/08 "Organizing" · docs/09 "AI view" on the phone: which groups a recording's notes show and in what order, when
 * there is no block at all, what a row says, which line under it is the note's own, when the chips are there, what Edit
 * notes saves and says, how My sections are saved, that a job with only its notes left is a done row, and that a start
 * from the record screen asks about organizing first — the decisions the screens make, as the pure steps they apply.
 */
class NotesTest {

    @Test
    fun `the groups come as Decisions, To-dos, Questions, and an empty one is left out`() {
        val notes = notes(decisions = listOf(item("d", NoteKind.DECISION)), questions = listOf(item("q", NoteKind.QUESTION)))

        assertEquals(listOf(R.string.notes_decisions, R.string.notes_questions), noteGroups(notes).map { it.header })
        assertEquals(
            listOf(R.string.notes_decisions, R.string.notes_todos, R.string.notes_questions),
            noteGroups(notes.copy(todos = listOf(item("t", NoteKind.TODO)))).map { it.header },
        )
    }

    @Test
    fun `key points come first and the user's sections last, under their own names`() {
        val notes = notes(decisions = listOf(item("d", NoteKind.DECISION)), questions = listOf(item("q", NoteKind.QUESTION))).copy(
            points = listOf(item("p", NoteKind.POINT)),
            sections = listOf(
                NoteSectionItems("s1", "Risks", listOf(item("r", NoteKind.SECTION))),
                NoteSectionItems("s2", "Empty", emptyList()),
                NoteSectionItems("s3", "Budget", listOf(item("b", NoteKind.SECTION))),
            ),
        )

        val groups = noteGroups(notes)
        assertEquals(listOf(R.string.notes_key_points, R.string.notes_decisions, R.string.notes_questions, null, null), groups.map { it.header })
        assertEquals(listOf(null, null, null, "Risks", "Budget"), groups.map { it.name })
        // The record screen has no key points: they come after Stop. The user's sections still follow Questions.
        assertEquals(listOf(R.string.notes_decisions, R.string.notes_questions, null, null), noteGroups(notes, keyPoints = false).map { it.header })
    }

    @Test
    fun `key points alone are notes on the detail, and nothing on the record screen`() {
        val points = notes().copy(points = listOf(item("p", NoteKind.POINT)))

        assertTrue(showsNotes(points, overview = true))
        assertTrue(hasNotes(points))
        assertFalse(showsNotes(points, overview = false))
        assertFalse(hasNotes(notes()))
        assertFalse(hasNotes(null))
    }

    @Test
    fun `the chips are there once there are notes or Organize was asked, never while the transcript is edited`() {
        assertTrue(showsNotesChips(notes = true, asked = false, editing = false))
        assertTrue(showsNotesChips(notes = false, asked = true, editing = false))
        assertFalse(showsNotesChips(notes = false, asked = false, editing = false))
        assertFalse(showsNotesChips(notes = true, asked = true, editing = true))
        // The notes editor's keyboard takes the chips' room until it goes.
        assertFalse(showsNotesChips(notes = true, asked = false, editing = false, typing = true))
    }

    @Test
    fun `Edit notes sends only what changed, and an emptied item to be deleted`() {
        val notes = notes(
            overview = "The team moved the launch.",
            todos = listOf(item("t1", NoteKind.TODO, text = "Send the quote"), item("t2", NoteKind.TODO, text = "Book the room")),
        ).copy(points = listOf(item("p1", NoteKind.POINT, text = "Launch on Friday")))
        val draft = NotesDraft.of(notes)

        assertFalse(draft.changed)
        assertEquals(null, draft.overviewEdit())
        assertEquals(emptyList(), draft.edits())
        // Spaces the core trims away change nothing.
        assertFalse(draft.type("t1", "  Send the quote ").copy(overview = "The team moved the launch. ").changed)

        val edited = draft.type("t1", "Send the signed quote").type("p1", "").copy(overview = "")
        assertTrue(edited.changed)
        assertEquals("", edited.overviewEdit(), "an emptied overview is removed")
        assertEquals(listOf(NoteEdit("p1", ""), NoteEdit("t1", "Send the signed quote")), edited.edits())
    }

    @Test
    fun `notes without an overview send none`() {
        val draft = NotesDraft.of(notes(todos = listOf(item("t", NoteKind.TODO))))

        assertEquals(null, draft.copy(overview = "A new overview").overviewEdit())
    }

    @Test
    fun `the editor's note says the notes travel only with Drive or iCloud`() {
        assertEquals(R.string.notes_edit_note_shared, notesEditNote(StorageKind.DRIVE))
        assertEquals(R.string.notes_edit_note_shared, notesEditNote(StorageKind.ICLOUD))
        assertEquals(R.string.notes_edit_note_local, notesEditNote(StorageKind.FOLDER))
        // Not uploaded yet: there is no folder for them to go to.
        assertEquals(R.string.notes_edit_note_local, notesEditNote(null))
    }

    @Test
    fun `under an item's field, its owner and deadline as they are, or nothing`() {
        assertEquals("Minji · Friday", ownerAndDue(item("t", NoteKind.TODO, owner = "Minji", due = "Friday")))
        assertEquals("Friday", ownerAndDue(item("t", NoteKind.TODO, due = "Friday")))
        assertEquals(null, ownerAndDue(item("t", NoteKind.TODO, owner = " ")))
    }

    @Test
    fun `My sections saves the named ones, in order, and a new one keeps the id it was given`() {
        val drafts = listOf(SectionDraft(0, "s1", " Risks ", "What could slip"), SectionDraft(1, "", "", "half written"), SectionDraft(2, "", "Budget", ""))

        assertEquals(listOf(NoteSection("s1", "Risks", "What could slip"), NoteSection("", "Budget", "")), keptSections(drafts))
        val kept = listOf(NoteSection("s1", "Risks", "What could slip"), NoteSection("new", "Budget", ""))
        assertEquals(listOf("s1", "", "new"), withIds(drafts, sent = listOf(0, 2), kept = kept).map { it.id })
        // A section removed before the save came back is not given anything.
        assertEquals(listOf("s1"), withIds(drafts.take(1), sent = listOf(0, 2), kept = kept).map { it.id })
    }

    @Test
    fun `nothing in the notes is no block at all`() {
        assertFalse(showsNotes(null, overview = true))
        assertFalse(showsNotes(notes(), overview = true))
        // A blank overview is none.
        assertFalse(showsNotes(notes(overview = "  "), overview = true))
    }

    @Test
    fun `an overview alone is a block on the detail, and nothing on the record screen`() {
        val overview = notes(overview = "The team moved the launch to Friday.")

        assertTrue(showsNotes(overview, overview = true))
        assertFalse(showsNotes(overview, overview = false))
        assertTrue(showsNotes(overview.copy(todos = listOf(item("t", NoteKind.TODO))), overview = false))
    }

    @Test
    fun `a row is the item, its owner after a dash, then its deadline`() {
        assertEquals("Send the quote", noteText(item("t", NoteKind.TODO, text = "Send the quote"), Color.Gray).text)
        assertEquals("Send the quote — Minji", noteText(item("t", NoteKind.TODO, text = "Send the quote", owner = "Minji"), Color.Gray).text)
        assertEquals(
            "Send the quote — Minji · Friday",
            noteText(item("t", NoteKind.TODO, text = "Send the quote", owner = "Minji", due = "Friday"), Color.Gray).text,
        )
        assertEquals("Send the quote · Friday", noteText(item("t", NoteKind.TODO, text = "Send the quote", due = "Friday"), Color.Gray).text)
        // docs/09 "Notes view": the deadline is the muted part, as on the iPhone, the Mac and Windows.
        val due = noteText(item("t", NoteKind.TODO, text = "Send the quote", due = "Friday"), Color.Gray).spanStyles.single()
        assertEquals(Color.Gray, due.item.color)
        assertEquals(" · Friday", noteText(item("t", NoteKind.TODO, text = "Send the quote", due = "Friday"), Color.Gray).text.substring(due.start, due.end))
    }

    @Test
    fun `the note's line is the one its quote is in, whatever the case and punctuation`() {
        val lines = listOf(line(300.0, 305.0, "So, the release."), line(305.0, 312.0, "Let's move the launch to Friday, then!"), line(312.0, 318.0, "Fine."))

        assertEquals(1, noteLineIndex(lines, atSec = 312.0, quote = "let's move the launch to friday then"))
    }

    @Test
    fun `without its quote the note's line is the one its time falls in, else the nearest`() {
        val lines = listOf(line(300.0, 305.0, "So, the release."), line(305.0, 312.0, "Something else."), line(330.0, 340.0, "Fine."))

        assertEquals(1, noteLineIndex(lines, atSec = 306.0, quote = "words nobody said"))
        assertEquals(2, noteLineIndex(lines, atSec = 326.0, quote = ""))
        assertEquals(-1, noteLineIndex(emptyList(), atSec = 1.0, quote = "x"))
    }

    /** docs/08 "Organizing": the notes are not the recording's processing — a job waiting on them is a finished row. */
    @Test
    fun `a job with only its notes left reads as done, not waiting`() {
        val job = job("j", JobStatus.WAITING, nextRunAt = null).let {
            it.copy(workflow = it.workflow?.copy(steps = listOf(Step.DriveUpload("upload"), Step.TranscriptPublish("publish"), Step.OrganizeNotes("notes"))))
        }
        val notesLeft = listOf(run("upload", 0, StepStatus.SUCCEEDED), run("publish", 1, StepStatus.SUCCEEDED), run("notes", 2, StepStatus.PENDING))
        val uploadLeft = listOf(run("upload", 0, StepStatus.PENDING), run("publish", 1, StepStatus.PENDING), run("notes", 2, StepStatus.PENDING))

        assertEquals(ItemState.DONE, stateOf(record(), job, StepReport.notesOnly(job.workflow, notesLeft)))
        assertEquals(ItemState.DONE, stateOf(record(), job.copy(status = JobStatus.RUNNING), StepReport.notesOnly(job.workflow, notesLeft)))
        assertEquals(ItemState.WAITING, stateOf(record(), job, StepReport.notesOnly(job.workflow, uploadLeft)))
    }

    @Test
    fun `a start from the record screen asks about organizing first, alone`() {
        val asked = RecordingUiState().startAsked(askConsent = true, askNotes = true)

        assertTrue(asked.notesConsentPrompt)
        assertFalse(asked.consentPrompt, "the reminder waits for this answer")
        assertFalse(asked.askPermissions, "the system prompts wait for both")
    }

    @Test
    fun `either answer goes on with the start, to the reminder when it is due`() {
        val asked = RecordingUiState().startAsked(askConsent = true, askNotes = true)

        val reminder = asked.notesConsentAnswered(askConsent = true)
        assertFalse(reminder.notesConsentPrompt)
        assertTrue(reminder.consentPrompt)
        assertFalse(reminder.askPermissions)

        val prompts = asked.notesConsentAnswered(askConsent = false)
        assertFalse(prompts.notesConsentPrompt)
        assertFalse(prompts.consentPrompt)
        assertTrue(prompts.askPermissions)
    }

    @Test
    fun `a start that need not ask about organizing is the start it was`() {
        assertEquals(RecordingUiState().startAsked(askConsent = false), RecordingUiState().startAsked(askConsent = false, askNotes = false))
        assertFalse(RecordingUiState().startAsked(askConsent = false).notesConsentPrompt)
    }

    private fun notes(
        overview: String? = null,
        decisions: List<NoteItem> = emptyList(),
        todos: List<NoteItem> = emptyList(),
        questions: List<NoteItem> = emptyList(),
    ) = RecordingNotes(ID, NotesStage.FINAL, overview, decisions, todos, questions, organizing = false)

    private fun item(id: String, kind: NoteKind, text: String = "text", owner: String? = null, due: String? = null) =
        NoteItem(id, kind, text, owner, due, atSec = 10.0, quote = "text", provisional = false)

    private fun line(at: Double, end: Double, text: String) = NoteLine(at, end, "S1", null, me = false, text = text, provisional = false)

    private fun run(stepId: String, ordinal: Int, status: StepStatus) =
        StepRun("$stepId-run", "j", stepId, ordinal, status, attempts = 0, nextAttemptAt = null, lastError = null, state = null, output = null)

    private fun record() = RecordingRecord(
        id = ID,
        meta = RecordingMeta(
            schema = 1,
            recordingId = ID,
            source = Source.PHONE,
            platform = Platform.ANDROID,
            deviceId = "device",
            deviceName = "Z Fold7",
            startedAt = "2026-10-27T09:00:00Z",
            timezone = "Asia/Seoul",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MONO),
            parts = emptyList(),
            status = RecordingStatus.FINALIZED,
        ),
        dir = "/data/rec/$ID".toPath(),
    )

    private companion object {
        const val ID = "01J0NOTES"
    }
}
