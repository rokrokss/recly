@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.*
import app.recly.windows.ui.component.*
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import recly.core.processing.*
import java.util.Locale
import recly.core.transcribe.SttProviders
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.Qwen3Asr
import recly.core.transcribe.TranscriptionLanguages
import recly.core.model.Language
import recly.core.workflow.*

@Composable
fun ProcessingPanel(model: ProcessingViewModel, strings: Strings, preparationAllowed: Boolean = true) {
    val draft = model.draft ?: return
    // docs/05 "Fixed processing settings": on this device that means Qwen3-ASR, which has its own language list.
    val languages = if (draft.mode == TranscriptionMode.LOCAL) Qwen3Asr.languages else draft.languages
    val languageSupported = draft.mode == TranscriptionMode.OFF || draft.language in languages
    var deletingKey by remember { mutableStateOf<String?>(null) }
    deletingKey?.let { name -> BlueprintDialog(title = strings[Str.DELETE_KEY_TITLE, SttProviders.displayName(name)], onDismissRequest = { deletingKey = null }, actions = {
        BlueprintButton(strings[Str.CANCEL], { deletingKey = null }, tone = ButtonTone.QUIET)
        // Irreversible, so the danger tone, as every Delete that cannot be undone wears it.
        BlueprintButton(strings[Str.DELETE], { model.deleteKey(name); deletingKey = null }, tone = ButtonTone.DANGER)
    }) { Text(name) } }
    // The same section heading as the rest of Settings (SettingsWindow `Section`).
    SectionHeader(strings[Str.PROCESSING_TITLE], Modifier.padding(horizontal = Space.m))
    HairLine()
    // On the surface, with the same inset as the other settings sections' blocks (SettingsWindow
    // `SettingsCard`): one left edge and one right edge for every label, row and button group in it.
    Column(
        Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (model.importing) Text(strings[Str.PROCESSING_IMPORT_BODY])
        BlueprintTextField(draft.folder, { v -> model.edit { it.folder = v } }, strings[Str.PROCESSING_STORAGE])
        BlueprintTextField(draft.minimumSeconds, { v -> model.edit { it.minimumSeconds = v } }, strings[Str.FIELD_MIN_DURATION])
        SectionHeader(strings[Str.PROCESSING_TRANSCRIPTION])
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            // No on-device engine in this build: offer `local` only to a draft that already holds it.
            TranscriptionMode.entries.filter { it != TranscriptionMode.LOCAL || model.localInstalled || draft.mode == TranscriptionMode.LOCAL }.forEach { mode ->
                // The language the app is in stands in for Automatic and mixed Korean, which Qwen3-ASR does not
                // have — the phone's rule (ProcessingDraft.selectTranscriptionMode).
                BlueprintChip(strings[mode.label()], draft.mode == mode, { model.edit { it.selectTranscriptionMode(mode, strings.language) } })
            }
        }
        if (draft.mode == TranscriptionMode.LOCAL) {
            // The model by name, as the phone and the Mac show theirs; a product name, not translated.
            if (model.localInstalled) {
                LabelledRow(strings[Str.PROCESSING_SPEECH_MODEL]) {
                    Text(Qwen3Asr.DISPLAY_NAME, style = MaterialTheme.typography.bodyMedium, color = blueprint.textMuted)
                }
            }
            when {
                // A language the model lacks has its own line below; this one is for the device.
                !model.localInstalled || (model.local?.status == LocalEngineStatus.UNSUPPORTED && languageSupported) ->
                    Text(strings[Str.CORE_LOCAL_TRANSCRIPTION_UNAVAILABLE], style = MaterialTheme.typography.bodySmall)
                model.local?.status == LocalEngineStatus.MODEL_REQUIRED -> {
                    (model.local?.modelBytes ?: model.download.info?.modelBytes)?.let { bytes ->
                        val size = ByteFormat.format(bytes, Locale.forLanguageTag(strings.language))
                        Text(strings[Str.PROCESSING_MODEL_DOWNLOAD, size], style = MaterialTheme.typography.bodySmall)
                    }
                    // Not while recording, as on the Mac: the download is for later, the recording is now.
                    ModelDownloadControls(model.download, strings, preparationAllowed, model::prepare, ButtonTone.ACCENT)
                }
            }
            if (model.localInstalled) Text(strings[Str.PROCESSING_LOCAL_NO_SPEAKERS], style = MaterialTheme.typography.bodySmall)
        }
        if (draft.mode == TranscriptionMode.EXTERNAL) {
            // A labelled row, as the other shells draw it: the dropdown alone said only its value.
            LabelledRow(strings[Str.FIELD_PROVIDER]) {
                BlueprintDropdown(strings[Str.FIELD_PROVIDER], WorkflowParser.STT_PROVIDERS.map { it to SttProviders.displayName(it) }, draft.provider, { value -> model.edit { it.selectProvider(value) } })
            }
            // docs/15 §3: what leaves the device, said under the provider choice on every shell.
            Text(strings[Str.PROVIDER_DISCLOSURE_TRANSCRIBE, SttProviders.displayName(draft.provider)], style = MaterialTheme.typography.bodySmall)
            if (SttProviders.keyIsClientPair(draft.provider)) Text(strings[Str.PROCESSING_KEY_CLIENT_PAIR], style = MaterialTheme.typography.bodySmall)
            ProcessingKey(model, draft.secretRef, strings) { deletingKey = draft.secretRef }
            if (WorkflowParser.invokeUrlUse(draft.provider) != InvokeUrlUse.NONE) BlueprintTextField(draft.invokeUrl, { v -> model.edit { it.invokeUrl = v } }, strings[Str.FIELD_INVOKE_URL], placeholder = draft.invokeUrlHint)
            if (draft.acceptsModel) BlueprintTextField(draft.model, { v -> model.edit { it.model = v } }, strings[Str.PROCESSING_MODEL])
        }
        // The last setting of either method, and a labelled row like the provider's.
        if (draft.mode != TranscriptionMode.OFF) {
            LabelledRow(strings[Str.FIELD_LANGUAGE]) {
                BlueprintDropdown(strings[Str.FIELD_LANGUAGE], languages.map { it to transcriptionLanguageLabel(it, strings) }, draft.language, { value -> model.edit { it.language = value } })
            }
            if (!languageSupported) Text(strings[Str.PROCESSING_LANGUAGE_UNSUPPORTED])
        }
        model.message?.let { Text(it.text(strings)) }
        // docs/09: a form's buttons are end-aligned, the commit last — and only there while the draft has changes.
        if (model.dirty) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            BlueprintButton(strings[Str.CANCEL], { model.reload() }, tone = ButtonTone.QUIET, enabled = !model.busy)
            BlueprintButton(strings[Str.SAVE], { model.save() }, tone = ButtonTone.PRIMARY, enabled = !model.busy && languageSupported)
        }
        // Keys only matter to an external provider, so the list lives with it — after the form it
        // belongs to, because deleting one is not part of that form's Save.
        // The current provider's key is managed on its own row above; this lists the rest.
        val others = model.secretNames.filter { it != draft.secretRef }
        if (draft.mode == TranscriptionMode.EXTERNAL && others.isNotEmpty()) {
            SectionHeader(strings[Str.PROCESSING_OTHER_KEYS])
            others.forEach { name ->
                LabelledRow(SttProviders.displayName(name)) {
                    BlueprintButton(strings[Str.DELETE], { deletingKey = name }, tone = ButtonTone.DANGER)
                }
            }
        }
    }
}

