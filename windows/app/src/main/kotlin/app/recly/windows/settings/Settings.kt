package app.recly.windows.settings

import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Str
import app.recly.windows.ui.DisconnectPhase
import java.util.prefs.Preferences
import recly.core.model.Track

/**
 * docs/09 "접근성": light, dark, or whatever the OS says. Compose Desktop reads the system setting
 * (`isSystemInDarkTheme`), and this is the override over it — a per-machine choice like the
 * language, and stored beside it.
 */
enum class AppTheme(val key: String, val label: Str) {
    SYSTEM("", Str.THEME_SYSTEM),
    LIGHT("light", Str.THEME_LIGHT),
    DARK("dark", Str.THEME_DARK),
    ;

    companion object {
        fun of(key: String?): AppTheme = entries.firstOrNull { it.key.isNotEmpty() && it.key == key } ?: SYSTEM
    }
}

/**
 * docs/14 "캡처" · docs/12 M4-L3: what a recording on this PC is made of, and the Mac's
 * `RecordingMode` with the same two answers — the microphone alone, or the meeting with the system
 * audio in it. Picked before a recording and never during one: the track set is written into
 * `meta.json` at the start.
 *
 * The mode decides three things and they are all here, because all three are the same fact about a
 * recording: which tracks the helper is asked for ([tracks]), whether there is a meeting whose end
 * is worth detecting ([detectsEnd]), and whether there is anyone else to have told about the
 * recording ([remindsConsent]).
 */
enum class RecordingMode(val key: String, val label: Str, val tracks: List<Track>) {
    /** One `mono` track — a memo, and the helper opens no render endpoint for it. */
    MICROPHONE("microphone", Str.SETTINGS_CAPTURE_MODE_MICROPHONE, listOf(Track.MONO)),

    /** ADR-006: `mic`, `sys` and `mix`, sharing one start time and one segment boundary. */
    MEETING("meeting", Str.SETTINGS_CAPTURE_MODE_MEETING, listOf(Track.MIC, Track.SYS, Track.MIX)),
    ;

    /**
     * docs/14 "감지" · docs/12 "종료 감지": the end-of-meeting offer is about *this* recording, and
     * only a meeting has one — a memo's own idle microphone is not a meeting that has ended.
     */
    val detectsEnd: Boolean get() = this == MEETING

    /**
     * docs/12 M8 · ADR-011: the reminder is about the other participants, and a microphone-only
     * memo has none. The Mac asks the question under exactly this condition.
     */
    val remindsConsent: Boolean get() = this == MEETING

    companion object {
        /**
         * ADR-006: what a PC in front of a call records is a meeting far more often than it is a
         * memo, and every Windows recording before this setting existed was one — so a store that
         * has never been written keeps recording what it was recording.
         */
        fun of(key: String?): RecordingMode = entries.firstOrNull { it.key == key } ?: MEETING
    }
}

/**
 * The shell's own switches (docs/14 "감지" · docs/12 M8 · docs/07). Not the core's settings
 * document: like the Mac's `UserDefaults` (`MenuModel.Defaults`), none of these is worth syncing
 * between machines — whether *this* user has read the consent reminder, or which language they
 * want this PC in, is a fact about one PC.
 */
interface Settings {
    /** docs/12 M8: asked once before the first meeting recording, and switchable off in Settings. */
    var consentReminder: Boolean

    /** docs/14 "캡처": the microphone alone or the whole meeting — the Mac's `Defaults.mode`. */
    var recordingMode: RecordingMode

    /** docs/07 rule 2: system default, Korean or English, on this machine only. */
    var language: AppLanguage

    /** docs/09: the system's dark mode, or the user's override of it. */
    var theme: AppTheme

