package app.recly.windows.ui

import androidx.compose.runtime.*
import app.recly.windows.i18n.*
import kotlinx.coroutines.*
import recly.core.ReclyCore
import recly.core.message.CoreMessage
import recly.core.processing.*
import recly.core.transcribe.installed

/** Draft and import preview survive window dismissal. Only Save affects future captures. */
class ProcessingViewModel(
    private val core: ReclyCore,
    private val scope: CoroutineScope,
    private val saveFile: suspend (String, String) -> Boolean,
    private val openFile: suspend () -> String?,
    private val onSaved: () -> Unit,
) {
    var draft: ProcessingDraft? by mutableStateOf(null); private set
    var stored: ProcessingSettingsState by mutableStateOf(ProcessingSettingsState.NotInitialized); private set
    var dirty by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var importing by mutableStateOf(false); private set
    var message: UiMessage? by mutableStateOf(null); private set
    var summary: ProcessingTranscription by mutableStateOf(ProcessingTranscription()); private set
    var secretNames: List<String> by mutableStateOf(emptyList()); private set
    /** Whether this build ships an on-device engine; the panel hides `local` when it does not. */
    val localInstalled: Boolean = core.deps.localTranscription.installed
    init { reload() }
    fun edit(change: (ProcessingDraft) -> Unit) { draft = draft?.snapshot()?.apply(change); dirty = true; message = null }
    fun reload() = scope.launch {
        runCatching {
            stored = core.initializeProcessing()
            secretNames = core.secrets.names()
            val settings = (stored as? ProcessingSettingsState.Ready)?.document?.settings ?: ProcessingSettings()
            draft = ProcessingDraft.from(settings); summary = settings.transcription; dirty = false; importing = false
        }.onFailure(::failed)
    }
    fun save() = scope.launch {
        val current = draft ?: return@launch
        if (busy) return@launch
        busy = true
        try {
            val result = when (val value = stored) {
                is ProcessingSettingsState.Ready -> core.processingSettings.save(current.settings(), value.document.revision)
                ProcessingSettingsState.NotInitialized -> ProcessingSaveResult.Unavailable
            }
            when (result) {
                is ProcessingSaveResult.Saved -> {
                    stored = ProcessingSettingsState.Ready(result.document); draft = ProcessingDraft.from(result.document.settings)
                    summary = result.document.settings.transcription; dirty = false; importing = false
                    message = Str.PROCESSING_SAVED.message(); onSaved()
                }
                is ProcessingSaveResult.Invalid -> message = coreMessage(CoreMessage.STEP_FAILED, result.errors.joinToString("\n"))
                ProcessingSaveResult.Stale -> message = coreMessage(CoreMessage.STALE)
                else -> message = Str.PROCESSING_UNREADABLE.message()
            }
        } catch (error: Exception) { failed(error) } finally { busy = false }
    }
    fun saveKey(name: String, value: String, saved: () -> Unit) = scope.launch {
        if (!name.matches(Regex("[a-z][a-z0-9_]{0,31}")) || value.isBlank()) {
            message = (if (value.isBlank()) Str.SECRET_VALUE_REQUIRED else Str.SECRET_NAME_INVALID).message(); return@launch
        }
        runCatching { core.secrets.put(name, value); secretNames = core.secrets.names(); saved(); message = Str.PROCESSING_KEY_SAVED.message() }.onFailure(::failed)
    }
    fun deleteKey(name: String) = scope.launch {
        runCatching { core.secrets.delete(name); secretNames = core.secrets.names() }.onFailure(::failed)
    }
    fun export() = scope.launch {
        runCatching {
            val json = core.processingSettings.exportJson()
            if (json != null) saveFile("recly-settings.json", json)
        }.onFailure(::failed)
    }
    fun importSettings() = scope.launch {
        runCatching {
            val json = openFile() ?: return@runCatching
            require(json.length <= 1_048_576)
            val parsed = ProcessingSettingsParser.parse(json)
            if (parsed is ProcessingParseResult.Valid) {
                draft = ProcessingDraft.from(parsed.document.settings); dirty = true; importing = true; message = null
            } else message = Str.PROCESSING_UNREADABLE.message()
        }.onFailure(::failed)
    }
    private fun failed(error: Throwable) { message = coreMessage(CoreMessage.STEP_FAILED, error.message.orEmpty()) }
}
