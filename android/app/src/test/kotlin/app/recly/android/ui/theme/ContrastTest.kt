package app.recly.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/09 "접근성": WCAG AA — 4.5:1 for text, 3:1 for a graphic — for every pair the app actually
 * draws, in both palettes (light and dark).
 *
 * What is *not* in here is the grid colour against its background. A hairline divider carries no
 * information (WCAG 1.4.11 is about graphics you must see to understand the content), and docs/09
 * asks for it to be quiet.
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

    /**
     * A selected node and a selected chip draw [BlueprintColors.selectedLine], which has to stay
     * heavier than the lines around it or the selection says nothing at all.
     */
    @Test
    fun `a selection is always drawn heavier than the line it sits among`() {
        palettes().forEach { (name, palette) ->
            assertTrue(
                palette.selectedLine > palette.line,
                "$name draws a selection at ${palette.selectedLine}, no heavier than its ${palette.line} line",
            )
        }
    }

    /** The check itself, on a pair whose answer is known: black on white is 21:1. */
    @Test
    fun `the ratio is the WCAG one`() {
        val black = contrastRatio(Color(0xFF000000), Color(0xFFFFFFFF))
        assertTrue(black > 20.9 && black < 21.1, "black on white is $black, not 21:1")
        assertTrue(contrastRatio(Color(0xFF777777), Color(0xFF777777)) == 1.0)
    }

    private fun palettes(): List<Pair<String, BlueprintColors>> = listOf(
        "light" to blueprintColors(dark = false),
        "dark" to blueprintColors(dark = true),
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
        // The two filled surfaces: the primary button and the recording node.
        put("onAccent on accent", p.onAccent to p.accent)
        put("onDanger on danger", p.onDanger to p.danger)
    }

    /** Badge borders and node edges — colour that means something without being read. */
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
