@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import app.recly.android.ui.component.ProcessingState
import app.recly.android.workflow.EditorErrors
import app.recly.android.workflow.toEdit
import app.recly.android.workflow.toWorkflow
import app.recly.android.workflow.with
import app.recly.android.workflow.without
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import recly.core.model.Language
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.sync.SaveResult
import recly.core.workflow.EditorSessions
import recly.core.workflow.MutationResult
import recly.core.workflow.OpenedOn
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowDocuments
import recly.core.workflow.WorkflowMutator
import recly.core.workflow.WorkflowParser

/**
 * The write gate every document mutation goes through (Sol M2-L4 #1·#2·#3). The ViewModel around it
 * is a thin delegate — it cannot be constructed off-device (`AndroidViewModel` + the real graph) —
 * so the rules live here, where the document source is a fake that records what was written.
 */
class WorkflowMutatorTest {

    private val now = Instant.parse("2026-08-27T09:00:00Z")
    private val opened = "2026-08-01T00:00:00.000Z"

    /** A workflow the parser will look at, so its id has to be a docs/02 ULID. */
    private val TRANSCRIBING = "01J9ABCDEF0123456789ABCDEF"

    private fun workflow(id: String, updatedAt: String = opened, name: String = id) = Workflow(
        id = id,
        name = name,
        updatedAt = updatedAt,
        steps = listOf(Step.DriveUpload(id = "upload")),
    )

    private fun document(vararg workflows: Workflow) = WorkflowsDocument(
        schema = 2,
        revision = 3,
        updatedAt = opened,
        updatedBy = "device-a",
        workflows = workflows.toList(),
    )

    /** Renames on the document it is handed — never on one captured before. */
    private fun WorkflowsDocument.renamed(id: String): WorkflowsDocument? {
        val workflow = workflows.firstOrNull { it.id == id } ?: return null
        return this.with(workflow.toEdit().copy(name = "${workflow.name} 2"), now)
    }

    @Test
    fun `a save is refused when a sync replaced the workflow under the editor`() = runTest {
        val documents = FakeDocuments(document(workflow("a")))
        val mutator = WorkflowMutator(documents)
        // The editor opened on this version; then a pull landed the other device's edit.
        val editing = OpenedOn("a", opened)
        documents.stored = document(workflow("a", updatedAt = "2026-08-27T08:00:00.000Z", name = "theirs"))

        val result = mutator.mutate(expect = editing) { fail("a stale editor must not build a document") }

        assertEquals(MutationResult.Stale, result)
        assertTrue(documents.saves.isEmpty(), "nothing was written")
        assertEquals("theirs", documents.stored.workflows.single().name, "the other device's edit stands")
    }

    @Test
    fun `a save is refused when the workflow was deleted elsewhere`() = runTest {
        val documents = FakeDocuments(document(workflow("a")))
        val mutator = WorkflowMutator(documents)
        documents.stored = document()

        val result = mutator.mutate(expect = OpenedOn("a", opened)) { fail("nothing to save onto") }

        assertEquals(MutationResult.Stale, result)
        assertTrue(documents.saves.isEmpty(), "nothing was written")
    }

    @Test
    fun `a workflow that is not stored yet has no version to be stale against`() = runTest {
        val documents = FakeDocuments(document(workflow("a")))
        val mutator = WorkflowMutator(documents)

        val result = mutator.mutate { doc -> doc.with(workflow("new").toEdit(), now) }

        assertTrue(result is MutationResult.Saved, "a new workflow saves: $result")
        assertEquals(listOf("a", "new"), documents.stored.workflows.map { it.id })
    }

    @Test
    fun `two renames at once keep both changes`() = runTest {
        val documents = FakeDocuments(document(workflow("a"), workflow("b")))
        val mutator = WorkflowMutator(documents)

        // Both start before either finishes: the fake suspends between the read and the write,
        // which is exactly where an unserialized second mutation would read the pre-rename "a".
        awaitAll(
            async { mutator.mutate { it.renamed("a") } },
            async { mutator.mutate { it.renamed("b") } },
        )

        assertEquals(2, documents.saves.size, "each rename wrote once")
        assertEquals(
            mapOf("a" to "a 2", "b" to "b 2"),
            documents.stored.workflows.associate { it.id to it.name },
            "the last document written carries both renames, not just the last one",
        )
    }

