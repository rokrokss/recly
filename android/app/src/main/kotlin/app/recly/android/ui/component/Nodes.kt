package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.android.ui.theme.monoWords

/** One node of the recording dashboard: a label and the value under it (docs/09 screen principle 1). */
data class NodeSpec(
    val label: String,
    val value: String,
    /** Null for the body colour; a state that means something (`REC`) says so in its own. */
    val valueColor: Color? = null,
    /** A node that is not doing anything takes the quiet border. */
    val active: Boolean = true,
    /** Work is running behind the value, and the node turns a loader beside it to say so. */
    val busy: Boolean = false,
    /** Set only on the one node that takes a tap — the transcription node (docs/09 screen principle 1). */
    val onClick: (() -> Unit)? = null,
    /** What tapping it does, said in words, because the node itself is a label and a value. */
    val onClickLabel: String? = null,
)

/**
 * A square node with a label and a monospace value. [valueStyle] is the value's size as [fitValue] chose it
 * for the room the node has.
 */
@Composable
fun StateNode(spec: NodeSpec, modifier: Modifier = Modifier, valueStyle: TextStyle = mono.bodySmall) {
    val palette = blueprint
    Column(
        modifier = modifier
            .border(
                width = palette.line,
                // Quiet, but still the 3:1 a control's edge needs on the page (UX decisions of 2026-10-08).
                color = if (spec.active) palette.text else palette.inputBorder,
                shape = RoundedCornerShape(Radius.node),
            )
            .background(palette.surface, RoundedCornerShape(Radius.node))
            .then(
                // docs/09 screen principle 1: only the node that is a choice takes a tap. The other two are
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
            // docs/09 "Spacing": the same 8dp as the vertical, which is what a 320dp phone has to
            // spare — a third of that row is nine characters of `UPLOADING` and little else.
            .padding(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // N2 (UX decisions of 2026-10-11): a label wider than the node ("Transkription" at a large font)
        // takes a second line, broken at a hyphen, rather than being cut to `Transkri…`.
        Text(
            spec.label,
            style = MaterialTheme.typography.labelSmall.copy(hyphens = Hyphens.Auto),
            color = palette.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (spec.busy) BlueprintLoader(spec.valueColor ?: palette.text)
            Text(
                monoWords(spec.value),
                style = valueStyle,
                color = spec.valueColor ?: palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The value's size for [width] px of node. RecKit's `minimumScaleFactor(0.7)` first, which is what a node
 * this narrow needs: a third of a 360dp row, less the loader, is not nine characters of `UPLOADING` at full
 * size, and a state truncated to `UPLOAD…` is the one thing docs/09 screen principle 1 keeps when it takes
 * everything else away — smaller, then, rather than shorter. A value too wide for one line even at that
 * floor (`Wird hochgeladen` on a 360dp phone) takes two, at the largest size two hold, broken between words
 * or at a hyphen (N2, UX decisions of 2026-10-11). Null when not even two lines hold it: the row then stacks
 * its nodes, as it does at the largest font sizes. Sized here rather than by `autoSize`, so the row can ask
 * each node its height and give all three the tallest.
 */
internal fun fitValue(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, width: Int): TextStyle? {
    val max = style.fontSize.value
    val sizes = generateSequence(max) { it - 1f }.takeWhile { it >= max * MIN_SCALE }.map { style.copy(fontSize = it.sp) }.toList()
    fun fits(candidate: TextStyle, lines: Int) = !measurer.measure(
        text, candidate, maxLines = lines, constraints = Constraints(maxWidth = width.coerceAtLeast(0)),
    ).hasVisualOverflow
    return sizes.firstOrNull { fits(it, 1) } ?: sizes.firstOrNull { fits(it, 2) }
}

/** How far a value may shrink to stay on one line — the Mac's own floor. */
private const val MIN_SCALE = 0.7f

/**
 * The three dashboard nodes, joined edge to edge by straight 20dp connectors. A node whose words took a
 * second line makes the row taller, and all three with it: they stay one height. Where a value would not
 * fit a third of the row even in two lines, the nodes stack, each the full width.
 */
@Composable
fun StateNodeRow(nodes: List<NodeSpec>, modifier: Modifier = Modifier) {
    val palette = blueprint
    val measurer = rememberTextMeasurer()
    val value = mono.bodySmall.copy(hyphens = Hyphens.Auto)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val narrow = density.fontScale > 1.3f || maxWidth < 300.dp
        // Each value's size in a node of [share]: its padding and border on each side, and the loader while it works, off.
        fun fit(share: Dp): List<TextStyle?> = nodes.map { spec ->
            val room = share - (Space.s + palette.line) * 2 - if (spec.busy) LOADER + Space.xs else 0.dp
            fitValue(measurer, monoWords(spec.value), value, with(density) { room.roundToPx() })
        }
        val (stacked, styles) = remember(nodes.map { it.value to it.busy }, maxWidth, value, density, narrow) {
            val row = if (narrow) null else fit((maxWidth - CONNECTOR * (nodes.size - 1)) / nodes.size)
            if (row != null && row.none { it == null }) false to row else true to fit(maxWidth)
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                nodes.forEachIndexed { index, spec -> StateNode(spec, Modifier.fillMaxWidth(), styles[index] ?: value) }
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Max), verticalAlignment = Alignment.CenterVertically) {
                nodes.forEachIndexed { index, spec ->
                    StateNode(spec, Modifier.weight(1f).fillMaxHeight(), styles[index] ?: value)
                    if (index != nodes.lastIndex) Box(Modifier.width(CONNECTOR).height(palette.line)
                        .background(palette.text).clearAndSetSemantics {})
                }
            }
        }
    }
}

/** The straight line between two nodes. */
private val CONNECTOR = 20.dp

/** docs/09 "Typography": the timer is the one piece of data big enough to be the screen. */
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
