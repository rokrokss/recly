@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.recly.windows.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.recly.windows.i18n.*
import app.recly.windows.ui.component.*
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import recly.core.processing.*
import java.util.Locale
import recly.core.transcribe.SttProviders
import recly.core.transcribe.TranscriptionLanguages
import recly.core.model.Language
import recly.core.workflow.*

@Composable
fun ProcessingPanel(model: ProcessingViewModel, strings: Strings) {
    val draft = model.draft ?: return
    val languageSupported = draft.mode == TranscriptionMode.OFF || draft.language in draft.languages
    var deletingKey by remember { mutableStateOf<String?>(null) }
    deletingKey?.let { name -> BlueprintDialog(title = strings[Str.DELETE_KEY_TITLE, SttProviders.displayName(name)], onDismissRequest = { deletingKey = null }, actions = {
        BlueprintButton(strings[Str.CANCEL], { deletingKey = null }, tone = ButtonTone.QUIET)
        BlueprintButton(strings[Str.DELETE], { model.deleteKey(name); deletingKey = null })
    }) { Text(name) } }
    Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SectionHeader(strings[Str.PROCESSING_TITLE])
        if (model.importing) Text(strings[Str.PROCESSING_IMPORT_BODY])
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
            BlueprintDropdown(strings[Str.FIELD_PROVIDER], WorkflowParser.STT_PROVIDERS.map { it to SttProviders.displayName(it) }, draft.provider, { value -> model.edit { it.selectProvider(value) } })
            // docs/15 §3: what leaves the device, said under the provider choice on every shell.
            Text(strings[Str.PROVIDER_DISCLOSURE_TRANSCRIBE, SttProviders.displayName(draft.provider)], style = MaterialTheme.typography.bodySmall)
            if (SttProviders.keyIsClientPair(draft.provider)) Text(strings[Str.PROCESSING_KEY_CLIENT_PAIR], style = MaterialTheme.typography.bodySmall)
            ProcessingKey(model, draft.secretRef, strings) { deletingKey = draft.secretRef }
            if (WorkflowParser.invokeUrlUse(draft.provider) != InvokeUrlUse.NONE) BlueprintTextField(draft.invokeUrl, { v -> model.edit { it.invokeUrl = v } }, strings[Str.FIELD_INVOKE_URL])
            if (draft.acceptsModel) BlueprintTextField(draft.model, { v -> model.edit { it.model = v } }, strings[Str.PROCESSING_MODEL])
            // Keys only matter to an external provider, so the list lives with it.
            // The current provider's key is managed on its own row above; this lists the rest.
            val others = model.secretNames.filter { it != draft.secretRef }
            if (others.isNotEmpty()) {
                SectionHeader(strings[Str.PROCESSING_OTHER_KEYS])
                others.forEach { name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Text(SttProviders.displayName(name), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        BlueprintButton(strings[Str.DELETE], { deletingKey = name }, tone = ButtonTone.QUIET)
                    }
                }
            }
        }
        if (draft.mode != TranscriptionMode.OFF) {
            BlueprintDropdown(strings[Str.FIELD_LANGUAGE], draft.languages.map { it to transcriptionLanguageLabel(it, strings) }, draft.language, { value -> model.edit { it.language = value } })
            if (!languageSupported) Text(strings[Str.PROCESSING_LANGUAGE_UNSUPPORTED])
        }
        model.message?.let { Text(it.text(strings)) }
        // docs/09: a form's buttons are end-aligned, the commit last — and only there while the draft has changes.
        if (model.dirty) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            BlueprintButton(strings[Str.CANCEL], { model.reload() }, tone = ButtonTone.QUIET, enabled = !model.busy)
            BlueprintButton(strings[Str.SAVE], { model.save() }, tone = ButtonTone.PRIMARY, enabled = !model.busy && languageSupported)
        }
        SectionHeader(strings[Str.PROCESSING_SETTINGS_FILE])
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            BlueprintButton(strings[Str.PROCESSING_EXPORT], { model.export() }, tone = ButtonTone.QUIET, enabled = model.stored is ProcessingSettingsState.Ready && !model.dirty)
            BlueprintButton(strings[Str.PROCESSING_IMPORT], { model.importSettings() }, tone = ButtonTone.QUIET, enabled = !model.dirty)
        }
    }
}
@Composable
/**
 * docs/05 "시크릿": the value is never read back. A saved key is a row that says so — [SELECTION_MARK]
 * in the success colour, colour and text together (docs/09 "모든 상태는 색 + 텍스트") — with
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
            BlueprintButton(strings[Str.DELETE], delete, tone = ButtonTone.QUIET)
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
