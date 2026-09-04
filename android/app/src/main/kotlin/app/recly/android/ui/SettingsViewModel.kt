@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.core.CoreModule
import app.recly.android.settings.AppLanguage
import app.recly.android.settings.AppSurfaces
import app.recly.android.settings.AppSettings
import app.recly.android.settings.AppTheme
import app.recly.android.settings.LanguageSetting
import app.recly.android.settings.SystemLocaleStore
import app.recly.android.ui.component.ProcessingState
import app.recly.android.work.WorkScheduler
import app.recly.android.work.applyNetworkSetting
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

data class SettingsUiState(
    val wifiOnly: Boolean = false,
    /** docs/12 M8: the recording-consent reminder, on until the user says not to ask again. */
    val consentReminder: Boolean = true,
    /** docs/09 "접근성": the system's dark mode until this device says otherwise. */
    val theme: AppTheme = AppTheme.SYSTEM,
    /** docs/05 "워크플로우 내보내기 · 가져오기": the section that moves definitions between devices. */
    val transfer: WorkflowTransferUiState = WorkflowTransferUiState(),
)

/** docs/11 A10, the M2 slice: the language (docs/07) and the network setting. The account lives on
 * the same screen but is [MainViewModel]'s, which already owns sign-in. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AppSettings(application)
    private val language = LanguageSetting(SystemLocaleStore(application), AppSurfaces(application))

    // The language is not in the state: the platform recreates the activities on a change, and what
    // the screen draws is the locale its own words were resolved in (docs/07 rule 3).
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        mirror(settings.wifiOnly) { copy(wifiOnly = it) }
        mirror(settings.consentReminder) { copy(consentReminder = it) }
        mirror(settings.theme) { copy(theme = it) }
    }

    /** The store owns these for the life of the install; the screen only ever mirrors them. */
    private fun <T> mirror(flow: Flow<T>, into: SettingsUiState.(T) -> SettingsUiState) {
        viewModelScope.launch { flow.collect { value -> _state.update { it.into(value) } } }
    }

    /** docs/12 M8: switching it back on is "ask me again", which the store takes care of. */
    fun setConsentReminder(value: Boolean) {
        viewModelScope.launch { settings.setConsentReminder(value) }
    }

    /**
     * docs/09 "접근성": nothing to recreate — the theme is read in the composition, so the store
     * emitting the new value is the whole of the change (the language's activity recreation is
     * `setLanguage`'s, and only its).
     */
    fun setTheme(value: AppTheme) {
        viewModelScope.launch { settings.setTheme(value) }
    }

    // --- docs/05 "워크플로우 내보내기 · 가져오기" -------------------------------------------------

    /**
     * The document as it is stored, into the file SAF just created for the user. The pointer is not
     * in it and neither are the secret values — the core's `exportJson` decides that, not this.
     */
    fun exportWorkflows(target: Uri) {
        editTransfer { it.copy(exporting = ProcessingState.PROCESSING) }
        viewModelScope.launch {
            val core = CoreModule.get(getApplication<Application>()).core
            val json = core.workflows.exportJson()
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    resolver().openOutputStream(target, "wt")?.use { it.write(json.encodeToByteArray()) }
                        ?: error("no such document")
                }
            }
            written.fold(
                onSuccess = { editTransfer { WorkflowTransfer.exported(it) } },
                onFailure = { e -> editTransfer { WorkflowTransfer.fileFailed(it, e.reason()) } },
            )
        }
    }

    /**
     * The picked file, read and parsed for the one number the confirmation has to name. A file that
     * does not parse never gets a confirmation: `importJson` refuses it without writing anything,
     * and it is the one place the parser's complaints are turned into the list the editor shows.
     */
    fun pickImport(source: Uri) {
        editTransfer { it.copy(importing = ProcessingState.PROCESSING) }
        viewModelScope.launch {
            val json = runCatching {
                withContext(Dispatchers.IO) {
                    resolver().openInputStream(source)?.use { it.readBytes().decodeToString() }
                        ?: error("no such document")
                }
            }.getOrElse { e ->
                editTransfer { WorkflowTransfer.fileFailed(it, e.reason()) }
                return@launch
            }
            when (val parsed = WorkflowParser.parse(json)) {
                is ParseResult.Ok ->
                    editTransfer { WorkflowTransfer.picked(it, json, parsed.document.workflows.size) }

                else -> {
                    val core = CoreModule.get(getApplication<Application>()).core
                    val result = core.workflows.importJson(json)
                    editTransfer { WorkflowTransfer.imported(it, result) }
                }
            }
        }
    }

    fun cancelImport() = editTransfer { WorkflowTransfer.cancelled(it) }

    /** docs/05: the confirmed replace. There is no merge — the file becomes the whole document. */
    fun confirmImport() {
        val picked = _state.value.transfer.confirm ?: return
        // Down before the write starts: a cancel during a slow replace must find no dialog to
        // "cancel" a write that is already running.
        editTransfer { WorkflowTransfer.confirmed(it) }
        viewModelScope.launch {
            val core = CoreModule.get(getApplication<Application>()).core
            val result = core.workflows.importJson(picked.json)
            editTransfer { WorkflowTransfer.imported(it, result) }
        }
    }

    private fun editTransfer(edit: (WorkflowTransferUiState) -> WorkflowTransferUiState) =
        _state.update { it.copy(transfer = edit(it.transfer)) }

    private fun resolver() = getApplication<Application>().contentResolver

    /** A `ContentResolver` failure is a diagnostic, never a sentence — it is shown under one. */
    private fun Throwable.reason(): String = message ?: this::class.simpleName.orEmpty()

    /**
     * docs/07 rule 3: the platform recreates the activities on this, so the screens redraw in the
     * new language without a restart. What it does not recreate — the recording notification, the
     * home widget — [AppSurfaces] asks for. Nothing is written here; the store is the platform's.
     */
    fun setLanguage(value: AppLanguage) {
        language.select(value)
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(value)
            // Written first: everything rebuilt below reads the setting back to pick its
            // NetworkType, so the new value has to be the one on disk.
            val core = CoreModule.get(getApplication<Application>()).core
            applyNetworkSetting(
                scheduler = WorkScheduler(getApplication()),
                jobs = core.jobs.observe().first(),
                now = core.deps.clock.now(),
            )
        }
    }
}
