package app.recly.android.settings

import android.app.UiModeManager

/**
 * docs/09 "Accessibility": light, dark, or whatever the OS says. Compose reads the system setting
 * (`isSystemInDarkTheme`), and this is the override over it — a per-device choice like the language
 * (docs/07 rule 2) and stored beside it. The PC's `AppTheme` is the same three answers.
 *
 * [key] is what the store holds; the empty one is "nothing chosen", which is the system's answer.
 */
enum class AppTheme(val key: String) {
    SYSTEM(""),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Whether the app draws dark. [system] is what the OS says, which only [SYSTEM] defers to. */
    fun isDark(system: Boolean): Boolean = when (this) {
        SYSTEM -> system
        LIGHT -> false
        DARK -> true
    }

    /**
     * The same choice told to the system ([UiModeManager.setApplicationNightMode]), which keeps it for the next
     * launch: the launch window is `Theme.DeviceDefault.DayNight` and draws before Compose has read the store, so
     * without it an override flashed the other theme first. [SYSTEM] is the system's own answer (`AUTO`).
     */
    val nightMode: Int
        get() = when (this) {
            SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            LIGHT -> UiModeManager.MODE_NIGHT_NO
            DARK -> UiModeManager.MODE_NIGHT_YES
        }

    companion object {
        /** Anything the store does not recognise — nothing written yet, another build's value — is
         * [SYSTEM]. */
        fun of(key: String?): AppTheme = entries.firstOrNull { it.key.isNotEmpty() && it.key == key } ?: SYSTEM
    }
}
