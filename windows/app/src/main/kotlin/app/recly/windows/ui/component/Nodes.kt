package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** One node of the dashboard: a label and the value under it (docs/09 screen principle 1). */
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
            // docs/09 "Accessibility": a label and its value are one fact, so a screen reader hears
            // "Workflow, Meeting" rather than two unconnected runs of text.
            .semantics(mergeDescendants = true) {}
            .border(
                width = palette.line,
                // Quiet, but 3:1 against the page in light and dark: the input border, not the grid.
                color = if (spec.active) palette.text else palette.inputBorder,
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
            if (spec.busy) BlueprintLoader(spec.valueColor ?: palette.text)
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

/** docs/09 "Typography": the timer is the one piece of data big enough to be a screen of its own. */
@Composable
fun MonoTimer(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        // docs/09 "Accessibility": it changes every second while a recording runs, and a reader that is not
        // told so hears the length the recording had when the window opened, for ever.
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = mono.timer,
        color = color ?: blueprint.text,
        maxLines = 1,
    )
}
