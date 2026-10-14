package app.recly.android.ui

import androidx.compose.ui.graphics.toArgb
import app.recly.android.ui.theme.BlueprintColors
import app.recly.android.ui.theme.BlueprintDark
import app.recly.android.ui.theme.BlueprintLight
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 "Tokens": the palette is written out once per shell — Android's theme, Windows' theme, RecKit's
 * `BlueprintPalette` (iPhone, Mac, Apple Watch) and Wear's dark-only `WearBlueprint` — by hand, because no
 * build shares a file between them. This reads the other copies as text and holds them to Android's, token by
 * token, so a hex changed in one place fails here instead of on one platform's screen (2026-10-15). RecKit's own
 * `BlueprintTokenTests` hold the Apple widget copies to `BlueprintPalette`.
 */
class PaletteParityTest {

    @Test
    fun `Windows writes the same light and dark palette`() {
        val text = file("windows/app/src/main/kotlin/app/recly/windows/ui/theme/Colors.kt")
        assertSame("Windows light", tokens(BlueprintLight), kotlinBlock(text, "BlueprintLight"))
        assertSame("Windows dark", tokens(BlueprintDark), kotlinBlock(text, "BlueprintDark"))
    }

    @Test
    fun `RecKit writes the same light and dark palette`() {
        val text = file("apple/RecKit/Sources/RecKit/Design/BlueprintPalette.swift")
        assertSame("RecKit light", tokens(BlueprintLight), swiftBlock(text, "light"))
        assertSame("RecKit dark", tokens(BlueprintDark), swiftBlock(text, "dark"))
    }

    @Test
    fun `Wear's dark palette is the phones' dark palette`() {
        val wear = File(REPO_ROOT, "android/wear/src/main/kotlin").walkTopDown().first { it.name == "WearBlueprint.kt" }.readText()
        val declared = Regex("""val (\w+): Color = Color\(0x([0-9A-Fa-f]{8})\)""").findAll(wear)
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }
        assertTrue(declared.isNotEmpty(), "WearBlueprint declares no colors")
        val dark = tokens(BlueprintDark)
        declared.forEach { (name, hex) ->
            dark[name]?.let { assertEquals(it, hex, "Wear $name") }
        }
        assertTrue(declared.keys.count { it in dark } >= 9, "Wear shares the base tokens: ${declared.keys}")
    }

    private fun assertSame(what: String, expected: Map<String, String>, actual: Map<String, String>) {
        assertTrue(actual.isNotEmpty(), "$what: no colors read")
        actual.forEach { (name, hex) -> expected[name]?.let { assertEquals(it, hex, "$what $name") } }
        assertEquals(expected.keys.filter { it in BASE }.toSet(), actual.keys.filter { it in BASE }.toSet(), "$what: the base tokens")
    }

    /** Android's palette as `name → AARRGGBB`, read from the objects themselves. */
    private fun tokens(colors: BlueprintColors): Map<String, String> = mapOf(
        "background" to colors.background, "surface" to colors.surface, "grid" to colors.grid, "text" to colors.text,
        "textMuted" to colors.textMuted, "accent" to colors.accent, "onAccent" to colors.onAccent, "danger" to colors.danger,
        "onDanger" to colors.onDanger, "success" to colors.success, "warning" to colors.warning, "warningInk" to colors.warningInk,
    ).mapValues { "%08X".format(it.value.toArgb()) }

    /** `name = Color(0xFF……)` lines between `val [name]… = BlueprintColors(` and its closing parenthesis. */
    private fun kotlinBlock(text: String, name: String): Map<String, String> {
        val start = text.indexOf("val $name")
        val body = text.substring(start, text.indexOf("\n)", start))
        return Regex("""(\w+) = Color\(0x([0-9A-Fa-f]{8})\)""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].uppercase() }
    }

    /** `0x……, // name` lines of `public static let [name] = BlueprintPalette(`, as opaque `FF……`. */
    private fun swiftBlock(text: String, name: String): Map<String, String> {
        val start = text.indexOf("public static let $name = BlueprintPalette(")
        val body = text.substring(start, text.indexOf("]", start))
        return Regex("""0x([0-9A-Fa-f]{6}),\s*// (\w+)""").findAll(body)
            .associate { it.groupValues[2] to "FF" + it.groupValues[1].uppercase() }
    }

    private fun file(path: String): String = File(REPO_ROOT, path).readText()

    private companion object {
        val REPO_ROOT: File = File("../..").canonicalFile
        val BASE = setOf("background", "surface", "grid", "text", "textMuted", "accent", "onAccent", "danger", "onDanger", "success", "warning", "warningInk")
    }
}
