package app.recly.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.core.UiMessage
import app.recly.android.core.coreMessage
import app.recly.android.work.ModelDownload
import app.recly.android.work.ModelDownloadState
import app.recly.android.work.WorkScheduler
import app.recly.android.work.wireTag
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
    /** The speech model download, which Settings shares with the list and the Record tab. */
    val download: ModelDownloadState = ModelDownloadState(),
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
        viewModelScope.launch {
            ModelDownload.get(getApplication()).state.collect { download ->
                val before = state.value.download.phase
                _state.update { it.copy(download = download) }
                // A download that started, stopped or finished changes what the engine says.
                if (download.phase != before) runCatching { refreshLocal() }.onFailure { failed(it) }
            }
        }
    }

    fun edit(change: (ProcessingDraft) -> Unit) {
        val language = state.value.draft?.language
        _state.update { value -> value.copy(draft = value.draft?.snapshot()?.apply(change), dirty = true, message = null) }
        if (state.value.draft?.language != language) viewModelScope.launch { runCatching { refreshLocal() }.onFailure { failed(it) } }
    }

    /** The method chips — iPhone's `selectMode`, with its language rule ([selectTranscriptionMode]). */
    fun selectMode(mode: TranscriptionMode, deviceLocale: String) = edit { it.selectTranscriptionMode(mode, deviceLocale) }

    fun reload() = viewModelScope.launch {
        runCatching {
            val core = core()
            val stored = core.initializeProcessing()
            _state.value = ProcessingUiState(stored, ProcessingDraft.from(stored.document.settings), secretNames = core.secrets.names(),
                localInstalled = core.deps.localTranscription.installed, download = state.value.download)
            refreshLocal()
        }.onFailure { failed(it) }
    }

    /**
     * docs/03 "Storage location": the storage was switched in the section above, which saved the next
     * revision. The form takes that revision and keeps its draft — the iPhone's `storageChanged` —
     * so a Save after the switch is not refused as stale, and nothing typed is lost to it.
     */
    fun storageChanged() = viewModelScope.launch {
        runCatching {
            val stored = core().initializeProcessing()
            _state.update { it.copy(stored = stored) }
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
                        importing = false, message = null)
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
            _state.update { it.copy(secretNames = (it.secretNames + name).distinct(), message = null) }
        }.onFailure { failed(it) }
    }

    /**
     * docs/05 "Fixed processing settings": the model download, for the language on screen — the status line
     * above the button is that language's. Recordings waiting on it resume ([ModelDownload]).
     */
    fun downloadModel(onWifi: Boolean) =
        ModelDownload.get(getApplication()).start((state.value.draft?.language ?: Language.KO).wireTag(), onWifi)

    /** The partial files stay for a later resume. */
    fun cancelDownload() = ModelDownload.get(getApplication()).cancel()
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
            val bytes = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(source)!!.use { it.readNBytes(IMPORT_LIMIT + 1) }
            }
            when (val result = importedSettings(bytes)) {
                null -> _state.update { it.copy(message = UiMessage.Res(R.string.processing_unreadable)) }
                else -> _state.update { it.copy(draft = ProcessingDraft.from(result.document.settings), dirty = true, importing = true, message = null) }
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

/** The largest settings file an import reads — the iPhone's limit. */
internal const val IMPORT_LIMIT: Int = 1_048_576

/**
 * What an import of [bytes] offers as a draft, or null for a file this version cannot read — one
 * over [IMPORT_LIMIT] included, which is the iPhone's same sentence rather than a failure of its own.
 */
internal fun importedSettings(bytes: ByteArray): ProcessingParseResult.Valid? =
    if (bytes.size > IMPORT_LIMIT) null else ProcessingSettingsParser.parse(bytes.decodeToString()) as? ProcessingParseResult.Valid

