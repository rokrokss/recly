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
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.BlueprintColors

/**
 * docs/09 "Component measurements" · "Disabled, focus and structure" (2026-10-15): the focus ring — the accent at
 * `selectedLine`, drawn inside the control and with its [radius], so it is square like the control — on every
 * focusable control, not only the fields. It lights under a keyboard, a D-pad or Switch Access; a touch never
 * focuses a clickable, so a tap does not.
 *
 * It goes before the control's own background, border and click: before the click, so it hears the click's focus,
 * and before the border, so the ring is drawn over the control's edge rather than under it.
 */
fun Modifier.focusRing(palette: BlueprintColors, radius: Dp = 0.dp): Modifier =
    this then FocusRingElement(palette.accent, palette.selectedLine, radius)

private data class FocusRingElement(val color: Color, val width: Dp, val radius: Dp) : ModifierNodeElement<FocusRingNode>() {
    override fun create(): FocusRingNode = FocusRingNode(color, width, radius)

    override fun update(node: FocusRingNode) {
        node.color = color
        node.width = width
        node.radius = radius
        node.invalidateDraw()
    }
}

private class FocusRingNode(var color: Color, var width: Dp, var radius: Dp) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) drawRing(color, width.toPx(), radius.toPx())
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
