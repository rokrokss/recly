@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintDropdown
import app.recly.windows.ui.component.BlueprintMenu
import app.recly.windows.ui.component.BlueprintMenuItem
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.GraphNode
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.NodeGraph
import app.recly.windows.ui.component.Placeholder
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.SectionHeader
import app.recly.windows.ui.component.SidebarRow
import app.recly.windows.ui.component.SidebarWidth
import app.recly.windows.ui.component.SwitchRow
import app.recly.windows.ui.component.VerticalHairLine
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.dotGrid
import app.recly.windows.ui.theme.mono
import app.recly.windows.workflow.StepEdit
import app.recly.windows.workflow.StepKind
import app.recly.windows.workflow.canAdd
import app.recly.windows.workflow.label
import app.recly.windows.workflow.tag
import kotlinx.coroutines.launch
import recly.core.model.Language
import recly.core.model.OnError
import recly.core.workflow.InvokeUrlUse
import recly.core.workflow.WorkflowParser

/**
 * docs/14 N6 · deliverable 4, drawn as docs/09 화면 원칙 3 asks: the workflow *is* a graph — a
 * trigger, its steps in order, an end — laid out left to right across the window, with the node's
 * inspector under it and the list of workflows and secrets down the side.
 *
 * It draws [WorkflowsModel] and calls it; every rule it appears to have — what a save does, what a
 * stale editor may not do — lives there and in the core's
 * `WorkflowMutator`.
 */
@Composable
fun WorkflowEditorWindow(
    model: WorkflowsModel,
    strings: Strings,
    openFirst: Boolean = false,
    /** Which step's inspector to open on — `--step`, so a form can be photographed (`DevFlags`). */
    openStep: Int? = null,
) {
    val scope = rememberCoroutineScope()
    val go: Go = { block -> scope.launch { block() } }
    LaunchedEffect(model, openFirst) {
        model.reload()
        // Only a `--show-editor` run asks for this: a window opened by a person opens on nothing,
        // because which workflow they want is not something this app gets to decide for them.
        if (openFirst) model.items.firstOrNull()?.let { model.edit(it.id) }
    }

    Row(Modifier.fillMaxSize().background(blueprint.background)) {
        Sidebar(model, strings, go, Modifier.width(SidebarWidth).fillMaxHeight())
        VerticalHairLine(Modifier.fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val editor = model.editor
            when {
                model.loading -> Placeholder(strings[Str.STATUS_OPENING])
                editor == null -> Placeholder(strings[Str.EDITOR_PICK_WORKFLOW])
                else -> EditorPane(model, editor, strings, go, openStep)
            }
        }
    }
}

/** Runs a suspending model call from a click, on the window's composition scope. */
private typealias Go = (suspend () -> Unit) -> Unit

// --- the side (docs/09 화면 원칙 4: a list is a table) ---------------------------------------------

@Composable
private fun Sidebar(model: WorkflowsModel, strings: Strings, go: Go, modifier: Modifier) {
    Column(modifier.background(blueprint.surface).verticalScroll(rememberScrollState())) {
        ScreenHeader(title = strings[Str.EDITOR_WORKFLOWS])
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            BlueprintButton(strings[Str.EDITOR_NEW_WORKFLOW], model::add)
        }
        HairLine()
        model.items.forEach { item ->
            val name = item.name.ifBlank { strings[Str.UNNAMED] }
            WorkflowRow(
                title = name,
                // The step kinds are *translated labels*, so they are sans: the monospace face this
                // app gets from Windows (Consolas) carries no Hangul at all.
                detail = item.steps.joinToString(" → ") { strings[it] },
                selected = model.editor?.edit?.id == item.id,
                inUse = item.isDeviceDefault,
                inUseLabel = strings[Str.WORKFLOW_IN_USE],
                useLabel = strings[Str.WORKFLOW_USE],
                onOpen = { model.edit(item.id) },
                onUse = { go { model.setDefault(item) } },
                // ADR-016: deleting it is allowed, and what it costs is said in the confirmation
                // rather than on the row — the row is not where the answer is given.
                onDelete = { model.askToDelete(item) },
                deleteLabel = strings[Str.DELETE],
            )
            // docs/05 "시크릿": the definition names the key; the value is this device's own.
            if (item.missingSecrets.isNotEmpty()) {
                BlueprintButton(
                    label = strings[Str.EDITOR_MISSING_KEY, item.missingSecrets.joinToString(", ")],
                    onClick = { model.openSecrets(item.missingSecrets.first()) },
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs),
                    tone = ButtonTone.DANGER,
                )
            }
        }
        Secrets(model, strings, go)
    }
}