    /**
     * ADR-016 superseded the isDefault-undeletable rule: nothing in the document says which device
     * runs a workflow, so no workflow is undeletable. The dialog warns; the write does not refuse.
     */
    @Test
    fun `any workflow is deleted, including the one this device defaults to`() = runTest {
        val documents = FakeDocuments(document(workflow("a"), workflow("b")))
        val mutator = WorkflowMutator(documents)

        val result = mutator.mutate { it.without("a") }

        assertTrue(result is MutationResult.Saved, "the delete landed: $result")
        assertEquals(listOf("b"), documents.stored.workflows.map { it.id })
    }

    /**
     * Sol M2-L4 #1. A save takes as long as a push does, and the user does not wait for it: by the
     * time it lands, the editor it was started from may be gone and another one open in its place.
     * The result may say what became of *its* editor and nothing about the one on screen.
     */
    @Test
    fun `a save landing after another editor opened leaves that editor alone`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val documents = FakeDocuments(document(workflow("a"), workflow("b")), hold = hold)
        val mutator = WorkflowMutator(documents)
        val sessions = EditorSessions()

        // Editor A is open on "a" and Save is tapped; the write is still in flight.
        val sessionA = sessions.open()
        val saving = async {
            mutator.mutate(expect = OpenedOn("a", opened)) { doc ->
                doc.with(workflow("a").toEdit().copy(name = "saved name"), now)
            }
        }
        documents.saving.await()

        // The user leaves it, opens "b", and types into it without saving.
        val state = WorkflowsUiState(
            loading = false,
            editor = EditorState(
                edit = workflow("b").toEdit().copy(name = "not saved yet"),
                isNew = false,
                session = sessions.open(),
                openedOn = OpenedOn("b", opened),
            ),
        )

        hold.complete(Unit)
        val result = saving.await()
        val next = state.afterMutation(result, workflowId = "a", session = sessionA, sessions = sessions)

