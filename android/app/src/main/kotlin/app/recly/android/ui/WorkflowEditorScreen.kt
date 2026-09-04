@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintChip
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintMenu
import app.recly.android.ui.component.BlueprintMenuItem
import app.recly.android.ui.component.BlueprintRadioRow
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.GraphNode
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.NodeGraph
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.SwitchTrack
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.android.core.UiMessage
import app.recly.android.workflow.EditorErrors
import app.recly.android.workflow.RetryEdit
import app.recly.android.workflow.StepEdit
import app.recly.android.workflow.StepErrors
import app.recly.android.workflow.StepKind
import app.recly.android.workflow.canAdd
import app.recly.android.workflow.WorkflowEdit
import app.recly.android.workflow.tag
import recly.core.model.Language
import recly.core.model.OnError
import recly.core.transcribe.SttProviders
import recly.core.workflow.InvokeUrlUse
import recly.core.workflow.WorkflowParser

/** docs/02 "템플릿 변수", in the order the table lists them. */
private val TEMPLATE_VARS = listOf(
    "yyyy", "MM", "dd", "HH", "mm", "title", "source", "recordingId", "workflowName", "device",
)

private val NUMERIC = KeyboardOptions(keyboardType = KeyboardType.Number)

/** The trigger node has no step id; the graph's head is what a finished recording arrives at. */
private const val TRIGGER_CODE = "trigger"

/**
 * `>= 30s`, or nothing at all for the docs/02 default of 0 — a code, not a sentence, because the
 * node's detail line is monospace and a number with a unit is the same in either language.
 */
private fun String.minimumCode(): String =
    trim().takeIf { it.isNotEmpty() && it != "0" }?.let { ">= ${it}s" }.orEmpty()

/**
 * docs/11 A6, drawn as docs/09 화면 원칙 3 asks: the workflow *is* a graph — a trigger, the steps in
 * order, an end — and tapping a node opens its inspector underneath. A step is inserted from the
 * `+` on the connector it would sit on.
 *
 * `editor.openStep` carries the selection, as it always has: null selects the trigger, whose
 * inspector holds what the workflow itself is (name, minimum length).
 */
