@file:OptIn(ExperimentalTime::class)

package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.core.SecretName
import app.recly.windows.core.Secrets
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.ui.theme.ProcessingState
import app.recly.windows.workflow.StepEdit
import app.recly.windows.workflow.StepKind
import app.recly.windows.workflow.canAdd
import app.recly.windows.workflow.WorkflowEdit
import app.recly.windows.workflow.label
import app.recly.windows.workflow.newStep
import app.recly.windows.workflow.secretRefs
import app.recly.windows.workflow.toEdit
import app.recly.windows.workflow.toWorkflow
import app.recly.windows.workflow.with
import app.recly.windows.workflow.without
import kotlin.time.ExperimentalTime
import recly.core.ids.Ulid
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.platform.Clock
import recly.core.sync.ImportResult
import recly.core.workflow.EditorSessions
import recly.core.workflow.MutationResult
import recly.core.workflow.OpenedOn
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowDocuments
import recly.core.workflow.WorkflowMutator
import recly.core.workflow.WorkflowParser
import kotlin.time.Clock as TimeClock

/** A row of the list (docs/11 A6, the phone's `WorkflowItem`). */
data class WorkflowItem(
    val id: String,
    val name: String,
    /**
     * ADR-016: whether *this PC* falls back to it. It is a local pointer, not a field of the shared
     * document, so the same row is marked here and not on the phone.
     */
    val isDeviceDefault: Boolean,
    /** The step labels in order, as names — "Drive → Webhook" once a window resolves them. */
    val steps: List<Str>,
    /** `secretRef`s this device has no value for (docs/05 "시크릿"). */
    val missingSecrets: List<String>,
)

data class EditorState(
    val edit: WorkflowEdit,
    val isNew: Boolean,
    /** Which opening of the editor this is, so an older save cannot close it ([EditorSessions]). */
    val session: Long,
    /** The version this editor was opened on — null for a workflow that is not stored yet. */
    val openedOn: OpenedOn? = null,
    /** The parser's own sentences, as `save()` returned them (docs/02 "검증 규칙"). */
    val errors: List<String> = emptyList(),
    /**
     * docs/08's order constraints by step id, as the parser's own tokens. Unlike [errors] these
     * are live: moving a step breaks a workflow without any field being wrong, so the editor says
     * so as soon as it is true rather than when a save is refused.
     */
    val order: Map<String, String> = emptyMap(),
    /** Another write landed while this one was open; nothing can be saved on top of it. */
    val stale: Boolean = false,
)

/** [generated] marks a value the user has not seen anywhere else and will not see again. */
data class SecretForm(
    val name: String = "",
    val value: String = "",
    val generated: Boolean = false,
    val error: Str? = null,
    /**
     * The step that asked for it, so the form is shown where it was asked for and nowhere else.
     * null when it was opened from the secret list.
     */
    val stepId: String? = null,
)

/** A stamp for a workflow that is only being validated, never written (see `orderErrors`). */
private const val NOT_SAVED_YET = "1970-01-01T00:00:00.000Z"

/** Matches the parser's `step '<id>'` (`EditorErrors` on the phone reads the same shape). */
private val STEP_ID = Regex("step (?:id )?'([^']+)'")

/** What the editor says when another write replaced the workflow underneath it (Sol M2-L4 #1). */
val STALE_NOTICE: Str = Str.EDITOR_STALE

/** A save landed after its editor was replaced by another one: it is only news, not a screen. */
val SAVED_ELSEWHERE_NOTICE: Str = Str.EDITOR_SAVED_ELSEWHERE

/**
 * The editor window's state (deliverable 4). The rules are the phone's (docs/11 A6) and the Mac's
 * (docs/12 M7), and the two that matter are not restated here — they are [WorkflowMutator]: every
 * write reads the document again inside one mutex, and an editor opened on a version the document
 * no longer has is refused rather than merged.
 *
 * Every mutating call suspends rather than launching: the window wraps them in its own scope, and a
 * test can await one and look at what it did.
 */
