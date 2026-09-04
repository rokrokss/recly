package app.recly.android.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/09 "접근성": the store keeps a key and the theme decides one thing from it. `AppSettings`
 * itself is DataStore over a `Context` and not something the JVM can drive (the same reason
 * `LanguageSettingTest` tests the setting rather than the platform), so what is tested here is the
 * round trip through the key the store writes.
 */
class AppThemeTest {

    @Test
    fun `every choice comes back out of the store as itself`() {
        AppTheme.entries.forEach { theme ->
            assertEquals(theme, AppTheme.of(theme.key), "the store's own key")
        }
    }

    /** Nothing written yet, and anything a later build might have written, is the system's answer. */
    @Test
    fun `an unset or unknown value follows the system`() {
        assertEquals(AppTheme.SYSTEM, AppTheme.of(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.of(""))
        assertEquals(AppTheme.SYSTEM, AppTheme.of("sepia"))
    }

    /** The override is an override in both directions; only [AppTheme.SYSTEM] asks the OS. */
    @Test
    fun `only the system default defers to the system`() {
        assertTrue(AppTheme.SYSTEM.isDark(system = true))
        assertFalse(AppTheme.SYSTEM.isDark(system = false))
        assertFalse(AppTheme.LIGHT.isDark(system = true))
        assertTrue(AppTheme.DARK.isDark(system = false))
    }
}
