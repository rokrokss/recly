@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import app.recly.android.R
import app.recly.android.core.coreMessage
import app.recly.android.core.text
import app.recly.android.work.modelSize
import app.recly.android.ui.component.*
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.processing.*
import java.util.Locale
import recly.core.transcribe.Qwen3Asr
import recly.core.transcribe.SttProviders
import recly.core.transcribe.TranscriptionLanguages
import recly.core.transcribe.LocalEngineStatus
import recly.core.workflow.InvokeUrlUse
import recly.core.workflow.WorkflowParser

@Composable
fun ProcessingPanel(model: ProcessingViewModel = viewModel()) {
    val resources = LocalContext.current.resources
    val state by model.state.collectAsState()
    var deletingKey by remember { mutableStateOf<String?>(null) }
    // What on-device falls back to when the language on screen is one it cannot take — the
    // language the app is in, as the iPhone reads it (see [selectTranscriptionMode]).
    val deviceLocale = LocalConfiguration.current.locales[0].toLanguageTag()
    val metered = rememberMeteredGate((state.download.info ?: state.local)?.modelBytes)
    val recorder by RecorderService.state.collectAsState()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { model.export(it) } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importSettings) }
    // The same section heading as the rest of Settings (SettingsScreen `Section`) — drawn whether or
    // not the settings could be read, so a reading that failed has somewhere to say so.
    SectionHeader(stringResource(R.string.processing_title), Modifier.padding(horizontal = Space.m))
    HairLine()
    // No body, as on the iPhone: the title names the provider, and the key's stored name is not
    // something the user ever typed.
    deletingKey?.let { name -> BlueprintDialog(title = stringResource(R.string.delete_key_title, SttProviders.displayName(name)), onDismissRequest = { deletingKey = null }, actions = {
        BlueprintButton(stringResource(R.string.action_cancel), { deletingKey = null }, tone = ButtonTone.QUIET, minWidth = MinTouch)
        BlueprintButton(stringResource(R.string.action_delete), { model.deleteKey(name); deletingKey = null }, tone = ButtonTone.DANGER, minWidth = MinTouch)
    }) { } }
    // One block of the section table, as the theme chips and the about lines are: the table's
    // surface, its insets, and its rule under it.
    Column(
        Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        val draft = state.draft
        if (draft == null) {
            // Settings that could not be read: the reason, where the settings would have been.
            state.message?.let { Text(it.text(resources), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted) }
        } else {
            // docs/05 "Fixed processing settings": on this device that means Qwen3-ASR, which has its own language list.
            val languages = if (draft.mode == TranscriptionMode.LOCAL) Qwen3Asr.languages else draft.languages
            val languageSupported = draft.mode == TranscriptionMode.OFF || draft.language in languages
            if (state.importing) Text(stringResource(R.string.processing_import_body), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
            FolderChoice(draft.folder) { v -> model.edit { it.folder = v } }
            ProcessingField(R.string.editor_min_duration, draft.minimumSeconds, monospace = true) { v -> model.edit { it.minimumSeconds = v } }
            SectionHeader(stringResource(R.string.processing_transcription))
            FillRow(Modifier.fillMaxWidth()) {
                // No engine, no chip — unless it is already chosen, so the line below can say why and the user can leave it.
                TranscriptionMode.entries.filter { it != TranscriptionMode.LOCAL || state.localInstalled || draft.mode == it }.forEach { mode ->
                    BlueprintChip(stringResource(mode.label()), draft.mode == mode, onClick = { model.selectMode(mode, deviceLocale) })
                }
            }
            if (draft.mode == TranscriptionMode.LOCAL) {
                // The model by name, as iPhone and Mac show Apple Speech; a product name, not translated.
                // A plain value and not a dropdown: there is nothing to choose, and it should not look as if there were.
                if (state.localInstalled) ProcessingRow(stringResource(R.string.processing_speech_model)) {
                    Text(Qwen3Asr.DISPLAY_NAME, style = MaterialTheme.typography.bodyMedium, color = blueprint.textMuted)
                }
                when {
                    // A language the model lacks has its own line below; this one is for the device.
                    !state.localInstalled || (state.local?.status == LocalEngineStatus.UNSUPPORTED && languageSupported) ->
                        Text(stringResource(R.string.core_local_transcription_unavailable), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                    // Not while recording, as on iPhone: the download is for later, the recording is now.
                    state.local?.status == LocalEngineStatus.MODEL_REQUIRED ->
                        ModelDownloadBlock(state, { metered(model::downloadModel) }, model::cancelDownload, recorder == RecorderState.Idle, "model")
                }
                // docs/09 "On-device speaker separation": the speaker models by name; once the speech model is here,
                // their own download when they are not, with the speech model's lines and buttons — with the speech
                // model they come together.
                if (state.localInstalled) {
                    ProcessingRow(stringResource(R.string.speaker_model)) {
                        Text(SPEAKER_MODEL_NAME, style = MaterialTheme.typography.bodyMedium, color = blueprint.textMuted)
                    }
                    val speechHere = state.local?.status.let { it != null && it != LocalEngineStatus.MODEL_REQUIRED && it != LocalEngineStatus.UNSUPPORTED }
                    if (speechHere && state.local?.supportsDiarization == false) {
                        ModelDownloadBlock(state, { metered(model::downloadModel) }, model::cancelDownload, recorder == RecorderState.Idle, "speaker-model",
                            sizeLine = R.string.processing_cellular_body)
                    }
                    Text(stringResource(R.string.speaker_model_sentences), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                }
            }
            if (draft.mode == TranscriptionMode.EXTERNAL) {
                // docs/09 principle 4: a settings row, "Provider … ElevenLabs ▾", like the app language row.
                ProcessingRow(stringResource(R.string.editor_provider)) {
                    BlueprintDropdown(
                        label = stringResource(R.string.editor_provider),
                        options = WorkflowParser.STT_PROVIDERS,
                        selected = draft.provider,
                        onSelect = { name -> model.edit { it.selectProvider(name) } },
                        title = { SttProviders.displayName(it) },
                    )
                }
                Text(stringResource(R.string.provider_disclosure_transcribe, SttProviders.displayName(draft.provider)), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                if (SttProviders.keyIsClientPair(draft.provider)) {
                    Text(stringResource(R.string.processing_key_client_pair), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                }
                ProcessingSecret(draft.secretRef, R.string.editor_api_key, state.secretNames, model::saveKey) { deletingKey = draft.secretRef }
                if (WorkflowParser.invokeUrlUse(draft.provider) != InvokeUrlUse.NONE) {
                    ProcessingField(R.string.editor_invoke_url, draft.invokeUrl, draft.invokeUrlHint, monospace = true, keyboard = URL_KEYBOARD) { v -> model.edit { it.invokeUrl = v } }
                }
                if (draft.acceptsModel) ProcessingField(R.string.processing_model, draft.model, monospace = true) { v -> model.edit { it.model = v } }
            }
            if (draft.mode != TranscriptionMode.OFF) {
                ProcessingRow(stringResource(R.string.editor_language)) {
                    BlueprintDropdown(
                        label = stringResource(R.string.editor_language),
                        options = languages,
                        selected = draft.language,
                        onSelect = { language -> model.edit { it.language = language } },
                        title = { transcriptionLanguageLabel(it) },
                    )
                }
                if (!languageSupported) {
                    Text(stringResource(R.string.processing_language_unsupported), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                }
                // docs/09 "Vocabulary": after the spoken language, part of the draft like everything above.
                Text(stringResource(R.string.vocabulary), style = MaterialTheme.typography.bodyMedium, color = blueprint.text,
                    modifier = Modifier.padding(top = Space.s))
                VocabularyEditor(draft.vocabulary) { terms -> model.edit { it.vocabulary = terms } }
                val provider = SttProviders.displayName(draft.provider)
                Text(
                    when {
                        draft.mode == TranscriptionMode.LOCAL && state.local?.supportsVocabulary == true -> stringResource(R.string.vocabulary_local)
                        draft.mode == TranscriptionMode.LOCAL -> stringResource(R.string.vocabulary_local_unsupported)
                        SttProviders.supportsVocabulary(draft.provider, draft.model.takeIf { draft.acceptsModel && it.isNotBlank() }, draft.language) ->
                            stringResource(R.string.vocabulary_external, provider)
                        else -> stringResource(R.string.vocabulary_unsupported, provider)
                    },
                    style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted,
                )
            }
            state.message?.let { Text(it.text(resources), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted) }
            // Only a draft with changes has anything to commit; the buttons appearing is the sign that it does.
            if (state.dirty) EndButtons {
                BlueprintButton(stringResource(R.string.action_cancel), { model.reload() }, tone = ButtonTone.QUIET, enabled = !state.busy, minWidth = MinTouch)
                BlueprintButton(stringResource(R.string.action_save), { model.save() }, tone = ButtonTone.PRIMARY,
                    enabled = !state.busy && languageSupported)
            }
            // Keys only matter to an external provider, so the list lives with it — after the draft's
            // own Save, since deleting a key is not part of it. The current provider's key is managed
            // on its own row above; this lists the rest.
            val others = state.secretNames.filter { it != draft.secretRef }
            if (draft.mode == TranscriptionMode.EXTERNAL && others.isNotEmpty()) {
                SectionHeader(stringResource(R.string.processing_other_keys))
                // docs/09 screen principle 8: an action that belongs to one item sits at the end of its row.
                others.forEach { name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(SttProviders.displayName(name), style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
                        BlueprintButton(stringResource(R.string.action_delete), { deletingKey = name }, tone = ButtonTone.DANGER, minWidth = MinTouch)
                    }
                }
            }
            SectionHeader(stringResource(R.string.processing_settings_file))
            EndButtons {
                BlueprintButton(stringResource(R.string.processing_export), { export.launch("recly-settings.json") }, tone = ButtonTone.QUIET,
                    enabled = state.stored is ProcessingSettingsState.Ready && !state.dirty)
                BlueprintButton(stringResource(R.string.processing_import), { importPicker.launch(arrayOf("application/json", "text/plain")) }, tone = ButtonTone.QUIET, enabled = !state.dirty)
            }
        }
    }
    HairLine()
}

/**
 * docs/05: the Drive folder template as two chips (UX decisions of 2026-10-08) — monthly folders or one
 * folder — and under them, in monospace, where today's recording would go. The draft holds the template
 * string as it was stored until a chip is tapped, so a template the user wrote elsewhere shows as monthly
 * and survives a Save of anything else.
 */
@Composable
private fun FolderChoice(folder: String, onChange: (String) -> Unit) {
    Text(stringResource(R.string.processing_storage), style = MaterialTheme.typography.bodyMedium, color = blueprint.text)
    val single = folderIsSingle(folder)
    FillRow(Modifier.fillMaxWidth()) {
        BlueprintChip(stringResource(R.string.processing_folder_monthly), !single, onClick = { folderAfterTap(folder, single = false)?.let(onChange) },
            modifier = Modifier.testTag("folder-monthly"))
        BlueprintChip(stringResource(R.string.processing_folder_single), single, onClick = { folderAfterTap(folder, single = true)?.let(onChange) },
            modifier = Modifier.testTag("folder-single"))
    }
    Text(folderPreview(folder, java.time.LocalDateTime.now()), style = mono.small, color = blueprint.textMuted,
        modifier = Modifier.testTag("folder-preview"))
}

/** The two templates the chips stand for. */
internal const val FOLDER_MONTHLY: String = "recly/memo/{{yyyy}}-{{MM}}"
internal const val FOLDER_SINGLE: String = "recly/memo"

/** Only the one-folder template is "One folder"; anything else — a template written elsewhere too — shows as monthly. */
internal fun folderIsSingle(template: String): Boolean = template == FOLDER_SINGLE

/**
 * What a tap on a chip leaves in the draft: that chip's template, or null — nothing to change — when the
 * chip is already the chosen one, so a template written elsewhere is kept until the other chip is chosen.
 */
internal fun folderAfterTap(current: String, single: Boolean): String? =
    if (folderIsSingle(current) == single) null else if (single) FOLDER_SINGLE else FOLDER_MONTHLY

/** The template resolved for [now] — `recly/memo/2026-10` — or as written when it uses more than the clock. */
internal fun folderPreview(template: String, now: java.time.LocalDateTime): String = runCatching {
    recly.core.workflow.Template.render(
        template,
        recly.core.workflow.TemplateContext(
            mapOf(
                "yyyy" to "%04d".format(java.util.Locale.ROOT, now.year),
                "MM" to "%02d".format(java.util.Locale.ROOT, now.monthValue),
                "dd" to "%02d".format(java.util.Locale.ROOT, now.dayOfMonth),
                "HH" to "%02d".format(java.util.Locale.ROOT, now.hour),
                "mm" to "%02d".format(java.util.Locale.ROOT, now.minute),
            ),
        ),
    )
}.getOrDefault(template)

/** What the speaker row names: the segmentation and embedding models, products and not translated. */
private const val SPEAKER_MODEL_NAME = "pyannote 3.0 · ERes2Net"

/**
 * A model download in Settings — the speech model's, and the speaker models' own when the speech model is
 * already here: the size, the progress, a failure, and Download model / Resume download or Cancel download.
 * [tag] names the buttons for tests (`{tag}-download`, `{tag}-cancel`). [sizeLine] says the size: the speech model's
 * says transcription needs it; the speaker models' only how big they are — transcription runs without them.
 */
@Composable
private fun ModelDownloadBlock(
    state: ProcessingUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    recorderIdle: Boolean,
    tag: String,
    sizeLine: Int = R.string.processing_model_download,
) {
    val resources = LocalContext.current.resources
    val download = state.download
    // The download's own reading moves while it runs; the partial bytes are the same disk either way.
    val reading = download.info ?: state.local
    reading?.modelBytes?.let { size ->
        Text(stringResource(sizeLine, modelSize(size)), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
    }
    ModelDownloadLines(download, reading)
    // A footnote like the iPhone's: the button under it is the way on, and red is
    // kept for a failed recording (docs/09 "Red means only two things").
    download.error?.let { error ->
        Text(coreMessage(CoreMessage.STEP_FAILED, error).text(resources), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
    }
    EndButtons {
        if (download.active) {
            BlueprintButton(stringResource(R.string.processing_cancel_download), onCancel, tone = ButtonTone.QUIET,
                modifier = Modifier.testTag("$tag-cancel"))
        } else {
            BlueprintButton(modelDownloadLabel(reading), onStart, enabled = !state.busy && recorderIdle, modifier = Modifier.testTag("$tag-download"))
        }
    }
}

/**
 * docs/09 "Vocabulary": the terms as chips with a remove each, and a field that adds one — Enter or Add, and
 * one chip per line of a paste. A term past [VOCABULARY_ENTRY_MAX] characters, or one past [VOCABULARY_MAX],
 * is not added and the limits are said in the warning tone; a repeat (ignoring case) is not added either.
 */
@Composable
private fun VocabularyEditor(terms: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val add = {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var next = terms
        var over = false
        lines.forEach { line ->
            when {
                line.length > VOCABULARY_ENTRY_MAX || next.size >= VOCABULARY_MAX -> over = true
                next.none { it.equals(line, ignoreCase = true) } -> next = next + line
            }
        }
        refused = over
        if (next != terms) onChange(next)
        if (!over) text = ""
    }
    if (terms.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        terms.forEach { term -> VocabularyChip(term) { refused = false; onChange(terms - term) } }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        BlueprintField(
            text,
            { value ->
                // A paste of several lines is several terms; a typed Enter is the same as Add.
                if ('\n' in value) { text = value; add() } else text = value
            },
            placeholder = stringResource(R.string.vocabulary_placeholder),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.testTag("vocabulary-field"),
        )
        BlueprintButton(stringResource(R.string.vocabulary_add), { add() }, enabled = text.isNotBlank(), tone = ButtonTone.QUIET,
            modifier = Modifier.testTag("vocabulary-add"))
    }
    if (refused) Text(stringResource(R.string.vocabulary_limits), style = MaterialTheme.typography.bodySmall, color = blueprint.warningInk)
}

@Composable
private fun VocabularyChip(term: String, onRemove: () -> Unit) {
    val palette = blueprint
    val remove = stringResource(R.string.vocabulary_remove, term)
    Row(
        Modifier.border(palette.line, palette.grid, androidx.compose.foundation.shape.RoundedCornerShape(app.recly.android.ui.theme.Radius.node))
            .padding(start = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(term, style = MaterialTheme.typography.labelLarge, color = palette.text)
        Box(
            Modifier.size(MinTouch).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRemove)
                .semantics { contentDescription = remove },
            contentAlignment = Alignment.Center,
        ) { Text("×", style = MaterialTheme.typography.labelLarge, color = palette.textMuted, modifier = Modifier.clearAndSetSemantics {}) }
    }
}

/**
 * A setting's name at the start and what it is at the end — the settings table's row, inside the
 * block. A choice ends in a [BlueprintDropdown] and a value that is not one in plain text, so the
 * two read differently; [MinTouch] tall either way, so they stand the same height.
 */
@Composable
private fun ProcessingRow(title: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = MinTouch),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** docs/09 screen principle 8: a button group in a settings block is end-aligned, the committing action last. */
@Composable
private fun EndButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End), content = content)
}

/**
 * @param monospace for a value that is data — a number, an address, a model id — as the iPhone's
 * field draws it (docs/09 "Typography").
 */
@Composable
private fun ProcessingField(
    label: Int,
    value: String,
    placeholder: String? = null,
    monospace: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    change: (String) -> Unit,
) {
    BlueprintField(
        value, change, label = stringResource(label), modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder,
        textStyle = if (monospace) mono.bodySmall else MaterialTheme.typography.bodyMedium,
        keyboardOptions = keyboard,
    )
}

/** An address is typed, not written: the URL keyboard, and nothing that "corrects" or capitalises it. */
private val URL_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)

/** A key is a secret: the password keyboard, which also keeps it out of suggestions and learning. */
private val KEY_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

@Composable
/**
 * docs/05 "Secrets": the value is never read back. A saved key is a row that says so — ✓ in the
 * success colour, colour and text together (docs/09 "Every state is color + text") — with Replace and
 * Delete; the empty field only comes back to take a new value.
 *
 * What was typed and not saved belongs to the provider it was typed for: another provider, or
 * Cancel while replacing, drops it without a question — as on every shell (docs/09 screen principle 3).
 */
private fun ProcessingSecret(name: String, label: Int, saved: List<String>, save: (String, String, () -> Unit) -> Any, delete: () -> Unit) {
    var value by remember(name) { mutableStateOf("") }
    var replacing by remember(name) { mutableStateOf(false) }
    if (name.isNotBlank() && name in saved && !replacing) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
            Text("${stringResource(R.string.action_done)} ${stringResource(R.string.processing_key_on_device)}",
                style = MaterialTheme.typography.bodyMedium, color = blueprint.success)
        }
        EndButtons {
            BlueprintButton(stringResource(R.string.processing_key_replace), { replacing = true }, tone = ButtonTone.QUIET)
            BlueprintButton(stringResource(R.string.action_delete), delete, tone = ButtonTone.DANGER, minWidth = MinTouch)
        }
    } else {
        BlueprintField(value, { value = it }, label = stringResource(label),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KEY_KEYBOARD, modifier = Modifier.fillMaxWidth(),
            supporting = if (name.isBlank() || replacing) null else stringResource(R.string.processing_key_not_on_device))
        EndButtons {
            if (replacing) BlueprintButton(stringResource(R.string.action_cancel), { value = ""; replacing = false }, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(stringResource(R.string.processing_save_key), { save(name, value) { value = ""; replacing = false } },
                enabled = name.isNotBlank() && value.isNotBlank(), tone = ButtonTone.QUIET)
        }
    }
}

internal fun TranscriptionMode.label(): Int = when (this) {
    TranscriptionMode.LOCAL -> R.string.processing_local
    TranscriptionMode.EXTERNAL -> R.string.processing_external
    TranscriptionMode.OFF -> R.string.processing_off
}

@Composable
internal fun transcriptionLanguageLabel(language: Language): String = when (language) {
    Language.AUTO -> stringResource(R.string.processing_language_auto)
    Language.KO_EN -> stringResource(R.string.processing_language_mixed)
    else -> Locale.forLanguageTag(TranscriptionLanguages.localeTag(language)).let { it.getDisplayName(it) }
}
