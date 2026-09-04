package app.recly.wear.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/09 "접근성" on the watch. The watch has one palette and no toggles — a Wear screen follows the
 * system, and there is no settings screen to put a switch on (docs/07: the watch takes the system
 * language too) — so what is checked is that the one palette clears WCAG AA.
 */
class WearContrastTest {

    @Test
    fun `every text pair clears 4_5 to 1`() {
        val pairs = mapOf(
            "text on background" to (WearBlueprint.text to WearBlueprint.background),
            "text on surface" to (WearBlueprint.text to WearBlueprint.surface),
            "textMuted on background" to (WearBlueprint.textMuted to WearBlueprint.background),
            "textMuted on surface" to (WearBlueprint.textMuted to WearBlueprint.surface),
            "danger on background" to (WearBlueprint.danger to WearBlueprint.background),
            "accent on background" to (WearBlueprint.accent to WearBlueprint.background),
            "success on background" to (WearBlueprint.success to WearBlueprint.background),
            "warning on background" to (WearBlueprint.warning to WearBlueprint.background),
            // The filled record node, and the stop square inside it.
            "onDanger on danger" to (WearBlueprint.onDanger to WearBlueprint.danger),
            "onAccent on accent" to (WearBlueprint.onAccent to WearBlueprint.accent),
        )
        pairs.forEach { (name, colors) ->
            val ratio = contrastRatio(colors.first, colors.second)
            assertTrue(ratio >= 4.5, "$name is ${"%.2f".format(ratio)}:1, WCAG AA wants 4.5:1")
        }
    }

    @Test
    fun `the record node's edge clears 3 to 1 against the screen`() {
        val ratio = contrastRatio(WearBlueprint.danger, WearBlueprint.background)
        assertTrue(ratio >= 3.0, "the record edge is ${"%.2f".format(ratio)}:1")
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val first = relativeLuminance(a)
        val second = relativeLuminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
