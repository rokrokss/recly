package app.recly.android.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.blueprint

/**
 * docs/09 (user decision of 2026-10-09): a control whose only effect is to open a web page outside the app is a text
 * link, not a button — the accent ink, a dotted underline under each line, no border, box or fill, and 60 % while
 * pressed. [style] is the body size, or the secondary 12 where the link sits in a subtitle.
 *
 * The target is at least [MinTouch] each way, made of invisible padding that lies over the neighbours rather than
 * pushing them away: the layout keeps the text's own size, so a link under a subtitle makes its row no taller than
 * the line it adds. A screen reader hears the label as a link — Compose has no link role, so the node's text carries
 * one link over the whole label.
 */
@Composable
fun TextLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val palette = blueprint
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = palette.accent.copy(alpha = if (pressed) PRESSED else 1f)
    val click by rememberUpdatedState(onClick)
    val linked = remember(label) { buildAnnotatedString { withLink(LinkAnnotation.Clickable(label) { click() }) { append(label) } } }
    var lines by remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        content = {
            Text(
                label,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .drawBehind {
                        val layout = lines ?: return@drawBehind
                        val dot = palette.line.toPx()
                        val dots = PathEffect.dashPathEffect(floatArrayOf(dot, DOT_GAP.toPx()))
                        for (line in 0 until layout.lineCount) {
                            val y = layout.getLineBaseline(line) + UNDER_BASELINE.toPx() + dot / 2
                            drawLine(ink.copy(alpha = ink.alpha * DOTS), Offset(layout.getLineLeft(line), y), Offset(layout.getLineRight(line), y),
                                strokeWidth = dot, pathEffect = dots)
                        }
                    },
                style = style,
                color = ink,
                onTextLayout = { lines = it },
            )
            // The target, laid over the text and past it; the one node a screen reader reads.
            Box(Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick).semantics { text = linked })
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val text = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val reach = MinTouch.roundToPx()
        val targetWidth = maxOf(text.width, reach)
        val targetHeight = maxOf(text.height, reach)
        val target = measurables[1].measure(Constraints.fixed(targetWidth, targetHeight))
        layout(constraints.constrainWidth(text.width), constraints.constrainHeight(text.height)) {
            text.place(0, 0)
            target.place((text.width - targetWidth) / 2, (text.height - targetHeight) / 2)
        }
    }
}

/** The dots: the hairline's width, this far apart, this far under the baseline, at this share of the ink. */
private val DOT_GAP: Dp = 2.dp
private val UNDER_BASELINE: Dp = 2.dp
private const val DOTS = 0.7f
private const val PRESSED = 0.6f