        assertTrue(result is MutationResult.Saved, "A's save went through: $result")
        assertEquals("b", next.editor?.edit?.id, "the editor on screen is still B")
        assertEquals("not saved yet", next.editor?.edit?.name, "B still has what was typed into it")
        assertEquals(SAVED_ELSEWHERE_NOTICE, next.message, "A's outcome is news, not a screen change")
    }

    /** The same save, landing on the editor that started it, is the one that closes it. */
    @Test
    fun `a save closes the editor it was started from`() = runTest {
        val documents = FakeDocuments(document(workflow("a")))
        val mutator = WorkflowMutator(documents)
        val sessions = EditorSessions()

        val session = sessions.open()
        val state = WorkflowsUiState(
            loading = false,
            editor = EditorState(
                edit = workflow("a").toEdit(),
                isNew = false,
                session = session,
                openedOn = OpenedOn("a", opened),
            ),
        )
        val result = mutator.mutate(expect = OpenedOn("a", opened)) { doc ->
            doc.with(workflow("a").toEdit().copy(name = "saved name"), now)
        }

        val next = state.afterMutation(result, workflowId = "a", session = session, sessions = sessions)

        assertNull(next.editor, "its own editor closes")
        assertNull(next.message, "nothing to announce — the editor closing is the answer")
    }

    /**
     * docs/09 트렌드 2: only a save that landed earns the button's ✓. A rejected one leaves the
     * editor open with its errors, and the button goes back to where it was.
     */
    @Test
    fun `a save that fails validation leaves the editor open with no completion`() {
        val sessions = EditorSessions()
        val session = sessions.open()
        val state = WorkflowsUiState(
            loading = false,
            editor = EditorState(
                edit = workflow("a").toEdit().copy(name = ""),
                isNew = false,
                session = session,
                openedOn = OpenedOn("a", opened),
                save = ProcessingState.PROCESSING,
            ),
        )

        val next = state.afterMutation(
            MutationResult.Invalid(listOf("workflow a: name: must not be blank")),
            workflowId = "a",
            session = session,
            sessions = sessions,
        )

        assertEquals(ProcessingState.FAILED, next.editor?.save, "a rejected save is not a done one")
        assertTrue(next.editor?.errors != EditorErrors(), "the editor keeps what was wrong with it")
    }

    /** A default-mark or a delete comes from the list and has no editor to close (Sol M2-L4 #1). */
    @Test
    fun `a list write never touches the open editor`() = runTest {
        val documents = FakeDocuments(document(workflow("a"), workflow("b")))
        val mutator = WorkflowMutator(documents)
        val sessions = EditorSessions()

        val state = WorkflowsUiState(
            loading = false,
            editor = EditorState(
                edit = workflow("b").toEdit().copy(name = "not saved yet"),
                isNew = false,
                session = sessions.open(),
                openedOn = OpenedOn("b", opened),
            ),
        )
        val result = mutator.mutate { it.renamed("a") }

        val next = state.afterMutation(result, workflowId = "a", session = null, sessions = sessions)

        assertEquals("not saved yet", next.editor?.edit?.name, "the editor is untouched")
        assertNull(next.message)
    }

    /**
     * M7-L3 deliverable 5. The editor holds every number and every optional string as text, so the
     * risk is not that a save fails — it is that opening a `transcribe` step and saving it back
     * quietly changes it. Nothing about it may move.
     */
    @Test
    fun `a transcribe workflow survives the editor's round trip`() = runTest {
        val stored = workflow(TRANSCRIBING).copy(
            steps = listOf(
                Step.DriveUpload(id = "upload"),
                Step.Transcribe(
                    id = "stt",
                    provider = "clova",
                    secretRef = "clova_key",
                    invokeUrl = "https://clovaspeech-gw.example.com/external/v1/1234/abcd",
                    language = Language.KO_EN,
                    diarize = true,
                    speakers = Speakers(min = 2, max = 6),
                ),
            ),
        )
        val documents = FakeDocuments(document(stored))
        val mutator = WorkflowMutator(documents)

        val result = mutator.mutate(expect = OpenedOn(stored.id, opened)) { doc ->
            doc.with(stored.toEdit(), now)
        }

        assertTrue(result is MutationResult.Saved, "the round trip saves: $result")
        val saved = documents.stored.workflows.single()
        assertEquals(stored.steps, saved.steps, "every step came back as it went in")
        assertTrue(
            WorkflowParser.parse(WorkflowParser.serialize(documents.stored)) is ParseResult.Ok,
            "and the document the editor wrote is one the parser accepts",
        )
    }

    /** docs/08's order constraint, which the editor asks about before it offers to save. */
    @Test
    fun `moving a transcribe step in front of its upload is reported by the parser`() = runTest {
        val stored = workflow(TRANSCRIBING).copy(
            steps = listOf(
                Step.DriveUpload(id = "upload"),
                Step.Transcribe(id = "stt", provider = "rtzr", secretRef = "rtzr_key"),
                Step.Webhook(id = "hook", url = "https://example.com/hook"),
            ),
        )
        val edit = stored.toEdit()
        val moved = edit.copy(steps = listOf(edit.steps[1], edit.steps[0], edit.steps[2]))

        val errors = EditorErrors.of(
            WorkflowParser.orderErrors(moved.toWorkflow(opened)),
            moved.id,
        )

        assertEquals(
            WorkflowParser.TRANSCRIBE_NEEDS_UPLOAD,
            errors.steps["stt"]?.order,
            "the complaint is hung on the step that has to move: ${errors.steps}",
        )
        assertNull(errors.steps["hook"]?.order, "the webhook step has no order of its own to keep")
    }

    @Test
    fun `a mutation that finds nothing to change writes nothing`() = runTest {
        val documents = FakeDocuments(document(workflow("a")))
        val mutator = WorkflowMutator(documents)

        val result = mutator.mutate { it.renamed("gone") }

        assertEquals(MutationResult.Skipped, result)
        assertTrue(documents.saves.isEmpty())
    }
}

/** `core.workflows` as [WorkflowMutator] uses it, minus the parser. */
private class FakeDocuments(
    var stored: WorkflowsDocument,
    /** When set, a write waits on it — the interleaving a real push takes its time over. */
    private val hold: CompletableDeferred<Unit>? = null,
) : WorkflowDocuments {

    val saves = mutableListOf<WorkflowsDocument>()

    /** Every call in the order it arrived, so a test can say what was *not* asked (Sol M2-L4 #3). */
    val calls = mutableListOf<String>()

    /** Completed once a write is under way, so a test can act while it is in flight. */
    val saving = CompletableDeferred<Unit>()

    override suspend fun current(): WorkflowsDocument {
        calls += "current"
        yield()
        return stored
    }

    override suspend fun save(document: WorkflowsDocument): SaveResult {
        calls += "save"
        saving.complete(Unit)
        hold?.await()
        yield()
        saves += document
        stored = document
        return SaveResult.Saved(document)
    }
}
