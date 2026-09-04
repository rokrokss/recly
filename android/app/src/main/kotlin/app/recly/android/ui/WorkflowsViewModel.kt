@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.R
import app.recly.android.core.AppGraph
import app.recly.android.core.CoreModule
import app.recly.android.core.UiMessage
import app.recly.android.ui.component.ProcessingState
import app.recly.android.workflow.EditorErrors
import app.recly.android.workflow.SecretName
import app.recly.android.workflow.StepEdit
import app.recly.android.workflow.StepKind
import app.recly.android.workflow.canAdd
import app.recly.android.workflow.WorkflowEdit
import app.recly.android.workflow.newStep
import app.recly.android.workflow.secretRefs
import app.recly.android.workflow.toEdit
import app.recly.android.workflow.toWorkflow
import app.recly.android.workflow.with
import app.recly.android.workflow.without
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import recly.core.ids.Ulid
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.sync.WorkflowRepository
import recly.core.workflow.EditorSessions
import recly.core.workflow.MutationResult
import recly.core.workflow.OpenedOn
import recly.core.workflow.WorkflowDocuments
import recly.core.workflow.WorkflowMutator
import recly.core.workflow.WorkflowParser

/** A row of the workflow list (deliverable 1). */
data class WorkflowItem(
    val id: String,
    val name: String,
    /**
     * ADR-016: whether *this phone* falls back to it. It is a local pointer, not a field of the
     * shared document, so the same row is marked on one device and not on another.
     */
    val isDeviceDefault: Boolean,
    /** The step types in order, as strings the screen looks up — e.g. Drive → Webhook. */
    val steps: List<Int>,
    /** `secretRef`s this device has no value for (docs/05 "시크릿"). */
    val missingSecrets: List<String>,
)

data class EditorState(
    val edit: WorkflowEdit,
    val isNew: Boolean,
    /** Which opening of the editor this is, so an older save cannot close it ([EditorSessions]). */
    val session: Long,
    val errors: EditorErrors = EditorErrors(),
    /** Index of the step whose sheet is open. */
    val openStep: Int? = null,
    /** The version this editor was opened on — null for a workflow that is not stored yet. */
    val openedOn: OpenedOn? = null,
    /** Another write landed while this one was open; nothing can be saved on top of it. */
    val stale: Boolean = false,
    /** Where the Save button's own operation is (docs/09): only a save that landed earns a ✓. */
    val save: ProcessingState = ProcessingState.IDLE,
)

/** The secret manager (deliverable 3). [generated] marks a value the user has not seen anywhere else. */
data class SecretsState(
    val name: String = "",
    val value: String = "",
    val generated: Boolean = false,
    @param:StringRes val error: Int? = null,
)

data class WorkflowsUiState(
    val loading: Boolean = true,
    val items: List<WorkflowItem> = emptyList(),
    val secrets: List<String> = emptyList(),
    val editor: EditorState? = null,
    val secretsOpen: SecretsState? = null,
    val confirmDelete: WorkflowItem? = null,
    val message: UiMessage? = null,
)

/** A stamp for a workflow that is only being validated, never written (see `orderErrors`). */
private const val NOT_SAVED_YET = "1970-01-01T00:00:00.000Z"

/** What the editor says when another write replaced the workflow underneath it (Sol M2-L4 #1). */
internal val STALE_NOTICE = UiMessage.Res(R.string.notice_stale)

/** A save landed after its editor was replaced by another one: it is only news, not a screen. */
internal val SAVED_ELSEWHERE_NOTICE = UiMessage.Res(R.string.notice_saved_elsewhere)

/**
 * docs/11 A6. The editor never mutates the document it is shown: it edits a copy of one workflow
 * and hands the whole `WorkflowsDocument` to `core.workflows.save`, which is the only thing that
 * validates it (docs/02).
 */
class WorkflowsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(WorkflowsUiState())
    val state: StateFlow<WorkflowsUiState> = _state.asStateFlow()

    /** The last document the core gave us — what the list and the editor are opened from. */
    private var document: WorkflowsDocument? = null

    /** ADR-016: the id this phone falls back to, as `observeDeviceDefault` last said. */
    private var deviceDefault: String? = null

    /** docs/10: an editor a notification asked for before the first document arrived. */
    private val pendingEditor = PendingEditor()

    /**
     * Every write goes through this one object, so two of them never race (see [WorkflowMutator]).
     * The graph resolves lazily inside it; the mutator itself — and its mutex — is one per
     * ViewModel, which is one per process for this screen.
     */
    /** Which editor is open, for results that come back to a screen that has moved on (Sol #1). */
    private val sessions = EditorSessions()

    private val mutator = WorkflowMutator(
        object : WorkflowDocuments {
            override suspend fun current() = core().workflows.current()
            override suspend fun save(document: WorkflowsDocument) = core().workflows.save(document)
        },
    )

    init {
        viewModelScope.launch {
            val graph = graph()
            // Seeds the docs/05 starters if this device has never had a document, so the list is
            // never empty on a fresh install — and points this phone's own default at 메모
            // (ADR-016: a phone records one far more often than a 회의).
            graph.core.workflows.seed(WorkflowRepository.MEMO_ID)
            refreshSecrets(graph)
            graph.core.workflows.observe().collect { doc ->
                document = doc
                _state.update { it.copy(loading = false).showing(items(doc, it.secrets)) }
                pendingEditor.onDocument(doc)?.let(::openEditor)
            }
        }
        // The pointer is not in the document, so it changes without one arriving — and it decides
        // which row is marked and which delete carries a warning.
        viewModelScope.launch {
            graph().core.workflows.observeDeviceDefault().collect { id ->
                deviceDefault = id
                _state.update { state ->
                    state.showing(document?.let { items(it, state.secrets) } ?: state.items)
                }
            }
        }
    }

    // --- list -------------------------------------------------------------------------------

    fun add() {
        // Whatever a notification asked for before the document arrived, the user has moved on.
        pendingEditor.clear()
        val edit = WorkflowEdit(
            id = Ulid.generate(Clock.System),
            name = "",
            minDurationSec = "0",
            // docs/02 wants 1..10 steps, so a new workflow starts with the one everybody wants.
            steps = listOf(StepEdit.Drive(id = "upload")),
        )
        _state.update { it.copy(editor = EditorState(edit, isNew = true, session = sessions.open())) }
    }

    fun edit(id: String) {
        pendingEditor.open(document, id)?.let(::openEditor)
    }

    private fun openEditor(workflow: Workflow) =
        _state.update { it.copy(editor = workflow.editor(sessions.open())) }

    /** Navigation away from the list (another tab, the secrets form): a parked editor request
     * must not surface later over whatever the user is doing then (Sol P1-android r3). */
    fun dismissPending() = pendingEditor.clear()

    fun cancel() {
        pendingEditor.clear()
        sessions.close()
        _state.update { it.copy(editor = null) }
    }

    /** Discards the local edits and starts again from what the document says now. */
    fun reopen() = launch {
        val id = _state.value.editor?.edit?.id ?: return@launch
        val doc = core().workflows.current().also { document = it }
        val workflow = doc.workflows.firstOrNull { it.id == id }
        // A reopen is a new editor over the stored version: a save still in flight from the old one
        // has no say in what happens to it.
        sessions.close()
        val editor = workflow?.editor(sessions.open())
        _state.update { state ->
            state.copy(
                editor = editor,
                message = if (workflow == null) {
                    UiMessage.Res(R.string.workflow_gone)
                } else {
                    state.message
                },
            ).showing(items(doc, state.secrets))
        }
    }

    /** ADR-016: the row's one control. It writes nothing to the document — the pointer is local. */
    fun setDeviceDefault(item: WorkflowItem) = launch {
        core().workflows.setDeviceDefault(item.id)
    }

    fun confirmDelete(item: WorkflowItem?) = _state.update { it.copy(confirmDelete = item) }

    /**
     * ADR-016: any workflow may be deleted, this device's default among them — the dialog says so
     * first, and the core clears the pointer with it so the screen asks for a new pick.
     */
    fun delete(item: WorkflowItem) = launch {
        _state.update { it.copy(confirmDelete = null) }
        apply(mutator.mutate { it.without(item.id) }, workflowId = item.id)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // --- editor -----------------------------------------------------------------------------

    /**
     * Every field edit goes through here, and every one of them clears the errors: they are the
     * verdict `save()` passed on a document that no longer exists the moment anything changes.
     */
    fun update(block: (WorkflowEdit) -> WorkflowEdit) = _state.update { state ->
        val editor = state.editor ?: return@update state
        val edit = block(editor.edit)
        state.copy(editor = editor.copy(edit = edit, errors = orderErrors(edit)))
    }

    fun addStep(kind: StepKind) = update { edit ->
        if (!kind.canAdd(edit.steps)) return@update edit
        val taken = edit.steps.map { it.id }.toSet()
        edit.copy(steps = edit.steps + kind.newStep(taken))
    }

    fun removeStep(index: Int) = update { edit ->
        edit.copy(steps = edit.steps.filterIndexed { i, _ -> i != index })
    }

    /** Drag reorder: [from] lands at [to] and everything between shifts one place. */
    fun moveStep(from: Int, to: Int) = update { edit ->
        if (from == to || to !in edit.steps.indices) return@update edit
        val steps = edit.steps.toMutableList()
        steps.add(to, steps.removeAt(from))
        edit.copy(steps = steps)
    }

    fun openStep(index: Int?) = _state.update { state ->
        state.copy(editor = state.editor?.copy(openStep = index))
    }

    fun updateStep(index: Int, block: (StepEdit) -> StepEdit) = update { edit ->
        edit.copy(steps = edit.steps.mapIndexed { i, step -> if (i == index) block(step) else step })
    }

    /**
     * The only write path. Validation errors come back as the parser's own sentences and are sorted
     * onto the fields that caused them ([EditorErrors]); everything else closes the editor this was
     * started from.
     */
    fun save() = launch {
        val editor = _state.value.editor ?: return@launch
        _state.update { it.copy(editor = it.editor?.copy(save = ProcessingState.PROCESSING)) }
        apply(
            mutator.mutate(expect = editor.openedOn) { doc ->
                doc.with(editor.edit, core().deps.clock.now())
            },
            workflowId = editor.edit.id,
            session = editor.session,
        )
    }

    /**
     * @param session the editor the mutation was started from, null for a list write. Only a result
     *   whose editor is still the one on screen may change it (Sol M2-L4 #1) — the document half
     *   lands whatever the user is looking at.
     */
    private fun apply(result: MutationResult, workflowId: String, session: Long? = null) {
        if (result is MutationResult.Saved) document = result.document

        _state.update { state ->
            val next = state.afterMutation(result, workflowId, session, sessions)
            if (result is MutationResult.Saved) {
                next.showing(items(result.document, next.secrets))
            } else {
                next
            }
        }

        // Its own editor closed above; the token goes with it.
        if (result is MutationResult.Saved && sessions.isCurrent(session)) sessions.close()
    }

    // --- secrets ----------------------------------------------------------------------------

    fun openSecrets(prefill: String? = null) =
        _state.update { it.copy(secretsOpen = SecretsState(name = prefill.orEmpty())) }

    fun closeSecrets() = _state.update { it.copy(secretsOpen = null) }

    fun secretName(value: String) = updateSecrets { it.copy(name = value, error = null) }

    fun secretValue(value: String) = updateSecrets { it.copy(value = value, generated = false, error = null) }

    /** docs/04: the `whsec_` value is shown once, here, and is never readable again afterwards. */
    fun generateSecret() = launch {
        val secret = graph().secrets.generate()
        updateSecrets { it.copy(value = secret, generated = true, error = null) }
    }

    fun saveSecret() = launch {
        val form = _state.value.secretsOpen ?: return@launch
        val name = form.name.trim()
        val problem = SecretName.problem(name, _state.value.secrets)
            ?: R.string.secret_value_required.takeIf { form.value.isBlank() }
        if (problem != null) {
            updateSecrets { it.copy(error = problem) }
            return@launch
        }
        val graph = graph()
        graph.secrets.put(name, form.value)
        _state.update { it.copy(secretsOpen = SecretsState()) }
        refreshSecrets(graph)
    }

    fun deleteSecret(name: String) = launch {
        val graph = graph()
        graph.secrets.delete(name)
        refreshSecrets(graph)
    }

    // --- plumbing ---------------------------------------------------------------------------

    private suspend fun refreshSecrets(graph: AppGraph) {
        val names = graph.secrets.names()
        _state.update {
            it.copy(secrets = names).showing(document?.let { doc -> items(doc, names) } ?: it.items)
        }
    }

    /** An editor over a stored workflow remembers the version it started from (Sol M2-L4 #1). */
    private fun Workflow.editor(session: Long) = EditorState(
        edit = toEdit(),
        isNew = false,
        session = session,
        openedOn = OpenedOn(id, updatedAt),
    )

    /**
     * docs/08's order constraints, live: moving a step is the one edit that breaks a workflow
     * without any field being wrong, so the parser is asked before the save rather than after it.
     */
    private fun orderErrors(edit: WorkflowEdit): EditorErrors =
        EditorErrors.of(WorkflowParser.orderErrors(edit.toWorkflow(NOT_SAVED_YET)), edit.id)

    private fun items(doc: WorkflowsDocument, secrets: List<String>): List<WorkflowItem> {
        val context = getApplication<Application>()
        return doc.workflows.map { workflow ->
            WorkflowItem(
                id = workflow.id,
                name = workflow.name,
                isDeviceDefault = workflow.id == deviceDefault,
                steps = workflow.steps.map { it.labelRes() },
                // docs/05 "시크릿": a transcribe key is missing the same way a webhook's is, so
                // every kind of step is asked what it needs.
                missingSecrets = workflow.secretRefs().filterNot { it in secrets },
            )
        }
    }

    private fun updateSecrets(block: (SecretsState) -> SecretsState) = _state.update { state ->
        state.copy(secretsOpen = state.secretsOpen?.let(block))
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun core() = graph().core

    private suspend fun graph(): AppGraph = CoreModule.get(getApplication())
}

/**
 * docs/10 "탭하면 고칠 수 있는 화면으로 간다", against the one launch where the screen is not there yet.
 *
 * A job notification tapped from cold starts the activity, this ViewModel and the first
 * `workflows.observe()` emission all at once, and the tap wins that race: [WorkflowsViewModel.edit]
 * reaches a ViewModel whose `document` is still null, finds nothing to open and returns — leaving
 * the user on the Workflows list with no editor and no second chance, because a notification tap
 * happens once. So a request the document cannot answer *yet* waits for one.
 *
 * One request at a time: there is one editor, and the newest tap is the one the user meant.
 */
internal class PendingEditor {

    private var pending: String? = null

    /**
     * The workflow to open for [id] now, if [document] can say. A null document parks the request
     * for [onDocument]; a document that simply has no such workflow does not — it was deleted, and
     * an id waiting for a workflow that will never arrive would open an editor at a random later
     * time instead of never.
     */
    fun open(document: WorkflowsDocument?, id: String): Workflow? {
        pending = if (document == null) id else null
        return document?.workflows?.firstOrNull { it.id == id }
    }

    /** Forgets a parked request — the user did something else before the document arrived. */
    fun clear() {
        pending = null
    }

    /** The first document after a parked request: the editor it was asking for, or nothing. */
    fun onDocument(document: WorkflowsDocument): Workflow? {
        val id = pending ?: return null
        pending = null
        return document.workflows.firstOrNull { it.id == id }
    }
}

/**
 * The one place [WorkflowsUiState.items] is rebuilt, so an open delete confirmation is the row as it
 * is *now*: ADR-016 lets the default pointer move while the question is on screen, and that pointer
 * is exactly what the question's warning is about.
 *
 * A question about a row that is gone is over — and it must not lie in wait: an import can bring the
 * same fixed id back, and a stale confirmation re-arming against the new workflow would be a delete
 * nobody asked of *it*. The desktop twins are Apple's `WorkflowsModel.show` and the PC's.
 */
internal fun WorkflowsUiState.showing(items: List<WorkflowItem>): WorkflowsUiState = copy(
    items = items,
    confirmDelete = confirmDelete?.let { open -> items.firstOrNull { it.id == open.id } },
)

/**
 * What a finished mutation does to the screen (Sol M2-L4 #1). Everything that only the editor cares
 * about — closing it, marking it stale, hanging field errors on it — happens only when the editor
 * on screen is still the one the mutation was started from; a result that belongs to an editor the
 * user has already left says its piece in [WorkflowsUiState.message] and leaves the screen alone.
 * A list write (toggle, delete) has no [session] at all and so never closes an editor.
 *
 * The document half of a save is not here: `items` is the ViewModel's, and it lands whatever is on
 * screen.
 */
internal fun WorkflowsUiState.afterMutation(
    result: MutationResult,
    workflowId: String,
    session: Long?,
    sessions: EditorSessions,
): WorkflowsUiState {
    val mine = sessions.isCurrent(session)
    return when (result) {
        is MutationResult.Invalid ->
            if (mine) {
                copy(
                    editor = editor?.copy(
                        errors = EditorErrors.of(result.errors, workflowId),
                        save = ProcessingState.FAILED,
                    ),
                )
            } else {
                copy(message = UiMessage.Text(result.errors.joinToString("\n")))
            }

        is MutationResult.Saved -> when {
            mine -> copy(editor = null)
            session != null -> copy(message = SAVED_ELSEWHERE_NOTICE)
            else -> this
        }

        // The editor stays open with what the user typed: the only choices v1 offers are
        // reopening (losing it) and cancelling, and both are theirs to make.
        MutationResult.Stale ->
            if (mine) {
                copy(editor = editor?.copy(stale = true, save = ProcessingState.FAILED))
            } else {
                copy(message = STALE_NOTICE)
            }

        MutationResult.Skipped ->
            if (mine) copy(editor = editor?.copy(save = ProcessingState.IDLE)) else this
    }
}

/**
 * The three step→label maps — this one, [StepEdit.labelRes] below, and `StepKind.labelRes` in the
 * editor — deliberately stay apart: they map three different types, and a saved `DriveUpload` is
 * listed as "Drive" where the two editing surfaces name the action, "Drive upload".
 */
@StringRes
internal fun Step.labelRes(): Int = when (this) {
    is Step.DriveUpload -> R.string.step_drive
    is Step.Webhook -> R.string.step_webhook
    is Step.Transcribe -> R.string.step_transcribe
}

@StringRes
internal fun StepEdit.labelRes(): Int = when (this) {
    is StepEdit.Drive -> R.string.step_drive_upload
    is StepEdit.Hook -> R.string.step_webhook
    is StepEdit.Transcribe -> R.string.step_transcribe
}

