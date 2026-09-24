@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.core.CoreModule
import app.recly.android.settings.AppLanguage
import app.recly.android.settings.AppSurfaces
import app.recly.android.settings.AppSettings
import app.recly.android.settings.AppTheme
import app.recly.android.settings.LanguageSetting
import app.recly.android.settings.SystemLocaleStore
import app.recly.android.work.WorkScheduler
import app.recly.android.work.applyNetworkSetting
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val wifiOnly: Boolean = false,
    /** docs/12 M8: the recording-consent reminder, on until the user says not to ask again. */
    val consentReminder: Boolean = true,
    /** docs/09 "접근성": the system's dark mode until this device says otherwise. */
    val theme: AppTheme = AppTheme.SYSTEM,
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