/**
 * ADR-016: name, steps, and the one thing a row decides — whether this PC runs it. The badge and the
 * button are the same control seen from its two states, so exactly one of them shows; the delete is
 * beside it and is the only one of the two that writes to the document.
 */
@Composable
private fun WorkflowRow(
    title: String,
    detail: String,
    selected: Boolean,
    inUse: Boolean,
    inUseLabel: String,
    useLabel: String,
    onOpen: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    deleteLabel: String,
) {
    SidebarRow(
        title = title,
        selected = selected,
        onOpen = onOpen,
        controls = {
            // The mark is a word rather than a colour (docs/09 화면 원칙 2), and it sits where the
            // button it replaces sat, at the same height as the one beside it.
            if (inUse) {
                Text(
                    inUseLabel,
                    modifier = Modifier.padding(horizontal = Space.s, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = blueprint.accent,
                    maxLines = 1,
                )
            } else {
                BlueprintButton(useLabel, onUse, tone = ButtonTone.QUIET)
            }
            Box(Modifier.weight(1f))
            BlueprintButton(deleteLabel, onDelete, tone = ButtonTone.DANGER)
        },
    ) {
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = blueprint.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** docs/05 "시크릿": the names live in the document, the values only ever here. */
@Composable
private fun Secrets(model: WorkflowsModel, strings: Strings, go: Go) {
    val palette = blueprint
    SectionHeader(strings[Str.SECRETS_TITLE], Modifier.padding(horizontal = Space.m))
    HairLine()
    model.secretNames.forEach { name ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, Modifier.weight(1f), style = mono.small, color = palette.text)
            BlueprintButton(strings[Str.DELETE], { go { model.deleteSecret(name) } }, tone = ButtonTone.DANGER)
        }
    }
    // A form a step opened belongs to that step, and is shown there rather than here.
    val form = model.secretForm?.takeIf { it.stepId == null }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (form == null) {
            BlueprintButton(strings[Str.SECRET_ADD], { model.openSecrets() })
        } else {
            SecretFormFields(model, form, strings, go)
        }
    }
}

/**
 * The name and the value, wherever they are asked for: the list above, or the step that needs a key
 * it does not have yet. [onSaved] is how that step learns the name to put in its `secretRef` — the
 * value goes to the store and never reaches the document (docs/05 "시크릿").
 */