@Composable
fun WorkflowEditorScreen(
    editor: EditorState,
    secrets: List<String>,
    onEdit: ((WorkflowEdit) -> WorkflowEdit) -> Unit,
    onAddStep: (StepKind) -> Unit,
    onRemoveStep: (Int) -> Unit,
    onMoveStep: (Int, Int) -> Unit,
    onOpenStep: (Int?) -> Unit,
    onEditStep: (Int, (StepEdit) -> StepEdit) -> Unit,
    onNewSecret: (String?) -> Unit,
    onSave: () -> Unit,
    onReopen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val edit = editor.edit
    val errors = editor.errors
    val palette = blueprint
    // The `+` that was tapped, while it asks which kind of step to insert there.
    var insertAt by remember { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = edit.name.ifBlank { stringResource(if (editor.isNew) R.string.editor_new else R.string.editor_edit) },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    BlueprintButton(
                        label = stringResource(R.string.action_cancel),
                        onClick = onCancel,
                        tone = ButtonTone.QUIET,
                    )
                    ProcessingButton(
                        label = stringResource(R.string.action_save),
                        state = editor.save,
                        onClick = onSave,
                        modifier = Modifier.testTag("workflow-save"),
                        tone = ButtonTone.PRIMARY,
                        enabled = !editor.stale,
                    )
                }
            },
        )

        Notices(editor = editor, errors = errors, onReopen = onReopen)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Space.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                NodeGraph(
                    count = 1 + edit.steps.size,
                    onInsert = { insertAt = it },
                    insertLabel = stringResource(R.string.editor_insert_step),
                    modifier = Modifier.fillMaxWidth(),
                ) { index ->
                    if (index == 0) {
                        GraphNode(
                            kicker = stringResource(R.string.editor_node_trigger),
                            title = stringResource(R.string.editor_node_trigger_title),
                            // The one thing left to say about the head of the graph, as a code
                            // rather than a sentence — a minimum of 0 has nothing to say at all.
                            detail = edit.minDurationSec.minimumCode(),
                            selected = editor.openStep == null,
                            onClick = { onOpenStep(null) },
                        )
                    } else {
                        val position = index - 1
                        val step = edit.steps[position]
                        GraphNode(
                            // docs/09 화면 원칙 3: the position and what the step is — the same
                            // "3 · Drive 업로드" the other three graphs put over a node.
                            kicker = stringResource(
                                R.string.editor_step_kicker,
                                index,
                                stringResource(step.labelRes()),
                            ),
                            title = stringResource(step.labelRes()),
                            detail = step.summary(),
                            selected = editor.openStep == position,
                            onClick = { onOpenStep(position) },
                        )
                    }
                }
                BlueprintMenu(expanded = insertAt != null, onDismissRequest = { insertAt = null }) {
                    // docs/08: the menu is one entry per step type, `transcribe` among them.
                    StepKind.entries.forEach { kind ->
                        BlueprintMenuItem(
                            label = stringResource(kind.labelRes()),
                            enabled = kind.canAdd(edit.steps),
                            onClick = {
                                val at = insertAt ?: return@BlueprintMenuItem
                                insertAt = null
                                // `addStep` appends, so the insert is an append and a move — the two
                                // writes the ViewModel already offers, in the order they are safe in.
                                onAddStep(kind)
                                onMoveStep(edit.steps.size, at)
                                onOpenStep(at)
                            },
                            divider = kind != StepKind.entries.last(),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.editor_node_end),
                modifier = Modifier.padding(top = Space.xs),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
            )
        }

        HairLine()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val position = editor.openStep
            val step = position?.let { edit.steps.getOrNull(it) }
            if (step == null) {
                TriggerInspector(edit = edit, errors = errors, onEdit = onEdit)
            } else {
                StepInspector(
                    step = step,
                    index = position,
                    count = edit.steps.size,
                    errors = errors.steps[step.id],
                    secrets = secrets,
                    onChange = { block -> onEditStep(position, block) },
                    onNewSecret = onNewSecret,
                    onMove = { to ->
                        onMoveStep(position, to)
                        onOpenStep(to)
                    },
                    onRemove = {
                        onRemoveStep(position)
                        onOpenStep(null)
                    },
                )
            }
        }
    }
}

/** The two things that can be wrong with an open editor, in the order they matter. */
@Composable
private fun Notices(editor: EditorState, errors: EditorErrors, onReopen: () -> Unit) {
    val palette = blueprint
    // Another write — an import, say — replaced this workflow while it was open. There is no
    // three-way merge, so the only honest offer is to start again from what the document says now.
    if (editor.stale) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.notice_stale),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = palette.danger,
            )
            BlueprintButton(
                label = stringResource(R.string.editor_reopen),
                onClick = onReopen,
                modifier = Modifier.testTag("workflow-reopen"),
            )
        }
    }
    errors.banner.forEach {
        Text(
            it.text(),
            modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs),
            style = MaterialTheme.typography.bodySmall,
            color = palette.danger,
        )
    }
}

/**
 * The trigger node's inspector: what the workflow is. ADR-016 left it two fields — which device
 * runs it is that device's own local pointer, set on the list, and never a field of the definition.
 */
