package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument

/**
 * docs/10: the cold launch from a job notification. The tap asks for an editor before the core has
 * emitted a document to look the workflow up in, and the request used to be dropped there — the app
 * opened on the Workflows list and did nothing, with no second chance, because the tap happens once.
 *
 * The [WorkflowsViewModel] around this cannot be built off-device (`AndroidViewModel` + the real
 * graph — the same reason `WorkflowMutatorTest` tests the mutator rather than the ViewModel), so
 * the rule is tested here against a document source that arrives as late as the real one does, wired
 * the way the ViewModel wires it.
 */
class PendingEditorTest {

    /** docs/02 ULIDs: the parser never sees these, but the ids the screen passes around are ULIDs. */
    private val morning = "01J9ABCDEF0123456789ABCDEF"
    private val evening = "01J9ABCDEF0123456789ABCDEG"

    @Test
    fun `an editor asked for before the document arrives opens when it does`() = runTest {
        val editor = PendingEditor()
        var document: WorkflowsDocument? = null
        var opened: Workflow? = null

        // The ViewModel's own wiring: `document` is what the last emission said, and a parked
        // request is spent on the emission that can answer it.
        launch {
            observe(document(workflow(morning)), afterMs = 200).collect { doc ->
                document = doc
                editor.onDocument(doc)?.let { opened = it }
            }
        }

        // The tap, on a ViewModel whose collector has not run yet. Nothing to open — but the id is
        // now waiting rather than gone.
        assertNull(editor.open(document, morning), "there is no document to open an editor from yet")
        assertNull(opened, "nothing opened before the document arrived")

        // Virtual time: long enough for the emission the tap beat to arrive.
        delay(300)

        assertEquals(morning, opened?.id, "the first document fulfils the request the tap parked")
    }

    /** The warm launch — the app was already open — is unchanged: the editor opens on the spot. */
    @Test
    fun `an editor asked for after the document has loaded opens immediately`() {
        val editor = PendingEditor()
        val document = document(workflow(morning), workflow(evening))

        assertEquals(evening, editor.open(document, evening)?.id)
    }

    /**
     * A workflow that is no longer in the document was deleted, not late. Parking that id would
     * open an editor for it at whatever unrelated moment it next appeared.
     */
    @Test
    fun `a request the loaded document cannot answer is not parked`() {
        val editor = PendingEditor()

        assertNull(editor.open(document(workflow(morning)), evening))
        assertNull(editor.onDocument(document(workflow(morning), workflow(evening))))
    }

    /** The core's `workflows.observe()`, arriving as late as it does on a cold start. */
    private fun observe(document: WorkflowsDocument, afterMs: Long): Flow<WorkflowsDocument> = flow {
        delay(afterMs)
        emit(document)
    }

    private fun workflow(id: String) = Workflow(
        id = id,
        name = id,
        updatedAt = "2026-08-01T00:00:00.000Z",
        steps = listOf(Step.DriveUpload(id = "upload")),
    )

    private fun document(vararg workflows: Workflow) = WorkflowsDocument(
        schema = 2,
        revision = 3,
        updatedAt = "2026-08-01T00:00:00.000Z",
        updatedBy = "device-a",
        workflows = workflows.toList(),
    )

    /** Sol P1-android r3: the user did something else before the document arrived. */
    @Test
    fun `a request the user moved on from is not fulfilled`() {
        val editor = PendingEditor()
        assertNull(editor.open(null, morning))
        editor.clear()
        assertNull(editor.onDocument(document(workflow(morning))), "the document arriving later opens nothing")
    }
}