@Composable
private fun SecretFormFields(
    model: WorkflowsModel,
    form: SecretForm,
    strings: Strings,
    go: Go,
    onSaved: (String) -> Unit = {},
) {
    val palette = blueprint
    BlueprintTextField(
        value = form.name,
        onValueChange = model::secretName,
        label = strings[Str.SECRET_NAME_LABEL],
        modifier = Modifier.fillMaxWidth(),
    )
    BlueprintTextField(
        value = form.value,
        onValueChange = model::secretValue,
        label = strings[Str.SECRET_VALUE_LABEL],
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.generated) {
        // docs/04: shown once and never readable again — the clipboard already has it.
        Text(
            strings[Str.SECRET_GENERATED_NOTE],
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
    }
    form.error?.let {
        Text(strings[it], style = MaterialTheme.typography.bodySmall, color = palette.danger)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        BlueprintButton(
            label = strings[Str.SAVE],
            onClick = { go { model.saveSecret()?.let(onSaved) } },
            tone = ButtonTone.PRIMARY,
        )
        BlueprintButton(strings[Str.SECRET_GENERATE], model::generateSecret, tone = ButtonTone.QUIET)
        if (form.generated) {
            BlueprintButton(strings[Str.SECRET_COPY_AGAIN], model::copyGenerated, tone = ButtonTone.QUIET)
        }
        BlueprintButton(strings[Str.CANCEL], model::closeSecrets, tone = ButtonTone.QUIET)
    }
}

// --- the canvas and the inspector (docs/09 화면 원칙 3) --------------------------------------------

@Composable
private fun ColumnScope.EditorPane(
    model: WorkflowsModel,
    editor: EditorState,
    strings: Strings,
    go: Go,
    openStep: Int?,
) {
    val palette = blueprint
    // Which node the inspector is showing; null is the trigger. Held here rather than in the model:
    // it is where the user is looking, and nothing outside this window has an opinion about it.
    var selected by remember(editor.edit.id) { mutableStateOf(openStep) }
    // The `+` that was clicked, while it asks which kind of step to insert there — and where on the
    // canvas it was, so the menu opens under it rather than at the origin of the graph.
    var insertAt by remember(editor.edit.id) { mutableStateOf<Int?>(null) }
    var insertOffset by remember(editor.edit.id) { mutableStateOf(IntOffset.Zero) }
    val steps = editor.edit.steps

    ScreenHeader(
        title = editor.edit.name.ifBlank { strings[Str.UNNAMED] },
        meta = editor.edit.id,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                BlueprintButton(strings[Str.CANCEL], model::cancel, tone = ButtonTone.QUIET)
                ProcessingButton(
                    label = strings[Str.SAVE],
                    state = model.action,
                    strings = strings,
                    onClick = { go { model.save() } },
                    tone = ButtonTone.PRIMARY,
                    enabled = !editor.stale,
                )
            }
        },
    )
    Notices(model, editor, strings, go)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .dotGrid(palette)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.m, vertical = Space.l),
    ) {
        NodeGraph(
            count = 1 + steps.size,
            onInsert = { index, at ->
                insertAt = index
                insertOffset = at
            },
            insertLabel = strings[Str.EDITOR_INSERT_STEP],
        ) { index ->
            if (index == 0) {
                GraphNode(
                    kicker = strings[Str.EDITOR_NODE_TRIGGER],
                    title = strings[Str.EDITOR_NODE_TRIGGER_TITLE],
                    // The one thing left to say about the head of the graph, as a code rather than
                    // a sentence — a minimum of 0 has nothing to say at all.
                    detail = editor.edit.minDurationSec.minimumCode(),
                    selected = selected == null,
                    onClick = { selected = null },
                )
            } else {
                val position = index - 1
                val step = steps[position]
                GraphNode(
                    // docs/09 화면 원칙 3: the position and what the step is — the same
                    // "3 · Drive 업로드" the other three graphs put over a node.
                    kicker = strings[Str.EDITOR_STEP_KICKER, index, strings[step.label()]],
                    // No code slot: the other three graphs have none, and the step id still shows
                    // in the inspector's title (docs/recly.md §7 rule 11 — one surface, one shape).
                    title = strings[step.label()],
                    detail = step.summary(strings),
                    selected = selected == position,
                    onClick = { selected = position },
                )
            }
        }
        BlueprintMenu(
            expanded = insertAt != null,
            onDismissRequest = { insertAt = null },
            offset = insertOffset,
        ) {
            STEP_KINDS.forEach { (kind, label) ->
                BlueprintMenuItem(
                    label = strings[label],
                    enabled = kind.canAdd(steps),
                    onClick = {
                        val at = insertAt
                        if (at != null) {
                            insertAt = null
                            // `addStep` appends, so an insert is an append and a move — the two
                            // writes the model already offers, in the order they are safe in.
                            model.addStep(kind)
                            model.moveStep(steps.size, at)
                            selected = at
                        }
                    },
                )
            }
        }
    }
    Text(
        strings[Str.EDITOR_NODE_END],
        modifier = Modifier.padding(horizontal = Space.m).padding(bottom = Space.s),
        style = MaterialTheme.typography.bodySmall,
        color = palette.textMuted,
    )
    HairLine()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.m, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val position = selected?.takeIf { it in steps.indices }
        if (position == null) {
            TriggerInspector(model, editor, strings)
        } else {
            StepInspector(
                model = model,
                step = steps[position],
                index = position,
                count = steps.size,
                secrets = model.secretNames,
                order = editor.order[steps[position].id],
                strings = strings,
                go = go,
                onMove = { to ->
                    model.moveStep(position, to)
                    selected = to
                },
                onRemove = {
                    model.removeStep(position)
                    selected = null
                },
            )
        }
    }
}

