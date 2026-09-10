package app.recly.android.ui

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.recly.android.ui.theme.BlueprintColors
import app.recly.android.ui.theme.ReclyTheme
import app.recly.android.ui.theme.blueprint
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises system callbacks on an isolated emulator; never changes a physical device's settings. */
@SdkSuppress(minSdkVersion = 36)
class HighContrastThemeTest {
    @get:Rule val ui = createComposeRule()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Test fun contrastPreferencesUpdateAnAlreadyMountedTheme() {
        assumeTrue(shell("getprop ro.kernel.qemu") == "1")
        val keys = listOf("high_text_contrast_enabled", "contrast_level")
        val originals = keys.associateWith { shell("settings get secure $it") }
        val current = AtomicReference<BlueprintColors>()
        try {
            keys.forEach { shell("settings put secure $it 0") }
            ui.setContent {
                ReclyTheme {
                    val palette = blueprint
                    SideEffect { current.set(palette) }
                }
            }
            ui.waitUntil(5_000) { current.get()?.highContrast == false }
            shell("settings put secure high_text_contrast_enabled 1")
            ui.waitUntil(5_000) { current.get()?.highContrast == true }
            assertEquals(current.get().text, current.get().inputBorder)
            shell("settings put secure high_text_contrast_enabled 0")
            ui.waitUntil(5_000) { current.get()?.highContrast == false }
            shell("settings put secure contrast_level 1.0")
            ui.waitUntil(5_000) { current.get()?.highContrast == true }
            shell("settings put secure contrast_level 0")
            ui.waitUntil(5_000) { current.get()?.highContrast == false }
        } finally {
            originals.forEach { (key, value) ->
                shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
            }
        }
    }
}