@Composable
private fun TriggerInspector(
    edit: WorkflowEdit,
    errors: EditorErrors,
    onEdit: ((WorkflowEdit) -> WorkflowEdit) -> Unit,
) {
    InspectorTitle(stringResource(R.string.editor_node_trigger_title), TRIGGER_CODE)

    OutlinedTextField(
        value = edit.name,
        onValueChange = { value -> onEdit { it.copy(name = value) } },
        label = { Text(stringResource(R.string.editor_name)) },
        singleLine = true,
        isError = errors.name != null,
        supportingText = errors.name?.let { { Text(it.text()) } },
        modifier = Modifier.fillMaxWidth().testTag("workflow-name"),
    )

    OutlinedTextField(
        value = edit.minDurationSec,
        onValueChange = { value -> onEdit { it.copy(minDurationSec = value) } },
        label = { Text(stringResource(R.string.editor_min_duration)) },
        singleLine = true,
        keyboardOptions = NUMERIC,
        isError = errors.minDuration != null,
        supportingText = {
            Text(errors.minDuration?.text() ?: stringResource(R.string.editor_min_duration_hint))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A step node's inspector: what the step needs, then where it sits and whether it stays. */
@Composable
private fun StepInspector(
    step: StepEdit,
    index: Int,
    count: Int,
    errors: StepErrors?,
    secrets: List<String>,
    onChange: ((StepEdit) -> StepEdit) -> Unit,
    onNewSecret: (String?) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val palette = blueprint
    InspectorTitle(stringResource(step.labelRes()), step.id)

    // docs/08: an order constraint is broken by moving a step, not by typing in it, so it is said
    // here as soon as it is true rather than waiting for the save to be refused.
    errors?.order?.let {
        Text(
            stringResource(R.string.editor_order_transcribe_needs_upload),
            color = palette.danger,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    when (step) {
        is StepEdit.Drive -> DriveFields(step, errors, onChange)
        is StepEdit.Hook -> HookFields(step, errors, secrets, onChange, onNewSecret)
        is StepEdit.Transcribe -> TranscribeFields(step, errors, secrets, onChange, onNewSecret)
    }

    SectionHeader(stringResource(R.string.editor_retry))
    RetryFields(step.retry, errors) { retry -> onChange { it.withCommon(retry = retry) } }

    SectionHeader(stringResource(R.string.editor_on_error))
    ChipRow {
        OnError.entries.forEach { value ->
            BlueprintChip(
                label = stringResource(
                    if (value == OnError.ABORT) {
                        R.string.editor_on_error_abort
                    } else {
                        R.string.editor_on_error_continue
                    },
                ),
                selected = step.onError == value,
                onClick = { onChange { it.withCommon(onError = value) } },
            )
        }
    }
    errors?.other?.forEach {
        Text(it.text(), color = palette.danger, style = MaterialTheme.typography.bodySmall)
    }

    // The order of the steps is the order of the graph, so it is moved here rather than dragged.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        BlueprintButton(
            label = stringResource(R.string.editor_move_up),
            onClick = { onMove(index - 1) },
            modifier = Modifier.testTag("step-up-$index"),
            tone = ButtonTone.QUIET,
            enabled = index > 0,
        )
        BlueprintButton(
            label = stringResource(R.string.editor_move_down),
            onClick = { onMove(index + 1) },
            modifier = Modifier.testTag("step-down-$index"),
            tone = ButtonTone.QUIET,
            enabled = index < count - 1,
        )
        BlueprintButton(
            label = stringResource(R.string.action_delete),
            onClick = onRemove,
            tone = ButtonTone.DANGER,
        )
    }
}

/** A row of chips: one rhythm for all of them, and it wraps rather than clipping the last one. */
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

@Composable
private fun InspectorTitle(title: String, code: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = blueprint.text,
        )
        Text(code, style = mono.small, color = blueprint.textMuted, maxLines = 1)
    }
}

@Composable
private fun InspectorSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // Toggleable on the row, not on the track: one node with a name, so TalkBack says
    // "<title>, switch, on" instead of an unnamed "switch, on" (docs/09 "접근성").
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = blueprint.text,
        )
        SwitchTrack(checked = checked)
    }
}