/** The three things that can be wrong with an open editor, in the order they matter. */
@Composable
private fun Notices(model: WorkflowsModel, editor: EditorState, strings: Strings, go: Go) {
    val palette = blueprint
    // A pull replaced this workflow while it was open. v1 has no three-way merge, so the only
    // honest offer is to start again from what the document says now.
    if (editor.stale) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings[STALE_NOTICE],
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = palette.danger,
            )
            BlueprintButton(strings[Str.EDITOR_REOPEN], { go { model.reopen() } })
        }
    }
    editor.errors.forEach { Notice(it) }
    model.message?.let {
        Text(
            it.text(strings),
            modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
    }
    // docs/07 §5: a core code's `|detail` is a diagnostic, never translated, and it goes under the
    // sentence in monospace rather than inside it.
    model.messageDetail?.let {
        Text(
            it,
            modifier = Modifier.padding(horizontal = Space.m).padding(bottom = Space.xs),
            style = mono.small,
            color = palette.textMuted,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs),
        style = MaterialTheme.typography.bodySmall,
        color = blueprint.danger,
    )
}

/**
 * The trigger node's inspector: what the workflow is. ADR-016 left it two fields — which device
 * runs it is that device's own local pointer, set on the list, and never a field of the definition.
 */
@Composable
private fun TriggerInspector(model: WorkflowsModel, editor: EditorState, strings: Strings) {
    InspectorTitle(strings[Str.EDITOR_NODE_TRIGGER_TITLE], TRIGGER_CODE)
    BlueprintTextField(
        value = editor.edit.name,
        onValueChange = { value -> model.update { it.copy(name = value) } },
        label = strings[Str.FIELD_NAME],
        // A workflow's name is something a person writes, not a field of data.
        monospace = false,
        modifier = Modifier.fillMaxWidth(),
    )
    BlueprintTextField(
        value = editor.edit.minDurationSec,
        onValueChange = { value -> model.update { it.copy(minDurationSec = value) } },
        label = strings[Str.FIELD_MIN_DURATION],
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A step node's inspector: what the step needs, then where it sits and whether it stays. */
@Composable
private fun StepInspector(
    model: WorkflowsModel,
    step: StepEdit,
    index: Int,
    count: Int,
    secrets: List<String>,
    order: String?,
    strings: Strings,
    go: Go,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    InspectorTitle(strings[step.label()], step.id)
    // docs/08: an order constraint is broken by moving a step, not by typing in it, so it is said
    // as soon as it is true rather than when the save is refused.
    order?.let { Notice(strings[Str.EDITOR_ORDER_TRANSCRIBE_NEEDS_UPLOAD]) }
    when (step) {
        is StepEdit.Drive -> DriveFields(model, step, index, strings)
        is StepEdit.Hook -> HookFields(model, step, index, secrets, strings)
        is StepEdit.Transcribe -> TranscribeFields(model, step, index, secrets, strings, go)
    }

    SectionHeader(strings[Str.EDITOR_RETRY])
    NumberField(strings[Str.FIELD_RETRIES], step.retry.maxAttempts) { value ->
        model.updateStep(index) { it.withCommon(retry = it.retry.copy(maxAttempts = value)) }
    }
    NumberField(strings[Str.FIELD_FIRST_DELAY], step.retry.initialDelaySec) { value ->
        model.updateStep(index) { it.withCommon(retry = it.retry.copy(initialDelaySec = value)) }
    }
    NumberField(strings[Str.FIELD_MAX_DELAY], step.retry.maxDelaySec) { value ->
        model.updateStep(index) { it.withCommon(retry = it.retry.copy(maxDelaySec = value)) }
    }
    // docs/09: what happens after a failure is a choice of one, so it is a chip row and not a
    // switch — the same "Stop · Continue" pair the phones and the Mac show.
    SectionHeader(strings[Str.FIELD_ON_ERROR])
    ChipFlow {
        OnError.entries.forEach { value ->
            BlueprintChip(
                label = strings[
                    if (value == OnError.ABORT) Str.ON_ERROR_ABORT else Str.ON_ERROR_CONTINUE,
                ],
                selected = step.onError == value,
                onClick = { model.updateStep(index) { it.withCommon(onError = value) } },
            )
        }
    }

    // The order of the steps is the order of the graph, so it is moved here rather than dragged.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        BlueprintButton(
            label = strings[Str.EDITOR_MOVE_EARLIER],
            onClick = { onMove(index - 1) },
            tone = ButtonTone.QUIET,
            enabled = index > 0,
        )
        BlueprintButton(
            label = strings[Str.EDITOR_MOVE_LATER],
            onClick = { onMove(index + 1) },
            tone = ButtonTone.QUIET,
            enabled = index < count - 1,
        )
        BlueprintButton(strings[Str.DELETE], onRemove, tone = ButtonTone.DANGER)
    }
}

