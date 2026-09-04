package app.recly.windows.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/09 "접근성": WCAG AA — 4.5:1 for text, 3:1 for a graphic — for every pair this shell actually
 * draws, in all four palettes (light, dark, and each of them in high contrast).
 *
 * What is *not* in here is the grid colour against its background. A hairline divider carries no
 * information (WCAG 1.4.11 is about graphics you must see to understand the content), and docs/09
 * asks for it to be quiet; in high contrast it is promoted to the body colour anyway, which is the
 * variant a user who needs to see it turns on.
 */
class ContrastTest {

    @Test
    fun `text pairs clear 4_5 to 1 in every palette`() {
        palettes().forEach { (name, palette) ->
            textPairs(palette).forEach { (pair, colors) ->
                val ratio = contrastRatio(colors.first, colors.second)
                assertTrue(ratio >= 4.5, "$name/$pair is ${"%.2f".format(ratio)}:1, WCAG AA wants 4.5:1")
            }
        }
    }

    @Test
    fun `status graphics clear 3 to 1 in every palette`() {
        palettes().forEach { (name, palette) ->
            graphicPairs(palette).forEach { (pair, colors) ->
                val ratio = contrastRatio(colors.first, colors.second)
                assertTrue(ratio >= 3.0, "$name/$pair is ${"%.2f".format(ratio)}:1, WCAG AA wants 3:1")
            }
        }
    }

    /** The check itself, on a pair whose answer is known: black on white is 21:1. */
    @Test
    fun `the ratio is the WCAG one`() {
        val black = contrastRatio(Color(0xFF000000), Color(0xFFFFFFFF))
        assertTrue(black > 20.9 && black < 21.1, "black on white is $black, not 21:1")
        assertTrue(contrastRatio(Color(0xFF777777), Color(0xFF777777)) == 1.0)
    }

    /** docs/09 "고대비 모드": the quiet colours are promoted and the borders double. */
    @Test
    fun `high contrast promotes the grid and the muted text, and thickens the lines`() {
        listOf(false, true).forEach { dark ->
            val plain = blueprintColors(dark, highContrast = false)
            val strong = blueprintColors(dark, highContrast = true)
            assertTrue(strong.grid == strong.text, "the grid is still quiet in high contrast")
            assertTrue(strong.textMuted == strong.text, "the secondary text is still quiet")
            assertTrue(strong.accent == plain.accent, "the accent lost its saturation")
            assertTrue(strong.line.value == 2f && plain.line.value == 1f, "the border width did not change")
            // A chosen chip has to stay heavier than an unchosen one *in high contrast too*, where
            // the hairline is already 2dp — a flat 2dp selection said nothing there.
            assertTrue(
                strong.selectedLine > strong.line && plain.selectedLine > plain.line,
                "the selection border is not heavier than the hairline",
            )
        }
    }

    /**
     * docs/09 "접근성" on Windows: the high-contrast setting *is* readable (AWT's
     * `win.highContrast.on`), and the system is the whole of the answer.
     */
    @Test
    fun `high contrast is the system's, and a machine that did not say is normal contrast`() {
        assertTrue(highContrastOf(system = true))
        assertTrue(!highContrastOf(system = false))
        // The macOS development host, and any Windows toolkit that did not publish the property.
        assertTrue(!highContrastOf(system = null))
    }

    private fun palettes(): List<Pair<String, BlueprintColors>> = listOf(
        "light" to blueprintColors(dark = false, highContrast = false),
        "dark" to blueprintColors(dark = true, highContrast = false),
        "light-hc" to blueprintColors(dark = false, highContrast = true),
        "dark-hc" to blueprintColors(dark = true, highContrast = true),
    )

    /** Every colour this design puts letters in, on both grounds it puts them on. */
    private fun textPairs(p: BlueprintColors): Map<String, Pair<Color, Color>> = buildMap {
        listOf(
            "text" to p.text,
            "textMuted" to p.textMuted,
            "accent" to p.accent,
            "danger" to p.danger,
            "success" to p.success,
            "warningInk" to p.warningInk,
        ).forEach { (name, ink) ->
            put("$name on surface", ink to p.surface)
            put("$name on background", ink to p.background)
        }
        // The one filled surface this shell has: the primary button.
        put("onAccent on accent", p.onAccent to p.accent)
        put("onDanger on danger", p.onDanger to p.danger)
    }

    /** Badge borders, node edges and switch tracks — colour that means something without being read. */
    private fun graphicPairs(p: BlueprintColors): Map<String, Pair<Color, Color>> = buildMap {
        listOf(
            "accent" to p.accent,
            "danger" to p.danger,
            "success" to p.success,
            "warning" to p.warning,
            "textMuted" to p.textMuted,
        ).forEach { (name, edge) ->
            put("$name edge on surface", edge to p.surface)
            put("$name edge on background", edge to p.background)
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = relativeLuminance(a)
        val second = relativeLuminance(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** WCAG 2.1 relative luminance, from sRGB. */
    private fun relativeLuminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
