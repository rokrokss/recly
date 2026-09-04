package app.recly.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.recly.android.ui.DisconnectPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * docs/11 A10, the part M2 needs: whether uploads may use mobile data. It is read by
 * `WorkScheduler` on every enqueue, so the constraint on a new request is always the current
 * answer — the requests already queued keep the constraint they were made with, which is why
 * changing it re-enqueues them.
 */
class AppSettings(private val context: Context) {

    val wifiOnly: Flow<Boolean> = context.store.data.map { it[WIFI_ONLY] ?: false }

    /**
     * docs/12 M8 · ADR-011: the recording-consent reminder, on by default. A phone has no meeting
     * detection, so the trigger is the first recording rather than every meeting one — [askConsent]
     * is the two flags together, and the setting on its own is what the switch shows.
     */
    val consentReminder: Flow<Boolean> = context.store.data.map { it[CONSENT_REMINDER] ?: true }

    /** True until the reminder has been answered once, either way. */
    val askConsent: Flow<Boolean> =
        context.store.data.map { (it[CONSENT_REMINDER] ?: true) && (it[CONSENT_ASKED] != true) }

    /**
     * docs/09 "접근성": the system's dark mode, or this device's own override of it. Nothing written
     * yet is [AppTheme.SYSTEM] — the OS decides until the user says otherwise. It is not synced, for
     * the same reason the language is not (docs/07 rule 2): it is a fact about one device.
     */
    val theme: Flow<AppTheme> = context.store.data.map { AppTheme.of(it[THEME]) }

    /**
     * docs/03 "연결 해제" · docs/06: how far the last disconnect got (see [DisconnectPhase]). It is
     * the one source of truth for a disconnect that is still owed, and it survives the process
     * because the retry may be a whole launch later — the account is already cleared by then, so
     * nothing else would keep the Disconnect row on screen or a second account out of the slot.
     */
    val disconnectPhase: Flow<DisconnectPhase> =
        context.store.data.map { DisconnectPhase.of(it[DISCONNECT_PHASE]) }

    /**
     * docs/03: a revoke Google refused, still owed. It is durable for the same reason
     * [disconnectPhase] is, and written before the account it is about is deleted: with the account
     * gone nothing else can tell a revoke that happened from one that never did, and the flow would
     * report success over a grant Google is still listing. Only the user's own word clears it.
     */
    val revokeDebt: Flow<Boolean> = context.store.data.map { it[REVOKE_DEBT] ?: false }

    suspend fun setWifiOnly(value: Boolean) {
        context.store.edit { it[WIFI_ONLY] = value }
    }

    /**
     * Switching it back on means "ask me again", so it clears the answer as well: the reminder is
     * a one-off, and a setting that could never make it appear again would be a dead switch.
     */
    suspend fun setConsentReminder(value: Boolean) {
        context.store.edit {
            it[CONSENT_REMINDER] = value
            if (value) it[CONSENT_ASKED] = false
        }
    }

    suspend fun setTheme(value: AppTheme) {
        context.store.edit { it[THEME] = value.key }
    }

    /** The reminder has been put to the user; whatever they answered, it is not asked again. */
    suspend fun markConsentAsked() {
        context.store.edit { it[CONSENT_ASKED] = true }
    }

    suspend fun setDisconnectPhase(phase: DisconnectPhase) {
        context.store.edit { it[DISCONNECT_PHASE] = phase.name }
    }

    suspend fun setRevokeDebt(value: Boolean) {
        context.store.edit { it[REVOKE_DEBT] = value }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val THEME = stringPreferencesKey("theme")
        val DISCONNECT_PHASE = stringPreferencesKey("disconnect_phase")
        val REVOKE_DEBT = booleanPreferencesKey("revoke_debt")
        val CONSENT_REMINDER = booleanPreferencesKey("consent_reminder")
        val CONSENT_ASKED = booleanPreferencesKey("consent_asked")
    }
}