@Composable
private fun DriveFields(model: WorkflowsModel, step: StepEdit.Drive, index: Int, strings: Strings) {
    BlueprintTextField(
        value = step.folder,
        onValueChange = { value ->
            model.updateStep(index) { (it as StepEdit.Drive).copy(folder = value) }
        },
        label = strings[Str.FIELD_FOLDER],
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(strings[Str.FIELD_INCLUDE_META], step.includeMeta, onCheckedChange = { value ->
        model.updateStep(index) { (it as StepEdit.Drive).copy(includeMeta = value) }
    })
}

@Composable
private fun HookFields(
    model: WorkflowsModel,
    step: StepEdit.Hook,
    index: Int,
    secrets: List<String>,
    strings: Strings,
) {
    BlueprintTextField(
        value = step.url,
        onValueChange = { value -> model.updateStep(index) { (it as StepEdit.Hook).copy(url = value) } },
        label = strings[Str.FIELD_URL],
        modifier = Modifier.fillMaxWidth(),
    )
    SectionHeader(strings[Str.FIELD_SECRET_NAME])
    ChipFlow {
        BlueprintChip(
            label = strings[Str.LABEL_NONE],
            selected = step.secretRef.isNullOrBlank(),
            onClick = { model.updateStep(index) { (it as StepEdit.Hook).copy(secretRef = null) } },
        )
        secrets.forEach { name ->
            BlueprintChip(
                label = name,
                selected = step.secretRef == name,
                // A secret name is an identifier the document carries, not a word.
                monospace = true,
                onClick = { model.updateStep(index) { (it as StepEdit.Hook).copy(secretRef = name) } },
            )
        }
    }
    // docs/05 "새 기기": the name synced, the value did not.
    val missing = step.secretRef?.takeIf { it.isNotBlank() && it !in secrets }
    if (missing != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings[Str.EDITOR_MISSING_KEY, missing],
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = blueprint.danger,
            )
            BlueprintButton(strings[Str.SECRET_ADD], { model.openSecrets(missing) })
        }
    }
}