class WorkflowsModel(
    private val documents: WorkflowDocuments,
    private val secrets: Secrets,
    private val clock: Clock,
    /** docs/05 "워크플로우 내보내기": `core.workflows.exportJson`. */
    private val exportJson: suspend () -> String,
    /** docs/05 "워크플로우 가져오기": `core.workflows.importJson`, which replaces the whole document. */
    private val importJson: suspend (String) -> ImportResult,
    /**
     * The file itself, which the core never sees: [saveFile] puts [WORKFLOWS_FILE_NAME] in front of
     * a save dialog and returns false when the user closed it, [openFile] returns null the same way.
     * Thin on purpose — a test hands over strings rather than a window.
     */
    private val saveFile: suspend (name: String, contents: String) -> Boolean,
    private val openFile: suspend () -> String?,
    /** ADR-016: the local pointer at what this PC runs — `core.workflows.deviceDefault`. */
    private val deviceDefault: suspend () -> String?,
    /** ADR-016: `core.workflows.setDeviceDefault`. */
    private val setDeviceDefault: suspend (String?) -> Unit,
    /** The clipboard, so the generated `whsec_` can be kept before it stops being readable. */
    private val clipboard: (String) -> Unit,
    /**
     * Every write that changed the document. The tray keeps its own filtered list of what a desktop
     * recording can run, and an edit here is what changes it.
     */
    private val onDocumentChanged: suspend () -> Unit = {},
) {
    var loading: Boolean by mutableStateOf(true)
        private set
    var items: List<WorkflowItem> by mutableStateOf(emptyList())
        private set
    var secretNames: List<String> by mutableStateOf(emptyList())
        private set
    var editor: EditorState? by mutableStateOf(null)
        private set
    var secretForm: SecretForm? by mutableStateOf(null)
        private set
    /** docs/05 "워크플로우 가져오기": the picked file, while its replace confirmation is up. */
    var importConfirm: PickedWorkflows? by mutableStateOf(null)
        private set

    private var deleteConfirmId: String? by mutableStateOf(null)

    /**
     * The row whose delete has been asked for and not yet answered. A workflow leaves this PC and
     * does not come back, so — as on Android — the row's button opens the question rather than
     * deleting. Looked up rather than held: the dialog says whether this is the PC's own default,
     * and ADR-016 lets that pointer move while the question is on screen.
     */
    val deleteConfirm: WorkflowItem? get() = items.firstOrNull { it.id == deleteConfirmId }

    /**
     * The one place [items] is rebuilt: an open delete question follows its row while it exists and
     * is dismissed for good when it is gone — an import can bring the same fixed id back, and a
     * stale confirmation re-arming against the new workflow would be a delete nobody asked of it.
     */
    private fun show(doc: WorkflowsDocument, names: List<String>) {
        items = items(doc, names)
        if (deleteConfirmId != null && items.none { it.id == deleteConfirmId }) deleteConfirmId = null
    }
    private val banner = StatusLine<UiMessage?>(null)

    /**
     * The banner over the list. A name, not a sentence: the window is open while the language can
     * change under it (docs/07 rule 3).
     */
    val message: UiMessage? get() = banner.text

    /**
     * The diagnostic that came with [message], when the message was a core code carrying one
     * (`CODE|detail`, docs/07 §5). The window puts it in monospace underneath.
     */
    val messageDetail: String? get() = banner.detail

    /**
     * docs/09 트렌드 2: where the last write the user asked for is, for the button that asked
     * (`ProcessingButton`). Only what the button draws depends on it.
     */
    var action: ProcessingState by mutableStateOf(ProcessingState.IDLE)
        private set

    /** The last document read — what the list and the editor are opened from. */
    private var document: WorkflowsDocument? = null

    /** ADR-016: the id this PC falls back to, as the last read of the local pointer said. */
    private var defaultId: String? = null

    private val sessions = EditorSessions()

    private val mutator = WorkflowMutator(documents)

    // --- list -----------------------------------------------------------------------------------

    suspend fun reload() {
        document = documents.current()
        secretNames = secrets.names()
        defaultId = deviceDefault()
        show(document!!, secretNames)
        loading = false
    }

    fun add() {
        editor = EditorState(
            edit = WorkflowEdit(
                id = Ulid.generate(TimeClock.System),
                name = "",
                minDurationSec = "0",
                // docs/02 wants 1..10 steps, so a new workflow starts with the one everybody wants.
                steps = listOf(StepEdit.Drive(id = "upload")),
            ),
            isNew = true,
            session = sessions.open(),
        )
    }

    fun edit(id: String) {
        val workflow = document?.workflows?.firstOrNull { it.id == id } ?: return
        editor = workflow.editor(sessions.open())
    }

    fun cancel() {
        sessions.close()
        editor = null
    }

    /** Discards the local edits and starts again from what the document says now. */
    suspend fun reopen() {
        val id = editor?.edit?.id ?: return
        val doc = documents.current().also { document = it }
        val workflow = doc.workflows.firstOrNull { it.id == id }
        // A reopen is a new editor over the stored version: a save still in flight from the old one
        // has no say in what happens to it.
        sessions.close()
        editor = workflow?.editor(sessions.open())
        show(doc, secretNames)
        if (workflow == null) banner.say(Str.EDITOR_DELETED_ELSEWHERE.message())
    }

    /** ADR-016: the row's one control. It writes nothing to the document — the pointer is local. */
    suspend fun setDefault(item: WorkflowItem) {
        setDeviceDefault(item.id)
        defaultId = item.id
        document?.let { show(it, secretNames) }
    }

    /** Opens the confirmation the row's delete button asks for. */
    fun askToDelete(item: WorkflowItem) {
        deleteConfirmId = item.id
    }

    fun cancelDelete() {
        deleteConfirmId = null
    }

    /**
     * ADR-016: any workflow may be deleted, this PC's default among them — the dialog says what that
     * costs, and the core clears the pointer with it so the tray asks for a new pick.
     */
    suspend fun delete(item: WorkflowItem) = working {
        deleteConfirmId = null
        apply(mutator.mutate { it.without(item.id) })
        defaultId = deviceDefault()
        document?.let { show(it, secretNames) }
    }

    // --- editor ---------------------------------------------------------------------------------

    /**
     * Every field edit goes through here, and every one of them clears the errors: they are the
     * verdict `save()` passed on a document that no longer exists the moment anything changes.
     */
    fun update(block: (WorkflowEdit) -> WorkflowEdit) {
        editor = editor?.let {
            val edit = block(it.edit)
            it.copy(edit = edit, errors = emptyList(), order = orderErrors(edit))
        }
    }

    fun addStep(kind: StepKind) = update { edit ->
        if (!kind.canAdd(edit.steps)) return@update edit
        val taken = edit.steps.map { it.id }.toSet()
        edit.copy(steps = edit.steps + kind.newStep(taken))
    }

    fun removeStep(index: Int) = update { edit ->
        edit.copy(steps = edit.steps.filterIndexed { i, _ -> i != index })
    }

    /** [from] lands at [to] and everything between shifts one place. */
    fun moveStep(from: Int, to: Int) = update { edit ->
        if (from == to || to !in edit.steps.indices || from !in edit.steps.indices) return@update edit
        val steps = edit.steps.toMutableList()
        steps.add(to, steps.removeAt(from))
        edit.copy(steps = steps)
    }

    fun updateStep(index: Int, block: (StepEdit) -> StepEdit) = update { edit ->
        edit.copy(steps = edit.steps.mapIndexed { i, step -> if (i == index) block(step) else step })
    }

    /**
     * The only write path. `expect` is the version the editor was opened on, which is what makes a
     * write that landed underneath it a [MutationResult.Stale] rather than a silent overwrite.
     */
    suspend fun save() {
        val open = editor ?: return
        working {
            apply(
                mutator.mutate(expect = open.openedOn) { doc -> doc.with(open.edit, clock.now()) },
                session = open.session,
            )
        }
    }

    /**
     * @param session the editor the mutation was started from, null for a list write. Only a result
     *   whose editor is still the one on screen may change it (Sol M2-L4 #1).
     */
    private suspend fun apply(result: MutationResult, session: Long? = null) {
        val mine = sessions.isCurrent(session)
        action = when (result) {
            is MutationResult.Saved -> ProcessingState.DONE
            MutationResult.Skipped -> ProcessingState.IDLE
            else -> ProcessingState.FAILED
        }
        when (result) {
            is MutationResult.Saved -> {
                document = result.document
                show(result.document, secretNames)
                if (mine) {
                    sessions.close()
                    editor = null
                }
                // A save that came back to an editor the user has already left says so; one that
                // landed in its own editor has the closed editor as its whole report.
                if (!mine && session != null) banner.say(SAVED_ELSEWHERE_NOTICE.message())
                onDocumentChanged()
            }

            is MutationResult.Invalid ->
                if (mine) {
                    editor = editor?.copy(errors = result.errors)
                } else {
                    // docs/02 owns these words and they are not ours to translate.
                    banner.say(UiMessage.Text(result.errors.joinToString("\n")))
                }

            // The editor stays open with what the user typed: the only choices v1 offers are
            // reopening (losing it) and cancelling, and both are theirs to make.
            MutationResult.Stale ->
                if (mine) editor = editor?.copy(stale = true) else banner.say(STALE_NOTICE.message())

            MutationResult.Skipped -> Unit
        }
    }

    // --- secrets (docs/05 "시크릿") ---------------------------------------------------------------

    fun openSecrets(prefill: String? = null, stepId: String? = null) {
        secretForm = SecretForm(name = prefill.orEmpty(), stepId = stepId)
    }

    fun closeSecrets() {
        secretForm = null
    }

    fun secretName(value: String) {
        secretForm = secretForm?.copy(name = value, error = null)
    }

    fun secretValue(value: String) {
        secretForm = secretForm?.copy(value = value, generated = false, error = null)
    }

    /** docs/04: the `whsec_` value is shown once, here, and is never readable again afterwards. */
    fun generateSecret() {
        val value = secrets.generate()
        secretForm = secretForm?.copy(value = value, generated = true, error = null)
        clipboard(value)
    }

    fun copyGenerated() {
        secretForm?.takeIf { it.generated }?.let { clipboard(it.value) }
    }

    /**
     * The name that was stored, for a caller that has somewhere to put it — the step editor assigns
     * it to the `secretRef` it was opened from. null when nothing was stored.
     */
    suspend fun saveSecret(): String? {
        val form = secretForm ?: return null
        val name = form.name.trim()
        val problem = SecretName.problem(name, secretNames)
            ?: Str.SECRET_VALUE_REQUIRED.takeIf { form.value.isBlank() }
        if (problem != null) {
            secretForm = form.copy(error = problem)
            return null
        }
        secrets.put(name, form.value)
        secretForm = null
        secretNames = secrets.names()
        document?.let { show(it, secretNames) }
        return name
    }

    suspend fun deleteSecret(name: String) {
        secrets.delete(name)
        secretNames = secrets.names()
        document?.let { show(it, secretNames) }
    }

    // --- docs/05 "워크플로우 내보내기 · 가져오기" -------------------------------------------------

    /**
     * The document as it is stored, into whatever file the save dialog came back with. The pointer
     * is not in it and neither are the secret values — `exportJson` decides that, not this.
     */
    suspend fun exportWorkflows() = working {
        val json = exportJson()
        // A dialog the user closed asked for nothing, so it reports nothing.
        if (!saveFile(WORKFLOWS_FILE_NAME, json)) {
            action = ProcessingState.IDLE
            return@working
        }
        banner.say(Str.WORKFLOWS_EXPORTED.message())
        action = ProcessingState.DONE
    }

    /**
     * The picked file, parsed for the one number the confirmation has to name — an import replaces
     * the whole document and there is no merge, so it is asked before anything is written. A file
     * that does not parse never gets a confirmation: [importJson] refuses it without writing, and it
     * is the one place the parser's complaints are turned into the list the editor shows.
     */
    suspend fun pickImport() = working {
        val json = openFile()
        if (json == null) {
            action = ProcessingState.IDLE
            return@working
        }
        when (val parsed = WorkflowParser.parse(json)) {
            is ParseResult.Ok -> {
                importConfirm = PickedWorkflows(json, parsed.document.workflows.size)
                action = ProcessingState.IDLE
            }

            else -> applyImport(importJson(json))
        }
    }

    fun cancelImport() {
        importConfirm = null
    }

    /** docs/05: the confirmed replace. The file becomes the whole document. */
    suspend fun confirmImport() = working {
        val picked = importConfirm ?: return@working
        importConfirm = null
        applyImport(importJson(picked.json))
    }

    private suspend fun applyImport(result: ImportResult) = when (result) {
        is ImportResult.Imported -> {
            defaultId = deviceDefault()
            document = documents.current().also { show(it, secretNames) }
            banner.say(Str.WORKFLOWS_IMPORTED.message(result.workflows))
            action = ProcessingState.DONE
            onDocumentChanged()
        }

        // docs/02 owns these words and they are not ours to translate.
        is ImportResult.Invalid -> {
            banner.say(UiMessage.Text(result.errors.joinToString("\n")))
            action = ProcessingState.FAILED
        }
    }

    /** The file the dialog could not open or write — the shell's own complaint, not the core's. */
    fun fileFailed(reason: String) {
        banner.say(Str.WORKFLOWS_FILE_FAILED.message(reason))
        action = ProcessingState.FAILED
    }

    // --- plumbing -------------------------------------------------------------------------------

    /**
     * The button that asked is "…" for as long as the write runs, not only once it has come back
     * (`ShellModel.tracked` says the same thing about the tray's actions). Set before the
     * suspension, or an operation slower than the button's own window re-enables it mid-flight and
     * the second press is a duplicate. A [block] that throws (a store that cannot be read, a file
     * that vanished) leaves the button failed rather than "…" for ever; the throwable carries on.
     */
    private suspend fun working(block: suspend () -> Unit) {
        action = ProcessingState.PROCESSING
        try {
            block()
        } catch (e: Throwable) {
            action = ProcessingState.FAILED
            throw e
        }
    }

    /** An editor over a stored workflow remembers the version it started from (Sol M2-L4 #1). */
    private fun Workflow.editor(session: Long) = EditorState(
        edit = toEdit(),
        isNew = false,
        session = session,
        openedOn = OpenedOn(id, updatedAt),
        order = orderErrors(toEdit()),
    )

    /** The parser's docs/08 order verdict, by the step that has to move. */
    private fun orderErrors(edit: WorkflowEdit): Map<String, String> =
        WorkflowParser.orderErrors(edit.toWorkflow(NOT_SAVED_YET))
            .mapNotNull { error ->
                val stepId = STEP_ID.find(error)?.groupValues?.get(1) ?: return@mapNotNull null
                stepId to WorkflowParser.TRANSCRIBE_NEEDS_UPLOAD
            }
            .toMap()

    private fun items(doc: WorkflowsDocument, secrets: List<String>): List<WorkflowItem> =
        doc.workflows.map { workflow ->
            WorkflowItem(
                id = workflow.id,
                name = workflow.name,
                isDeviceDefault = workflow.id == defaultId,
                steps = workflow.steps.map { it.label() },
                missingSecrets = workflow.secretRefs().filterNot { it in secrets },
            )
        }
}

/** A file the user picked and has not yet agreed to: [workflows] is what the confirmation names. */
data class PickedWorkflows(val json: String, val workflows: Int)

/** docs/05 "워크플로우 내보내기": the name the save dialog suggests, the same on every shell. */
const val WORKFLOWS_FILE_NAME: String = "recly-workflows.json"
