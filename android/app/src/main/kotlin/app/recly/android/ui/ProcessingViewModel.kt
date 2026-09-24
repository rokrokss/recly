package app.recly.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.core.UiMessage
import app.recly.android.core.coreMessage
import app.recly.android.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.processing.*
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.installed

data class ProcessingUiState(
    val stored: ProcessingSettingsState = ProcessingSettingsState.NotInitialized,
    val draft: ProcessingDraft? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val importing: Boolean = false,
    val message: UiMessage? = null,
    val local: LocalEngineInfo? = null,
    /** False when this build ships no on-device engine, so `local` is not offered as a choice. */
    val localInstalled: Boolean = false,
    val secretNames: List<String> = emptyList(),
)

/** Device settings drafts survive tab changes; only Save changes future recording snapshots. */
class ProcessingViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ProcessingUiState())
    val state = _state.asStateFlow()
    init {
        reload()
    }

    fun edit(change: (ProcessingDraft) -> Unit) {
        _state.update { value -> value.copy(draft = value.draft?.snapshot()?.apply(change), dirty = true, message = null) }
    }

    fun reload() = viewModelScope.launch {
        runCatching {
            val core = core()
            val stored = core.initializeProcessing()
            _state.value = ProcessingUiState(stored, ProcessingDraft.from(stored.document.settings), secretNames = core.secrets.names(),
                localInstalled = core.deps.localTranscription.installed)
            refreshLocal()
        }.onFailure { failed(it) }
    }

    fun save() = viewModelScope.launch {
        val before = state.value
        val draft = before.draft ?: return@launch
        if (before.busy) return@launch
        _state.update { it.copy(busy = true, message = null) }
        runCatching {
            val core = core()
            val result = when (val stored = before.stored) {
                is ProcessingSettingsState.Ready -> core.processingSettings.save(draft.settings(), stored.document.revision)
                ProcessingSettingsState.NotInitialized -> ProcessingSaveResult.Unavailable
            }
            when (result) {
                is ProcessingSaveResult.Saved -> {
                    _state.value = before.copy(stored = ProcessingSettingsState.Ready(result.document),
                        draft = ProcessingDraft.from(result.document.settings), dirty = false, busy = false,
                        importing = false, message = UiMessage.Res(R.string.processing_saved))
                    WorkScheduler(getApplication()).runNow(expedited = false)
                    refreshLocal()
                }
                ProcessingSaveResult.Stale -> _state.update { it.copy(busy = false, message = coreMessage(CoreMessage.STALE)) }
                is ProcessingSaveResult.Invalid -> _state.update { it.copy(busy = false, message = coreMessage(CoreMessage.STEP_FAILED, result.errors.joinToString("\n"))) }
                else -> _state.update { it.copy(busy = false, message = UiMessage.Res(R.string.processing_unreadable)) }
            }
        }.onFailure { failed(it) }
    }

    fun saveKey(name: String, value: String, saved: () -> Unit) = viewModelScope.launch {
        if (!name.matches(Regex("[a-z][a-z0-9_]{0,31}")) || value.isBlank()) {
            _state.update { it.copy(message = UiMessage.Res(if (value.isBlank()) R.string.secret_value_required else R.string.secret_name_invalid)) }
            return@launch
        }
        runCatching {
            core().secrets.put(name, value)
            saved()
            _state.update { it.copy(secretNames = (it.secretNames + name).distinct(), message = UiMessage.Res(R.string.processing_key_saved)) }
        }.onFailure { failed(it) }
    }

    fun prepare() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching {
            val language = state.value.draft?.language ?: Language.KO
            val info = core().deps.localTranscription.prepare(language.name.lowercase().replace('_', '-'))
            _state.update { it.copy(busy = false, local = info) }
        }.onFailure { failed(it) }
    }
    fun deleteKey(name: String) = viewModelScope.launch {
        runCatching {
            core().secrets.delete(name)
            val names = core().secrets.names()
            _state.update { it.copy(secretNames = names) }
        }.onFailure { failed(it) }
    }

    fun export(target: Uri) = viewModelScope.launch {
        runCatching {
            val core = core()
            val json = core.processingSettings.exportJson()
            requireNotNull(json)
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(target, "wt")!!.use { it.write(json.toByteArray()) } }
        }.onFailure { failed(it) }
    }

    fun importSettings(source: Uri) = viewModelScope.launch {
        runCatching {
            val json = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(source)!!.use { input ->
                    val bytes = input.readNBytes(1_048_577)
                    require(bytes.size <= 1_048_576)
                    bytes.decodeToString()
                }
            }
            when (val result = ProcessingSettingsParser.parse(json)) {
                is ProcessingParseResult.Valid -> _state.update { it.copy(draft = ProcessingDraft.from(result.document.settings), dirty = true, importing = true, message = null) }
                else -> _state.update { it.copy(message = UiMessage.Res(R.string.processing_unreadable)) }
            }
        }.onFailure { failed(it) }
    }

    private suspend fun refreshLocal() {
        val language = state.value.draft?.language ?: Language.KO
        val info = core().deps.localTranscription.status(language.name.lowercase().replace('_', '-'))
        _state.update { it.copy(local = info) }
    }
    private suspend fun core() = CoreModule.get(getApplication()).core
    private fun failed(error: Throwable) { _state.update { it.copy(busy = false, message = coreMessage(CoreMessage.STEP_FAILED, error.message.orEmpty())) } }
}