/**
 * docs/08 `transcribe`. `invokeUrl` is an addressing scheme some providers need, some accept and the
 * rest never read (`WorkflowParser.invokeUrlUse`), so the field is shown for the first two kinds and
 * the value goes with it for the third — a URL the form does not show is one the parser would refuse
 * for a field the user cannot see. Between the first two the value stays: both read it.
 */
@Composable
private fun TranscribeFields(
    model: WorkflowsModel,
    step: StepEdit.Transcribe,
    index: Int,
    secrets: List<String>,
    strings: Strings,
    go: Go,
) {
    // Fourteen provider ids are a list and not a row of chips — the same dropdown the settings
    // language row uses, in `WorkflowParser.STT_PROVIDERS` order, which is the order the core
    // declares and not one this window sorts. A provider id goes into the document verbatim, so it
    // is drawn as data.
    SectionHeader(strings[Str.FIELD_PROVIDER])
    BlueprintDropdown(
        label = strings[Str.FIELD_PROVIDER],
        options = WorkflowParser.STT_PROVIDERS.map { it to it },
        selected = step.provider,
        onSelect = { name ->
            model.updateStep(index) { edit ->
                val transcribe = edit as StepEdit.Transcribe
                transcribe.copy(
                    provider = name,
                    // An empty URL a provider requires starts as that provider's template, so the
                    // user edits a URL instead of composing one.
                    invokeUrl = when (WorkflowParser.invokeUrlUse(name)) {
                        InvokeUrlUse.NONE -> ""
                        else -> transcribe.invokeUrl.ifEmpty {
                            WorkflowParser.invokeUrlTemplate(name).orEmpty()
                        }
                    },
                )
            }
        },
        monospace = true,
    )
    ProviderDisclosure(strings[Str.PROVIDER_DISCLOSURE_TRANSCRIBE])
    SecretPicker(model, index, step.id, step.secretRef, secrets, strings, go) { edit, value ->
        (edit as StepEdit.Transcribe).copy(secretRef = value)
    }

    val invokeUrlUse = WorkflowParser.invokeUrlUse(step.provider)
    if (invokeUrlUse != InvokeUrlUse.NONE) {
        BlueprintTextField(
            value = step.invokeUrl,
            onValueChange = { value ->
                model.updateStep(index) { (it as StepEdit.Transcribe).copy(invokeUrl = value) }
            },
            label = strings[Str.FIELD_INVOKE_URL],
            hint = strings[
                if (invokeUrlUse == InvokeUrlUse.REQUIRED) {
                    Str.FIELD_INVOKE_URL_HINT_REQUIRED
                } else {
                    Str.FIELD_INVOKE_URL_HINT_OPTIONAL
                },
            ],
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionHeader(strings[Str.FIELD_LANGUAGE])
    ChipFlow {
        Language.entries.forEach { language ->
            BlueprintChip(
                label = language.tag(),
                selected = step.language == language,
                // A BCP-47 tag is data, not a word.
                monospace = true,
                onClick = {
                    model.updateStep(index) { (it as StepEdit.Transcribe).copy(language = language) }
                },
            )
        }
    }

    SwitchRow(strings[Str.FIELD_DIARIZE], step.diarize, onCheckedChange = { value ->
        model.updateStep(index) { (it as StepEdit.Transcribe).copy(diarize = value) }
    })
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        NumberField(strings[Str.FIELD_SPEAKERS_MIN], step.speakersMin) { value ->
            model.updateStep(index) { (it as StepEdit.Transcribe).copy(speakersMin = value) }
        }
        NumberField(strings[Str.FIELD_SPEAKERS_MAX], step.speakersMax) { value ->
            model.updateStep(index) { (it as StepEdit.Transcribe).copy(speakersMax = value) }
        }
    }
    Text(
        strings[Str.FIELD_SPEAKERS_HINT],
        style = MaterialTheme.typography.bodySmall,
        color = blueprint.textMuted,
    )
}

/**
 * docs/15 §3: what actually leaves this PC when the step runs, and whose policy decides what becomes
 * of it afterwards. Three sentences, the same three the phone and the Mac show word for word
 * (`ProviderDisclosureTest`), and deliberately **no link and no retention claim** — the provider
 * policy URLs are not settled, and docs/15's "작성 규칙" forbids inventing one or writing "kept for N
 * days" over a policy this app does not control.
 *
 * It sits under the provider row because that is the choice it is about.
 */
@Composable
private fun ProviderDisclosure(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = blueprint.textMuted,
    )
}

