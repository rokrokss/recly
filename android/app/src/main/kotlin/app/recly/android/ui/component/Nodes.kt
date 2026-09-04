package app.recly.android.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.recly.android.ui.theme.LocalReduceMotion
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/** One node of the recording dashboard: a label and the value under it (docs/09 화면 원칙 1). */
data class NodeSpec(
    val label: String,
    val value: String,
    /** Null for the body colour; a state that means something (`REC`) says so in its own. */
    val valueColor: Color? = null,
    /** A node that is not doing anything takes the quiet border. */
    val active: Boolean = true,
    /** Work is running behind the value, and the node turns a loader beside it to say so. */
    val busy: Boolean = false,
    /** Set only on the one node that is a choice — the workflow picker (docs/09 화면 원칙 1). */
    val onClick: (() -> Unit)? = null,
    /** What tapping it does, said in words, because the node itself is a label and a value. */
    val onClickLabel: String? = null,
)

/** A square node with a label and a monospace value. */
@Composable
fun StateNode(spec: NodeSpec, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(
        modifier = modifier
            .border(
                width = palette.line,
                color = if (spec.active) palette.text else palette.grid,
                shape = RoundedCornerShape(Radius.node),
            )
            .background(palette.surface, RoundedCornerShape(Radius.node))
            .then(
                // docs/09 화면 원칙 1: only the node that is a choice takes a tap. The other two are
                // readouts, and a readout with a click on it is a control a screen reader announces
                // and a user cannot use.
                if (spec.onClick != null) {
                    Modifier.clickable(
                        onClickLabel = spec.onClickLabel,
                        role = Role.Button,
                        onClick = spec.onClick,
                    )
                } else {
                    Modifier
                },
            )
            // docs/09 "간격": the same 8dp as the vertical, which is what a 320dp phone has to
            // spare — a third of that row is nine characters of `UPLOADING` and little else.
            .padding(Space.s),
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
            // docs/09 "모션": reduce motion keeps the state and drops the movement, and the code
            // beside it already says `UPLOADING` — which is the whole message either way.
            if (spec.busy && !LocalReduceMotion.current) NodeLoader(spec.valueColor ?: palette.text)
            val value = mono.bodySmall
            // RecKit's `minimumScaleFactor(0.7)`, which is what a node this narrow needs: a third of
            // a 360dp row, less the loader, is not nine characters of `UPLOADING` at full size, and
            // a state code truncated to `UPLOAD…` is the one thing docs/09 화면 원칙 1 keeps when it
            // takes everything else away. Smaller, then, rather than shorter.
            BasicText(
                spec.value,
                style = value.copy(color = spec.valueColor ?: palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = value.fontSize * MIN_SCALE,
                    maxFontSize = value.fontSize,
                    stepSize = 1.sp,
                ),
            )
        }
    }
}

/**
 * The one loader this design has: an 8dp square outline turning beside a value, for work that is
 * running with no percentage to show for it.
 *
 * docs/09 "모션": motion is a state signal. Straight edges, no rounding and no fade — the square is
 * the same shape everything else on the screen is.
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

/** How far a value may shrink to stay whole — the Mac's own floor. */
private const val MIN_SCALE = 0.7f

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

/** docs/09 "타이포": the timer is the one piece of data big enough to be the screen. */
@Composable
fun MonoTimer(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        modifier = modifier,
        style = mono.timer,
        color = color ?: blueprint.text,
        maxLines = 1,
    )
}
