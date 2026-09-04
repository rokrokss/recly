package app.recly.android.ui

import app.recly.android.R
import app.recly.android.core.UiMessage
import app.recly.android.ui.component.ProcessingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.sync.ImportResult

/**
 * docs/05 "워크플로우 내보내기 · 가져오기" as the settings section sees it. What is worth pinning is
 * the order: a picked file raises a confirmation and writes nothing, and the section only ever says
 * a number the replace itself came back with.
 */
class WorkflowTransferTest {

    @Test
    fun `a picked file is a confirmation, not a write`() {
        val state = WorkflowTransfer.picked(WorkflowTransferUiState(), json = JSON, workflows = 3)

        assertEquals(PickedWorkflows(JSON, 3), state.confirm)
        assertNull(state.message, "nothing has happened yet, so there is nothing to report")
    }

    /** The confirmation carries the file with it: the replace runs on what was parsed, not a reread. */
    @Test
    fun `cancelling drops the picked file`() {
        val picked = WorkflowTransfer.picked(WorkflowTransferUiState(), json = JSON, workflows = 3)

        assertNull(WorkflowTransfer.cancelled(picked).confirm)
    }

    @Test
    fun `a replace that landed closes the confirmation and names the count`() {
        val picked = WorkflowTransfer.picked(WorkflowTransferUiState(), json = JSON, workflows = 3)

        val state = WorkflowTransfer.imported(picked, ImportResult.Imported(workflows = 3))

        assertNull(state.confirm)
        assertEquals(UiMessage.Res(R.string.workflows_imported, listOf(3)), state.message)
        assertEquals(ProcessingState.DONE, state.importing)
    }

    /**
     * docs/02 owns the parser's sentences and they are not the shell's to translate, so an invalid
     * file is shown exactly as the core listed it (the editor shows the same list the same way).
     */
    @Test
    fun `an unreadable file is the parser's own complaint`() {
        val state = WorkflowTransfer.imported(
            WorkflowTransferUiState(),
            ImportResult.Invalid(listOf("workflow 'a': name is empty", "schema 9 is not supported")),
        )

        assertEquals(
            UiMessage.Text("workflow 'a': name is empty\nschema 9 is not supported"),
            state.message,
        )
        assertEquals(true, state.failed)
        assertEquals(ProcessingState.FAILED, state.importing)
    }

    /** A file the platform would not open is the shell's own failure, and it names the diagnostic. */
    @Test
    fun `a file that could not be opened says so with the reason under it`() {
        val state = WorkflowTransfer.fileFailed(WorkflowTransferUiState(), "no such document")

        assertEquals(
            UiMessage.Res(R.string.workflows_file_failed, listOf("no such document")),
            state.message,
        )
        assertEquals(true, state.failed)
    }

    @Test
    fun `an export that landed says so`() {
        val state = WorkflowTransfer.exported(WorkflowTransferUiState())

        assertEquals(UiMessage.Res(R.string.workflows_exported), state.message)
        assertEquals(ProcessingState.DONE, state.exporting)
    }

    private companion object {
        /** Opaque here: the section never reads it, it only carries it to `importJson`. */
        const val JSON = """{"schema":3}"""
    }
}
