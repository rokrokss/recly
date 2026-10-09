package app.recly.windows.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.blueprint

/**
 * docs/09 (2026-10-09): a control whose only effect is to open a web page outside the app is a link, not a
 * button — the accent, a dotted underline, no box. Pressed, it fades to 60 %.
 *
 * It takes the height of its text in the row it sits in: the [MinTouch] target around it is invisible and laid
 * over the lines next to it, so a row with a link in it is no taller than its words. Heard as a button with its
 * [label]: Compose Desktop's accessibility bridge has no link role to give it.
 */
@Composable
fun TextLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The body size on its own; the secondary size where it sits in a row's second line. */
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val palette = blueprint
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Written while the text is measured, and read in the same pass: not state, which would measure twice.
    val text = remember { arrayOfNulls<TextLayoutResult>(1) }
    val dots = palette.accent.copy(alpha = UNDERLINE_ALPHA)
    Box(
        modifier
            .layout { measurable, constraints ->
                val target = measurable.measure(constraints.copy(minHeight = 0))
                val height = text[0]?.size?.height?.coerceAtMost(target.height) ?: target.height
                layout(target.width, height) { target.place(0, (height - target.height) / 2) }
            }
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            modifier = Modifier
                .alpha(if (pressed) PRESSED_ALPHA else 1f)
                .drawBehind {
                    val layout = text[0] ?: return@drawBehind
                    val dot = UNDERLINE_DOT.toPx()
                    val effect = PathEffect.dashPathEffect(floatArrayOf(dot, dot))
                    for (line in 0 until layout.lineCount) {
                        val y = layout.getLineBaseline(line) + UNDERLINE_GAP.toPx() + dot / 2
                        drawLine(dots, Offset(layout.getLineLeft(line), y), Offset(layout.getLineRight(line), y), dot, pathEffect = effect)
                    }
                },
            style = style,
            color = palette.accent,
            onTextLayout = { text[0] = it },
        )
    }
}

/** 1 px dots on 1 px gaps, 2 px under the baseline, the accent at 70 %. */
private val UNDERLINE_DOT: Dp = 1.dp
private val UNDERLINE_GAP: Dp = 2.dp
private const val UNDERLINE_ALPHA = 0.7f
private const val PRESSED_ALPHA = 0.6f
