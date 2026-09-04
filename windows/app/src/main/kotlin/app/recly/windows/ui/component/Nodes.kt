package app.recly.windows.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/** One node of the dashboard: a label and the value under it (docs/09 화면 원칙 1). */
data class NodeSpec(
    val label: String,
    val value: String,
    /** Null for the body colour; a state that means something (`REC`) says so in its own. */
    val valueColor: Color? = null,
    /** A node that is not doing anything takes the quiet border. */
    val active: Boolean = true,
    /** Work is running behind the value, and the node turns a loader beside it to say so. */
    val busy: Boolean = false,
)

/** A square node with a label and a monospace value. */
@Composable
fun StateNode(spec: NodeSpec, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(
        modifier = modifier
            // docs/09 "접근성": a label and its value are one fact, so a screen reader hears
            // "Workflow, 회의" rather than two unconnected runs of text.
            .semantics(mergeDescendants = true) {}
            .border(
                width = palette.line,
                color = if (spec.active) palette.text else palette.grid,
                shape = RoundedCornerShape(Radius.node),
            )
            .background(palette.surface, RoundedCornerShape(Radius.node))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            spec.label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (spec.busy) NodeLoader(spec.valueColor ?: palette.text)
            Text(
                spec.value,
                style = mono.bodySmall,
                color = spec.valueColor ?: palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The one loader this design has: an 8dp square outline turning beside a value, for work that is
 * running with no percentage to show for it.
 *
 * docs/09 "모션": motion is a state signal. Straight edges, no rounding and no fade — the square is
 * the same shape everything else on the screen is. It always turns: docs/09 says only the shells the
 * system tells follow reduce motion, and Windows tells a Compose Desktop app nothing about it. The
 * code beside it already says `UPLOADING`, which is the whole message either way.
 */
@Composable
private fun NodeLoader(color: Color) {
    val turn = rememberInfiniteTransition()
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(LOADER_TURN_MS, easing = LinearEasing)),
    )
    Box(
        Modifier
            .size(LOADER)
            .graphicsLayer { rotationZ = angle }
            .border(width = blueprint.line, color = color)
            .clearAndSetSemantics {},
    )
}

/** docs/09: 8dp, and one full turn slow enough to read as "still working" rather than "hurry". */
private val LOADER = 8.dp
private const val LOADER_TURN_MS = 1_200

/** The three dashboard nodes, joined edge to edge by straight 20dp connectors. */
@Composable
fun StateNodeRow(nodes: List<NodeSpec>, modifier: Modifier = Modifier) {
    val palette = blueprint
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        nodes.forEachIndexed { index, spec ->
            StateNode(spec, Modifier.weight(1f))
            if (index != nodes.lastIndex) {
                Box(
                    Modifier
                        .width(20.dp)
                        .height(palette.line)
                        .background(palette.text)
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

/** docs/09 "타이포": the timer is the one piece of data big enough to be a screen of its own. */
@Composable
fun MonoTimer(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        // docs/09 "접근성": it changes every second while a recording runs, and a reader that is not
        // told so hears the length the recording had when the window opened, for ever.
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = mono.timer,
        color = color ?: blueprint.text,
        maxLines = 1,
    )
}