@Composable
private fun DriveFields(
    step: StepEdit.Drive,
    errors: StepErrors?,
    onChange: ((StepEdit) -> StepEdit) -> Unit,
) {
    val palette = blueprint
    // TextFieldValue, not String: a variable chip inserts at the caret, so the caret has to be ours.
    var field by remember(step.id) {
        mutableStateOf(TextFieldValue(step.folder, TextRange(step.folder.length)))
    }

    OutlinedTextField(
        value = field,
        onValueChange = { value ->
            field = value
            onChange { (it as StepEdit.Drive).copy(folder = value.text) }
        },
        label = { Text(stringResource(R.string.editor_folder)) },
        textStyle = mono.bodySmall,
        isError = errors?.folder != null,
        supportingText = { Text(errors?.folder?.text() ?: stringResource(R.string.editor_folder_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("step-folder"),
    )
    // These insert rather than choose, so they stay buttons — square and monospace, like the
    // variables themselves, and never Material's pill.
    ChipRow {
        TEMPLATE_VARS.forEach { name ->
            BlueprintButton(
                label = name,
                onClick = {
                    val inserted = field.insert("{{$name}}")
                    field = inserted
                    onChange { (it as StepEdit.Drive).copy(folder = inserted.text) }
                },
                tone = ButtonTone.QUIET,
                monospace = true,
            )
        }
    }

    InspectorSwitch(stringResource(R.string.editor_include_meta), step.includeMeta) { value ->
        onChange { (it as StepEdit.Drive).copy(includeMeta = value) }
    }
}

@Composable
private fun HookFields(
    step: StepEdit.Hook,
    errors: StepErrors?,
    secrets: List<String>,
    onChange: ((StepEdit) -> StepEdit) -> Unit,
    onNewSecret: (String?) -> Unit,
) {
    OutlinedTextField(
        value = step.url,
        onValueChange = { value -> onChange { (it as StepEdit.Hook).copy(url = value) } },
        label = { Text(stringResource(R.string.editor_url)) },
        singleLine = true,
        textStyle = mono.bodySmall,
        isError = errors?.url != null,
        supportingText = { Text(errors?.url?.text() ?: stringResource(R.string.editor_url_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("step-url"),
    )

    StepSecretPicker(
        header = stringResource(R.string.editor_secret),
        selected = step.secretRef,
        secrets = secrets,
        error = errors?.secretRef,
        onSelect = { name -> onChange { (it as StepEdit.Hook).copy(secretRef = name) } },
        onNewSecret = onNewSecret,
        onNone = { onChange { (it as StepEdit.Hook).copy(secretRef = null) } },
    )
}

@Composable
private fun RetryFields(
    retry: RetryEdit,
    errors: StepErrors?,
    onChange: (RetryEdit) -> Unit,
) {
    OutlinedTextField(
        value = retry.maxAttempts,
        onValueChange = { onChange(retry.copy(maxAttempts = it)) },
        label = { Text(stringResource(R.string.editor_max_attempts)) },
        singleLine = true,
        keyboardOptions = NUMERIC,
        isError = errors?.maxAttempts != null,
        supportingText = errors?.maxAttempts?.let { { Text(it.text()) } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = retry.initialDelaySec,
        onValueChange = { onChange(retry.copy(initialDelaySec = it)) },
        label = { Text(stringResource(R.string.editor_initial_delay)) },
        singleLine = true,
        keyboardOptions = NUMERIC,
        isError = errors?.initialDelay != null,
        supportingText = errors?.initialDelay?.let { { Text(it.text()) } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = retry.maxDelaySec,
        onValueChange = { onChange(retry.copy(maxDelaySec = it)) },
        label = { Text(stringResource(R.string.editor_max_delay)) },
        singleLine = true,
        keyboardOptions = NUMERIC,
        isError = errors?.maxDelay != null,
        supportingText = errors?.maxDelay?.let { { Text(it.text()) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun TextFieldValue.insert(text: String): TextFieldValue {
    val at = selection.start.coerceIn(0, this.text.length)
    val end = selection.end.coerceIn(at, this.text.length)
    val next = this.text.substring(0, at) + text + this.text.substring(end)
    return TextFieldValue(next, TextRange(at + text.length))
}

@Composable
private fun StepEdit.summary(): String = when (this) {
    is StepEdit.Drive -> folder
    is StepEdit.Hook -> url.ifBlank { stringResource(R.string.editor_no_url) } + (secretRef?.let { " · $it" } ?: "")
    is StepEdit.Transcribe ->
        "$provider · ${language.tag()}" + secretRef.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

/** The add-step menu's entries: what the step will be, before it exists (docs/02 · docs/08). */
@StringRes
private fun StepKind.labelRes(): Int = when (this) {
    StepKind.DRIVE -> R.string.step_drive_upload
    StepKind.HOOK -> R.string.step_webhook
    StepKind.TRANSCRIBE -> R.string.step_transcribe
}

/**
 * docs/08 `transcribe`. `invokeUrl` is an addressing scheme some providers need, some accept and
 * the rest never read (`WorkflowParser.invokeUrlUse`), so the field appears for the first two kinds
 * and the value goes with it for the third — a URL the form does not show is one the parser would
 * refuse for a field the user cannot see. Between the first two the value stays: both read it.
 */
@Composable
private fun TranscribeFields(
    step: StepEdit.Transcribe,
    errors: StepErrors?,
    secrets: List<String>,
    onChange: ((StepEdit) -> StepEdit) -> Unit,
    onNewSecret: (String?) -> Unit,
) {
    // docs/07 rule 4: a provider id is what the document carries, so the row says it verbatim and
    // in monospace. Fourteen of them are a list and not a row of chips — the same shape the
    // settings language picker uses, in `WorkflowParser.STT_PROVIDERS` order, which is the order
    // the core declares and not one this screen sorts.
    val providerLabel = stringResource(R.string.editor_provider)
    SectionHeader(providerLabel)
    var pickingProvider by rememberSaveable { mutableStateOf(false) }
    BlueprintButton(
        label = step.provider,
        onClick = { pickingProvider = true },
        // docs/09 "접근성": the button says the value, and the header above it says what the value
        // is of — a reader given only the value would hear "openai, button" and no question.
        modifier = Modifier
            .semantics {
                contentDescription = providerLabel
                stateDescription = step.provider
            }
            .testTag("step-provider"),
        tone = ButtonTone.QUIET,
        monospace = true,
    )
    // docs/08 "폴링 · 상태": a provider that answers on one long request is the one a phone's
    // background budget may cut off, and this is where that choice is made.
    if (SttProviders.synchronous(step.provider)) {
        Text(
            stringResource(R.string.editor_provider_synchronous_hint),
            modifier = Modifier.testTag("provider-synchronous-hint"),
            style = MaterialTheme.typography.bodySmall,
            color = blueprint.textMuted,
        )
    }
    if (pickingProvider) {
        BlueprintDialog(
            title = providerLabel,
            onDismissRequest = { pickingProvider = false },
            actions = {
                // Nothing to cancel: the choice is applied the moment it is made, so the one answer
                // here closes a question that has already been answered.
                BlueprintButton(
                    label = stringResource(R.string.action_close),
                    onClick = { pickingProvider = false },
                    tone = ButtonTone.QUIET,
                )
            },
        ) {
            WorkflowParser.STT_PROVIDERS.forEach { name ->
                BlueprintRadioRow(
                    label = name,
                    selected = step.provider == name,
                    onSelect = {
                        pickingProvider = false
                        onChange { current ->
                            val transcribe = current as StepEdit.Transcribe
                            transcribe.copy(
                                provider = name,
                                // An empty URL a provider requires starts as that provider's
                                // template, so the user edits a URL instead of composing one.
                                invokeUrl = when (WorkflowParser.invokeUrlUse(name)) {
                                    InvokeUrlUse.NONE -> ""
                                    else -> transcribe.invokeUrl.ifEmpty {
                                        WorkflowParser.invokeUrlTemplate(name).orEmpty()
                                    }
                                },
                            )
                        }
                    },
                    modifier = Modifier.testTag("provider-$name"),
                )
            }
        }
    }

    ProviderDisclosure(
        id = R.string.provider_disclosure_transcribe,
        testTag = "disclosure-transcribe",
    )

    StepSecretPicker(
        header = stringResource(R.string.editor_api_key),
        selected = step.secretRef,
        secrets = secrets,
        error = errors?.secretRef,
        onSelect = { name -> onChange { (it as StepEdit.Transcribe).copy(secretRef = name) } },
        onNewSecret = onNewSecret,
    )

    val invokeUrlUse = WorkflowParser.invokeUrlUse(step.provider)
    if (invokeUrlUse != InvokeUrlUse.NONE) {
        OutlinedTextField(
            value = step.invokeUrl,
            onValueChange = { value -> onChange { (it as StepEdit.Transcribe).copy(invokeUrl = value) } },
            label = { Text(stringResource(R.string.editor_invoke_url)) },
            singleLine = true,
            textStyle = mono.bodySmall,
            supportingText = {
                Text(
                    stringResource(
                        if (invokeUrlUse == InvokeUrlUse.REQUIRED) {
                            R.string.editor_invoke_url_hint_required
                        } else {
                            R.string.editor_invoke_url_hint_optional
                        },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().testTag("step-invoke-url"),
        )
    }

    SectionHeader(stringResource(R.string.editor_language))
    ChipRow {
        Language.entries.forEach { language ->
            BlueprintChip(
                // docs/02 writes the language as its tag; a tag is a code, not a sentence.
                label = language.tag(),
                selected = step.language == language,
                onClick = { onChange { (it as StepEdit.Transcribe).copy(language = language) } },
                monospace = true,
            )
        }
    }

    InspectorSwitch(stringResource(R.string.editor_diarize), step.diarize) { value ->
        onChange { (it as StepEdit.Transcribe).copy(diarize = value) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(
            value = step.speakersMin,
            onValueChange = { value -> onChange { (it as StepEdit.Transcribe).copy(speakersMin = value) } },
            label = { Text(stringResource(R.string.editor_speakers_min)) },
            singleLine = true,
            keyboardOptions = NUMERIC,
            enabled = step.diarize,
            modifier = Modifier.weight(1f).testTag("step-speakers-min"),
        )
        OutlinedTextField(
            value = step.speakersMax,
            onValueChange = { value -> onChange { (it as StepEdit.Transcribe).copy(speakersMax = value) } },
            label = { Text(stringResource(R.string.editor_speakers_max)) },
            singleLine = true,
            keyboardOptions = NUMERIC,
            enabled = step.diarize,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(R.string.editor_speakers_hint),
        style = MaterialTheme.typography.bodySmall,
        color = blueprint.textMuted,
    )
}

/**
 * docs/15 §3: what actually leaves the phone when this step runs, next to the row that decides
 * where it goes. Three sentences and no more — what is sent, whose policy decides how long it is
 * kept and whether it is trained on, and that the policy is the provider's to read. No "kept for N
 * days" anywhere: docs/15 forbids stating a retention this app cannot see, and there is no link
 * until the per-provider policy URLs of the docs/15 table are confirmed.
 */
@Composable
private fun ProviderDisclosure(@StringRes id: Int, testTag: String) {
    Text(
        stringResource(id),
        modifier = Modifier.padding(top = Space.xs).testTag(testTag),
        style = MaterialTheme.typography.bodySmall,
        color = blueprint.textMuted,
    )
}

/**
 * docs/05 "시크릿": the names live in the document, the values only on the device that entered
 * them. A `secretRef` this device has no value for is offered for entry right here, rather than
 * sending the user to another screen to find out what is missing.
 *
 * @param onNone non-null only where the key is optional — a webhook's is (docs/08 has no transcribe
 * provider that runs without one), and that is the whole "none" chip.
 */
@Composable
private fun StepSecretPicker(
    header: String,
    selected: String?,
    secrets: List<String>,
    error: UiMessage?,
    onSelect: (String) -> Unit,
    onNewSecret: (String?) -> Unit,
    onNone: (() -> Unit)? = null,
) {
    val palette = blueprint
    SectionHeader(header)
    ChipRow {
        onNone?.let { none ->
            BlueprintChip(
                label = stringResource(R.string.editor_secret_none),
                selected = selected == null,
                onClick = none,
            )
        }
        secrets.forEach { name ->
            BlueprintChip(
                label = name,
                selected = selected == name,
                onClick = { onSelect(name) },
                monospace = true,
            )
        }
        BlueprintButton(
            label = stringResource(R.string.editor_secret_new),
            onClick = { onNewSecret(null) },
            modifier = Modifier.testTag("step-new-secret"),
        )
    }
    error?.let { Text(it.text(), color = palette.danger, style = MaterialTheme.typography.bodySmall) }

    // docs/05 "시크릿": the definition names the key; the value is this device's own.
    val missing = selected?.takeIf { it.isNotBlank() && it !in secrets }
    if (missing != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.editor_secret_missing, missing),
                modifier = Modifier.weight(1f),
                color = palette.danger,
                style = MaterialTheme.typography.bodySmall,
            )
            BlueprintButton(stringResource(R.string.editor_secret_enter), { onNewSecret(missing) })
        }
    }
}