/**
 * A row of chips that wraps. docs/09 §유동 타이포 and docs/07: a device's secret names are wider
 * than the inspector at 640dp — and a chip pushed off the window is a choice the user cannot make.
 */
@Composable
private fun ChipFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

/**
 * docs/05 "시크릿": what the document carries is the *name* of a key, never the key itself. So the
 * step picks one of the names this PC has in Credential Manager, and a key it does not have yet is
 * entered right here — the value goes to the store and only the name reaches the step.
 */
@Composable
private fun SecretPicker(
    model: WorkflowsModel,
    index: Int,
    stepId: String,
    value: String,
    secrets: List<String>,
    strings: Strings,
    go: Go,
    set: (StepEdit, String) -> StepEdit,
) {
    SectionHeader(strings[Str.FIELD_API_KEY])
    ChipFlow {
        secrets.forEach { name ->
            BlueprintChip(
                label = name,
                selected = value == name,
                monospace = true,
                onClick = { model.updateStep(index) { set(it, name) } },
            )
        }
        BlueprintButton(strings[Str.SECRET_NEW], { model.openSecrets(stepId = stepId) })
    }
    // docs/05 "새 기기": the name arrived in the document, the value did not.
    val missing = value.takeIf { it.isNotBlank() && it !in secrets }
    if (missing != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings[Str.EDITOR_MISSING_KEY, missing],
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = blueprint.danger,
            )
            BlueprintButton(strings[Str.SECRET_ADD], { model.openSecrets(missing, stepId) })
        }
    }
    // The form this step asked for, shown where it was asked for and nowhere else.
    model.secretForm?.takeIf { it.stepId == stepId }?.let { form ->
        SecretFormFields(model, form, strings, go) { name -> model.updateStep(index) { set(it, name) } }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    BlueprintTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier.width(NUMBER_FIELD),
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

private fun StepEdit.summary(strings: Strings): String = when (this) {
    is StepEdit.Drive -> folder
    is StepEdit.Hook -> url.ifBlank { strings[Str.EDITOR_NO_URL] } +
        (secretRef?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

    is StepEdit.Transcribe -> "$provider · ${language.tag()}"
}

/** What the `+` on a connector offers — one per docs/02 · docs/08 step type, in that order. */
private val STEP_KINDS = listOf(
    StepKind.DRIVE to Str.STEP_ADD_DRIVE,
    StepKind.HOOK to Str.STEP_ADD_WEBHOOK,
    StepKind.TRANSCRIBE to Str.STEP_ADD_TRANSCRIBE,
)

/** The trigger node has no step id; the graph's head is what a finished recording arrives at. */
private const val TRIGGER_CODE = "trigger"

/**
 * `>= 30s`, or nothing at all for the docs/02 default of 0 — a code, not a sentence, because the
 * node's detail line is monospace and a number with a unit is the same in either language.
 */
private fun String.minimumCode(): String =
    trim().takeIf { it.isNotEmpty() && it != "0" }?.let { ">= ${it}s" }.orEmpty()


private val NUMBER_FIELD = 160.dp
