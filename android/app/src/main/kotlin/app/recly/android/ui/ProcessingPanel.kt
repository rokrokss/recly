@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import app.recly.android.R
import app.recly.android.core.text
import app.recly.android.ui.component.*
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import recly.core.model.Language
import recly.core.processing.*
import java.util.Locale
import recly.core.transcribe.SttProviders
import recly.core.transcribe.TranscriptionLanguages
import recly.core.transcribe.LocalEngineStatus
import recly.core.workflow.InvokeUrlUse
import recly.core.workflow.WorkflowParser

@Composable
fun ProcessingPanel(model: ProcessingViewModel = viewModel()) {
    val resources = LocalContext.current.resources
    val state by model.state.collectAsState()
    val draft = state.draft ?: return
    var pickingLanguage by remember { mutableStateOf(false) }
    val languageSupported = draft.mode == TranscriptionMode.OFF || draft.language in draft.languages
    var deletingKey by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { model.export(it) } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importSettings) }
    // The same section heading as the rest of Settings (SettingsScreen `Section`).
    SectionHeader(stringResource(R.string.processing_title), Modifier.padding(horizontal = Space.m))
    HairLine()
    deletingKey?.let { name -> BlueprintDialog(title = stringResource(R.string.delete_key_title, SttProviders.displayName(name)), onDismissRequest = { deletingKey = null }, actions = {
        BlueprintButton(stringResource(R.string.action_cancel), { deletingKey = null }, tone = ButtonTone.QUIET)
        BlueprintButton(stringResource(R.string.action_delete), { model.deleteKey(name); deletingKey = null })
    }) { Text(name) } }
    Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (state.importing) Text(stringResource(R.string.processing_import_body), style = MaterialTheme.typography.bodySmall)
        ProcessingField(R.string.processing_storage, draft.folder) { v -> model.edit { it.folder = v } }
        ProcessingField(R.string.editor_min_duration, draft.minimumSeconds) { v -> model.edit { it.minimumSeconds = v } }
        SectionHeader(stringResource(R.string.processing_transcription))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            // No engine, no chip — unless it is already chosen, so the line below can say why and the user can leave it.
            TranscriptionMode.entries.filter { it != TranscriptionMode.LOCAL || state.localInstalled || draft.mode == it }.forEach { mode ->
                BlueprintChip(stringResource(mode.label()), draft.mode == mode, onClick = { model.edit { it.mode = mode } })
            }
        }
        if (draft.mode == TranscriptionMode.LOCAL) {
            when (state.local?.status) {
                LocalEngineStatus.UNSUPPORTED -> Text(stringResource(R.string.core_local_transcription_unavailable), style = MaterialTheme.typography.bodySmall)
                LocalEngineStatus.MODEL_REQUIRED -> EndButtons {
                    BlueprintButton(stringResource(R.string.processing_prepare), { model.prepare() }, enabled = !state.busy)
                }
                else -> Unit
            }
        }
        if (draft.mode == TranscriptionMode.EXTERNAL) {
            var providers by remember { mutableStateOf(false) }
            // docs/09 원칙 4: a settings row, "Provider … ElevenLabs", like the app language row.
            ProcessingRow(stringResource(R.string.editor_provider), SttProviders.displayName(draft.provider)) { providers = true }
            if (providers) BlueprintDialog(title = stringResource(R.string.editor_provider), onDismissRequest = { providers = false }, actions = {
                BlueprintButton(stringResource(R.string.action_close), { providers = false }, tone = ButtonTone.QUIET)
            }) {
                WorkflowParser.STT_PROVIDERS.forEach { name -> BlueprintRadioRow(SttProviders.displayName(name), draft.provider == name, {
                    model.edit { it.selectProvider(name) }; providers = false
                }) }
            }
            Text(stringResource(R.string.provider_disclosure_transcribe, SttProviders.displayName(draft.provider)), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
            if (SttProviders.keyIsClientPair(draft.provider)) {
                Text(stringResource(R.string.processing_key_client_pair), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
            }
            ProcessingSecret(draft.secretRef, R.string.editor_api_key, state.secretNames, model::saveKey) { deletingKey = draft.secretRef }
            if (WorkflowParser.invokeUrlUse(draft.provider) != InvokeUrlUse.NONE) {
                ProcessingField(R.string.editor_invoke_url, draft.invokeUrl) { v -> model.edit { it.invokeUrl = v } }
            }
            if (draft.acceptsModel) ProcessingField(R.string.processing_model, draft.model) { v -> model.edit { it.model = v } }
            // Keys only matter to an external provider, so the list lives with it.
            // The current provider's key is managed on its own row above; this lists the rest.
            val others = state.secretNames.filter { it != draft.secretRef }
            if (others.isNotEmpty()) {
                SectionHeader(stringResource(R.string.processing_other_keys))
                // docs/09 화면 원칙 8: an action that belongs to one item sits at the end of its row.
                others.forEach { name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(SttProviders.displayName(name), style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
                        BlueprintButton(stringResource(R.string.action_delete), { deletingKey = name }, tone = ButtonTone.QUIET)
                    }
                }
            }
        }
        if (draft.mode != TranscriptionMode.OFF) {
            ProcessingRow(stringResource(R.string.editor_language), transcriptionLanguageLabel(draft.language)) { pickingLanguage = true }
            if (!languageSupported) Text(stringResource(R.string.processing_language_unsupported))
            if (pickingLanguage) BlueprintDialog(title = stringResource(R.string.editor_language), onDismissRequest = { pickingLanguage = false }, actions = {
                BlueprintButton(stringResource(R.string.action_close), { pickingLanguage = false }, tone = ButtonTone.QUIET)
            }) {
                draft.languages.forEach { language -> BlueprintRadioRow(transcriptionLanguageLabel(language), draft.language == language, {
                    model.edit { it.language = language }; pickingLanguage = false
                }) }
            }
        }
        state.message?.let { Text(it.text(resources), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted) }
        // Only a draft with changes has anything to commit; the buttons appearing is the sign that it does.
        if (state.dirty) EndButtons {
            BlueprintButton(stringResource(R.string.action_cancel), { model.reload() }, tone = ButtonTone.QUIET, enabled = !state.busy)
            BlueprintButton(stringResource(R.string.action_save), { model.save() }, tone = ButtonTone.PRIMARY,
                enabled = !state.busy && languageSupported)
        }
        SectionHeader(stringResource(R.string.processing_settings_file))
        EndButtons {
            BlueprintButton(stringResource(R.string.processing_export), { export.launch("recly-settings.json") }, tone = ButtonTone.QUIET,
                enabled = state.stored is ProcessingSettingsState.Ready && !state.dirty)
            BlueprintButton(stringResource(R.string.processing_import), { importPicker.launch(arrayOf("application/json", "text/plain")) }, tone = ButtonTone.QUIET, enabled = !state.dirty)
        }
    }
}

/** The app language row's shape (label, quiet value, tap to choose) without a second inset. */
@Composable
private fun ProcessingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = blueprint.text, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = blueprint.textMuted)
    }
}

/** docs/09 화면 원칙 8: a button group in a settings block is end-aligned, the committing action last. */
@Composable
private fun EndButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End), content = content)
}

@Composable
private fun ProcessingField(label: Int, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
/**
 * docs/05 "시크릿": the value is never read back. A saved key is a row that says so — ✓ in the
 * success colour, colour and text together (docs/09 "모든 상태는 색 + 텍스트") — with Replace and
 * Delete; the empty field only comes back to take a new value.
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
            BlueprintButton(stringResource(R.string.action_delete), delete, tone = ButtonTone.QUIET)
        }
    } else {
        OutlinedTextField(value, { value = it }, label = { Text(stringResource(label)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
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
