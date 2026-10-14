package app.recly.windows.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import app.recly.windows.ui.theme.blueprint

/**
 * docs/09 "Component measurements" · "Disabled, focus and structure": the focus ring of every focusable control — the
 * accent at `selectedLine`, inside the control (a hairline in from its edge, so the edge stays its own) and the shape it
 * is. Material's ripple tints a focused control by a tenth, which is not a cue anybody finds.
 *
 * Placed before the control's `clickable`, `selectable` or `toggleable`, whose focus it follows. Drawn only while the
 * keyboard drives: Compose Desktop focuses what a click lands on and turns its input mode to touch for it, so a ring
 * after every click would be noise — Tab turns the mode back, and the ring with it.
 *
 * [color] is for a control filled with the accent, where an accent ring would vanish.
 */
fun Modifier.focusRing(shape: Shape = RectangleShape, color: Color? = null): Modifier = composed {
    val palette = blueprint
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val ink = color ?: palette.accent
    val inset = palette.line
    val width = palette.selectedLine
    onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused && keyboard) {
                val stroke = width.toPx()
                val edge = inset.toPx() + stroke / 2f
                val outline = shape.createOutline(Size(size.width - edge * 2, size.height - edge * 2), layoutDirection, this)
                translate(edge, edge) { drawOutline(outline, ink, style = Stroke(stroke)) }
            }
        }
}
