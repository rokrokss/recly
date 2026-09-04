package app.recly.android.ui

import app.recly.android.R
import app.recly.android.core.UiMessage
import app.recly.android.ui.component.ProcessingState
import recly.core.sync.ImportResult

/** A file the user picked and has not yet agreed to: [workflows] is what the confirmation names. */
data class PickedWorkflows(val json: String, val workflows: Int)

/** The settings section docs/05 calls "워크플로우 내보내기 · 가져오기", in the shapes it has. */
data class WorkflowTransferUiState(
    /** The picked file, while the "this replaces everything" confirmation is up. */
    val confirm: PickedWorkflows? = null,
    /** What the last export or import had to say, said under the section that did it. */
    val message: UiMessage? = null,
    val failed: Boolean = false,
    val exporting: ProcessingState = ProcessingState.IDLE,
    val importing: ProcessingState = ProcessingState.IDLE,
)

/**
 * What the section decides, kept out of [SettingsViewModel] so the confirm, the replace and the
 * unreadable-file paths can be tested against the core's own result types rather than a screenshot
 * — the phone's ViewModel needs an `Application` and a database and a test has neither.
 *
 * Reading and writing the file itself is the shell's: the core only ever sees the string.
 */
object WorkflowTransfer {

    /**
     * The moment the user confirms: the dialog comes down *before* the write starts, so a cancel
     * arriving during a slow replace has nothing left to cancel — it cannot un-ask a write that is
     * already running, and leaving the dialog up would let it pretend to.
     */
    fun confirmed(state: WorkflowTransferUiState): WorkflowTransferUiState =
        state.copy(confirm = null, importing = ProcessingState.PROCESSING)

    /**
     * A picked file that parsed. docs/05 has no merge — the file replaces the whole document — so
     * the honest offer is the count, made before anything is written.
     */
    fun picked(state: WorkflowTransferUiState, json: String, workflows: Int): WorkflowTransferUiState =
        state.copy(
            confirm = PickedWorkflows(json, workflows),
            message = null,
            failed = false,
            importing = ProcessingState.IDLE,
        )

    fun cancelled(state: WorkflowTransferUiState): WorkflowTransferUiState =
        state.copy(confirm = null, importing = ProcessingState.IDLE)

    /**
     * The section after the replace ran — or after a file that never parsed, which
     * `WorkflowRepository.importJson` refuses without writing and whose errors it is the one place
     * that spells out (docs/02 owns those words, so they are shown as they stand).
     */
    fun imported(state: WorkflowTransferUiState, result: ImportResult): WorkflowTransferUiState =
        when (result) {
            is ImportResult.Imported -> state.copy(
                confirm = null,
                message = UiMessage.Res(R.string.workflows_imported, listOf(result.workflows)),
                failed = false,
                importing = ProcessingState.DONE,
            )

            is ImportResult.Invalid -> state.copy(
                confirm = null,
                message = UiMessage.Text(result.errors.joinToString("\n")),
                failed = true,
                importing = ProcessingState.FAILED,
            )
        }

    /** The file the user chose could not be read or written — the shell's own complaint, not the core's. */
    fun fileFailed(state: WorkflowTransferUiState, reason: String): WorkflowTransferUiState = state.copy(
        confirm = null,
        message = UiMessage.Res(R.string.workflows_file_failed, listOf(reason)),
        failed = true,
        exporting = ProcessingState.FAILED,
        importing = ProcessingState.FAILED,
    )

    fun exported(state: WorkflowTransferUiState): WorkflowTransferUiState = state.copy(
        message = UiMessage.Res(R.string.workflows_exported),
        failed = false,
        exporting = ProcessingState.DONE,
    )
}
