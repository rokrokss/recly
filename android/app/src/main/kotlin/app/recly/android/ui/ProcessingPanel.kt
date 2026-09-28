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
        BlueprintButton(stringResource(R.string.action_cancel), { deletingKey = null }, tone = ButtonTone.QUIET)
        BlueprintButton(stringResource(R.string.action_delete), { model.deleteKey(name); deletingKey = null }, tone = ButtonTone.DANGER)
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
            // docs/05 "고정 처리 설정 도입": on this device that means Qwen3-ASR, which has its own language list.
            val languages = if (draft.mode == TranscriptionMode.LOCAL) Qwen3Asr.languages else draft.languages
            val languageSupported = draft.mode == TranscriptionMode.OFF || draft.language in languages
            if (state.importing) Text(stringResource(R.string.processing_import_body), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
            ProcessingField(R.string.processing_storage, draft.folder) { v -> model.edit { it.folder = v } }
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
                    state.local?.status == LocalEngineStatus.MODEL_REQUIRED -> {
                        val download = state.download
                        // The download's own reading moves while it runs; the partial bytes are the same disk either way.
                        val reading = download.info ?: state.local
                        reading?.modelBytes?.let { size ->
                            Text(stringResource(R.string.processing_model_download, modelSize(size)), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                        }
                        ModelDownloadLines(download, reading)
                        // A footnote like the iPhone's: the button under it is the way on, and red is
                        // kept for a failed recording (docs/09 "빨강의 뜻은 둘뿐이다").
                        download.error?.let { error ->
                            Text(coreMessage(CoreMessage.STEP_FAILED, error).text(resources), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                        }
                        EndButtons {
                            if (download.active) {
                                BlueprintButton(stringResource(R.string.processing_cancel_download), model::cancelDownload, tone = ButtonTone.QUIET,
                                    modifier = Modifier.testTag("model-cancel"))
                            } else {
                                // Not while recording, as on iPhone: the download is for later, the recording is now.
                                BlueprintButton(modelDownloadLabel(reading), { metered(model::downloadModel) },
                                    enabled = !state.busy && recorder == RecorderState.Idle, modifier = Modifier.testTag("model-download"))
                            }
                        }
                    }
                }
                if (state.localInstalled) {
                    Text(stringResource(R.string.processing_local_no_speakers), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                }
            }
            if (draft.mode == TranscriptionMode.EXTERNAL) {
                // docs/09 원칙 4: a settings row, "Provider … ElevenLabs ▾", like the app language row.
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
            }
            state.message?.let { Text(it.text(resources), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted) }
            // Only a draft with changes has anything to commit; the buttons appearing is the sign that it does.
            if (state.dirty) EndButtons {
                BlueprintButton(stringResource(R.string.action_cancel), { model.reload() }, tone = ButtonTone.QUIET, enabled = !state.busy)
                BlueprintButton(stringResource(R.string.action_save), { model.save() }, tone = ButtonTone.PRIMARY,
                    enabled = !state.busy && languageSupported)
            }
            // Keys only matter to an external provider, so the list lives with it — after the draft's
            // own Save, since deleting a key is not part of it. The current provider's key is managed
            // on its own row above; this lists the rest.
            val others = state.secretNames.filter { it != draft.secretRef }
            if (draft.mode == TranscriptionMode.EXTERNAL && others.isNotEmpty()) {
                SectionHeader(stringResource(R.string.processing_other_keys))
                // docs/09 화면 원칙 8: an action that belongs to one item sits at the end of its row.
                others.forEach { name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(SttProviders.displayName(name), style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
                        BlueprintButton(stringResource(R.string.action_delete), { deletingKey = name }, tone = ButtonTone.DANGER)
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

/** docs/09 화면 원칙 8: a button group in a settings block is end-aligned, the committing action last. */
@Composable
private fun EndButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End), content = content)
}

/**
 * @param monospace for a value that is data — a number, an address, a model id — as the iPhone's
 * field draws it (docs/09 "타이포").
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
    val style = if (monospace) mono.bodySmall else LocalTextStyle.current
    OutlinedTextField(
        value, change, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder?.let { { Text(it, style = style) } },
        textStyle = style,
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
 * docs/05 "시크릿": the value is never read back. A saved key is a row that says so — ✓ in the
 * success colour, colour and text together (docs/09 "모든 상태는 색 + 텍스트") — with Replace and
 * Delete; the empty field only comes back to take a new value.
 *
 * What was typed and not saved belongs to the provider it was typed for: another provider, or
 * Cancel while replacing, drops it without a question — as on every shell (docs/09 화면 원칙 3).
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
            BlueprintButton(stringResource(R.string.action_delete), delete, tone = ButtonTone.DANGER)
        }
    } else {
        OutlinedTextField(value, { value = it }, label = { Text(stringResource(label)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KEY_KEYBOARD, modifier = Modifier.fillMaxWidth(),
            supportingText = if (name.isBlank() || replacing) null else {
                { Text(stringResource(R.string.processing_key_not_on_device)) }
            })
        EndButtons {
            if (replacing) BlueprintButton(stringResource(R.string.action_cancel), { value = ""; replacing = false }, tone = ButtonTone.QUIET)
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
private fun transcriptionLanguageLabel(language: Language): String = when (language) {
    Language.AUTO -> stringResource(R.string.processing_language_auto)
    Language.KO_EN -> stringResource(R.string.processing_language_mixed)
    else -> Locale.forLanguageTag(TranscriptionLanguages.localeTag(language)).let { it.getDisplayName(it) }
}