    /**
     * docs/03 "연결 해제" · docs/06: how far the last disconnect got. Persisted because the retry may
     * be a whole launch later — the tokens are already gone by then, and this is the only thing that
     * keeps the Disconnect row on screen and a second account out of the slot until it has finished.
     * The one setting an implementation has to write through synchronously: it is on disk before the
     * credentials it is about are deleted, or it is of no use at all. A write that could not be
     * confirmed therefore throws [java.util.prefs.BackingStoreException] rather than returning — the
     * caller must not delete anything over a phase that is not on disk (`DisconnectGuard.revoking`).
     */
    var disconnectPhase: DisconnectPhase

    /**
     * docs/03 "연결 해제": true while Google is still listing this app because the `/revoke` call
     * failed. The grant is then standing and only the user can take it down, so the debt outlives
     * the disconnect that could not pay it — and only the user saying they removed it by hand
     * clears it: a later revoke that succeeded may belong to another account, and the app keeps
     * no account identity to tell.
     *
     * The second setting an implementation has to write through synchronously, and for the same
     * reason as [disconnectPhase]: it is on disk before the refresh token it is about is deleted,
     * or a process killed in that gap comes back with no token, no way to revoke, and nothing on
     * screen saying the grant is still there. It throws on a write it could not confirm for that
     * reason too.
     */
    var revokeDebt: Boolean

    companion object {
        fun create(): Settings = PreferenceSettings()
    }
}

/**
 * `java.util.prefs`, which on Windows is `HKCU\Software\JavaSoft\Prefs\…` — the same hive the Run
 * key is in (`LaunchAtLogin`), so an uninstall that clears the user's registry clears these too. On
 * the macOS development host it is a plist, and nothing about that matters.
 */
class PreferenceSettings(
    private val prefs: Preferences = Preferences.userRoot().node(NODE),
) : Settings {

    override var consentReminder: Boolean
        get() = prefs.getBoolean(CONSENT_REMINDER, true)
        set(value) = prefs.putBoolean(CONSENT_REMINDER, value)

    override var recordingMode: RecordingMode
        get() = RecordingMode.of(prefs.get(RECORDING_MODE, ""))
        set(value) = prefs.put(RECORDING_MODE, value.key)

    override var language: AppLanguage
        get() = AppLanguage.of(prefs.get(LANGUAGE, ""))
        set(value) = prefs.put(LANGUAGE, value.tag)

    override var theme: AppTheme
        get() = AppTheme.of(prefs.get(THEME, ""))
        set(value) = prefs.put(THEME, value.key)

    override var disconnectPhase: DisconnectPhase
        get() = DisconnectPhase.of(prefs.get(DISCONNECT_PHASE, ""))
        set(value) {
            prefs.put(DISCONNECT_PHASE, value.name)
            // The one setting whose whole point is surviving a kill: it is written before the
            // credentials it is about are deleted (`DisconnectGuard.revoking`), and `java.util.prefs`
            // otherwise writes its file on a timer of its own — a process that died in that gap
            // would come back with the tokens gone and the store still saying nothing was owed.
            prefs.flush()
        }

    override var revokeDebt: Boolean
        get() = prefs.getBoolean(REVOKE_DEBT, false)
        set(value) {
            prefs.putBoolean(REVOKE_DEBT, value)
            // Flushed for the same reason the phase is: the failure branch writes this *before* it
            // deletes the refresh token, and a value still sitting in `java.util.prefs`' own timer
            // is a value that dies with the process that was about to need it.
            prefs.flush()
        }

    private companion object {
        /** `Preferences` wants a path, and `app.recly.windows` is not one. */
        const val NODE = "app/recly/windows"
        const val CONSENT_REMINDER = "consentReminder"

        /** The Mac's `Defaults.modeKey`, so the two shells' stores read the same on paper. */
        const val RECORDING_MODE = "recordingMode"
        const val LANGUAGE = "language"
        const val THEME = "theme"
        const val DISCONNECT_PHASE = "disconnectPhase"
        const val REVOKE_DEBT = "revokeDebt"
    }
}