/**
 * docs/05: the processing settings out to a file and back. Its own section in Settings, after the
 * features — Agent connection included — because it is a utility (docs/09 screen principle 4).
 */
@Composable
fun ProcessingSettingsFile(model: ProcessingViewModel, strings: Strings) {
    if (model.draft == null) return
    SectionHeader(strings[Str.PROCESSING_SETTINGS_FILE], Modifier.padding(horizontal = Space.m))
    HairLine()
    FlowRow(
        Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
    ) {
        BlueprintButton(strings[Str.PROCESSING_EXPORT], { model.export() }, tone = ButtonTone.QUIET, enabled = model.stored is ProcessingSettingsState.Ready && !model.dirty)
        BlueprintButton(strings[Str.PROCESSING_IMPORT], { model.importSettings() }, tone = ButtonTone.QUIET, enabled = !model.dirty)
    }
    HairLine()
}
@Composable
/**
 * docs/05 "Secrets": the value is never read back. A saved key is a row that says so — [SELECTION_MARK]
 * in the success colour, colour and text together (docs/09 "Every state is color + text") — with
 * Replace and Delete; the empty field only comes back to take a new value.
 */
private fun ProcessingKey(model: ProcessingViewModel, name: String, strings: Strings, delete: () -> Unit) {
    var value by remember(name) { mutableStateOf("") }
    var replacing by remember(name) { mutableStateOf(false) }
    if (name.isNotBlank() && name in model.secretNames && !replacing) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text(strings[Str.FIELD_API_KEY], style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
            Text("$SELECTION_MARK ${strings[Str.PROCESSING_KEY_ON_DEVICE]}", style = MaterialTheme.typography.bodyMedium, color = blueprint.success)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            BlueprintButton(strings[Str.PROCESSING_KEY_REPLACE], { replacing = true }, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.DELETE], delete, tone = ButtonTone.DANGER)
        }
    } else {
        OutlinedTextField(value, { value = it }, label = { Text(strings[Str.FIELD_API_KEY]) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            supportingText = if (name.isBlank() || replacing) null else {
                { Text(strings[Str.PROCESSING_KEY_NOT_ON_DEVICE]) }
            })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            if (replacing) BlueprintButton(strings[Str.CANCEL], { value = ""; replacing = false }, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.PROCESSING_SAVE_KEY], { model.saveKey(name, value) { value = ""; replacing = false } }, enabled = name.isNotBlank() && value.isNotBlank(), tone = ButtonTone.QUIET)
        }
    }
}
/** A setting's name at the start and its control at the end — the settings table's row, inside the block. */
@Composable
private fun LabelledRow(label: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

internal fun TranscriptionMode.label(): Str = when (this) {
    TranscriptionMode.LOCAL -> Str.PROCESSING_LOCAL
    TranscriptionMode.EXTERNAL -> Str.PROCESSING_EXTERNAL
    TranscriptionMode.OFF -> Str.PROCESSING_OFF
}

private fun transcriptionLanguageLabel(language: Language, strings: Strings): String = when (language) {
    Language.AUTO -> strings[Str.PROCESSING_LANGUAGE_AUTO]
    Language.KO_EN -> strings[Str.PROCESSING_LANGUAGE_MIXED]
    else -> Locale.forLanguageTag(TranscriptionLanguages.localeTag(language)).let { it.getDisplayName(it) }
}
