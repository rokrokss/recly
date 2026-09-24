@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.recly.windows.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.recly.windows.i18n.*
import app.recly.windows.ui.component.*
import app.recly.windows.ui.theme.Space
import recly.core.processing.*
import java.util.Locale
import recly.core.transcribe.TranscriptionLanguages
import recly.core.model.Language
import recly.core.workflow.*

@Composable
fun ProcessingPanel(model: ProcessingViewModel, strings: Strings) {
    val draft = model.draft ?: return
    val languageSupported = draft.mode == TranscriptionMode.OFF || draft.language in draft.languages
    var deletingKey by remember { mutableStateOf<String?>(null) }
    deletingKey?.let { name -> BlueprintDialog(title = strings[Str.DELETE_KEY_TITLE, name], onDismissRequest = { deletingKey = null }, actions = {
        BlueprintButton(strings[Str.CANCEL], { deletingKey = null }, tone = ButtonTone.QUIET)
        BlueprintButton(strings[Str.DELETE], { model.deleteKey(name); deletingKey = null })
    }) { Text(name) } }
    Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SectionHeader(strings[Str.PROCESSING_TITLE])
        if (model.importing) Text(strings[Str.PROCESSING_IMPORT_BODY])
        Text(strings[Str.PROCESSING_NEW_RECORDINGS], style = MaterialTheme.typography.bodySmall)
        BlueprintTextField(draft.folder, { v -> model.edit { it.folder = v } }, strings[Str.PROCESSING_STORAGE])
        BlueprintTextField(draft.minimumSeconds, { v -> model.edit { it.minimumSeconds = v } }, strings[Str.FIELD_MIN_DURATION])
        SectionHeader(strings[Str.PROCESSING_TRANSCRIPTION])
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            // No on-device engine in this build: offer `local` only to a draft that already holds it.
            TranscriptionMode.entries.filter { it != TranscriptionMode.LOCAL || model.localInstalled || draft.mode == TranscriptionMode.LOCAL }.forEach { mode ->
                BlueprintChip(strings[mode.label()], draft.mode == mode, { model.edit { it.mode = mode } })
            }
        }
        if (draft.mode == TranscriptionMode.LOCAL) Text(strings[Str.CORE_LOCAL_TRANSCRIPTION_UNAVAILABLE], style = MaterialTheme.typography.bodySmall)
        if (draft.mode == TranscriptionMode.EXTERNAL) {
            BlueprintDropdown(strings[Str.FIELD_PROVIDER], WorkflowParser.STT_PROVIDERS.map { it to it }, draft.provider, { value -> model.edit { it.selectProvider(value) } })
            // docs/15 §3: what leaves the device, said under the provider choice on every shell.
            Text(strings[Str.PROVIDER_DISCLOSURE_TRANSCRIBE], style = MaterialTheme.typography.bodySmall)
            Text(strings[Str.PROCESSING_KEYS_NOT_EXPORTED], style = MaterialTheme.typography.bodySmall)
            BlueprintTextField(draft.secretRef, { v -> model.edit { it.secretRef = v } }, strings[Str.FIELD_SECRET_NAME])
            ProcessingKey(model, draft.secretRef, strings)
            if (WorkflowParser.invokeUrlUse(draft.provider) != InvokeUrlUse.NONE) BlueprintTextField(draft.invokeUrl, { v -> model.edit { it.invokeUrl = v } }, strings[Str.FIELD_INVOKE_URL])
            if (draft.acceptsModel) BlueprintTextField(draft.model, { v -> model.edit { it.model = v } }, strings[Str.PROCESSING_MODEL])
        }
        if (draft.mode != TranscriptionMode.OFF) {
            BlueprintDropdown(strings[Str.FIELD_LANGUAGE], draft.languages.map { it to transcriptionLanguageLabel(it, strings) }, draft.language, { value -> model.edit { it.language = value } })
            if (!languageSupported) Text(strings[Str.PROCESSING_LANGUAGE_UNSUPPORTED])
        }
        if (model.secretNames.isNotEmpty()) {
            SectionHeader(strings[Str.PROCESSING_SECRETS])
            model.secretNames.forEach { name ->
                Text(name, style = MaterialTheme.typography.bodySmall)
                BlueprintButton(strings[Str.DELETE], { deletingKey = name }, tone = ButtonTone.QUIET)
            }
        }
        model.message?.let { Text(it.text(strings)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            BlueprintButton(strings[Str.CANCEL], { model.reload() }, tone = ButtonTone.QUIET, enabled = model.dirty && !model.busy)
            BlueprintButton(strings[Str.SAVE], { model.save() }, tone = ButtonTone.PRIMARY, enabled = !model.busy && languageSupported && model.dirty)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            BlueprintButton(strings[Str.PROCESSING_EXPORT], { model.export() }, tone = ButtonTone.QUIET, enabled = model.stored is ProcessingSettingsState.Ready && !model.dirty)
            BlueprintButton(strings[Str.PROCESSING_IMPORT], { model.importSettings() }, tone = ButtonTone.QUIET, enabled = !model.dirty)
        }
    }
}
@Composable
private fun ProcessingKey(model: ProcessingViewModel, name: String, strings: Strings) {
    var value by remember(name) { mutableStateOf("") }
    OutlinedTextField(value, { value = it }, label = { Text(strings[Str.FIELD_API_KEY]) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    BlueprintButton(strings[Str.PROCESSING_SAVE_KEY], { model.saveKey(name, value) { value = "" } }, enabled = name.isNotBlank() && value.isNotBlank(), tone = ButtonTone.QUIET)
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
