package app.recly.android.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.BlueprintColors

/**
 * docs/09 "Component measurements" · "Disabled, focus and structure" (2026-10-15): the focus ring — the accent at
 * `selectedLine`, inside the control (a hairline in from its edge, so the edge stays its own, as on Windows) and with
 * its [radius], so it is square like the control — on every focusable control, not only the fields. It lights under
 * a keyboard, a D-pad or Switch Access; a touch never focuses a clickable, so a tap does not.
 *
 * [onFill] is for a control filled with the accent, where an accent ring would vanish: its ring is the ink on that fill.
 *
 * It goes before the control's own background, border and click: before the click, so it hears the click's focus,
 * and before the border and the fill, so the ring is drawn over them rather than under.
 */
fun Modifier.focusRing(palette: BlueprintColors, radius: Dp = 0.dp, onFill: Boolean = false): Modifier =
    this then FocusRingElement(if (onFill) palette.onAccent else palette.accent, palette.selectedLine, palette.line, radius)

private data class FocusRingElement(val color: Color, val width: Dp, val inset: Dp, val radius: Dp) : ModifierNodeElement<FocusRingNode>() {
    override fun create(): FocusRingNode = FocusRingNode(color, width, inset, radius)

    override fun update(node: FocusRingNode) {
        node.color = color
        node.width = width
        node.inset = inset
        node.radius = radius
        node.invalidateDraw()
    }
}

private class FocusRingNode(var color: Color, var width: Dp, var inset: Dp, var radius: Dp) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val edge = inset.toPx()
        translate(edge, edge) {
            drawRing(color, width.toPx(), (radius.toPx() - edge).coerceAtLeast(0f), Size(size.width - edge * 2, size.height - edge * 2))
        }
    }
}

/** The ring inside [area]: a stroke is centred on its path, so the path is inset by half the stroke. */
internal fun DrawScope.drawRing(color: Color, stroke: Float, radius: Float, area: Size = size) {
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(area.width - stroke, area.height - stroke),
        cornerRadius = CornerRadius((radius - stroke / 2f).coerceAtLeast(0f)),
        style = Stroke(stroke),
    )
}
