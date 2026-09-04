package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.recly.android.ui.theme.BlueprintColors
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint

/**
 * docs/09 화면 원칙 5 ("제목 + 설명 + 최대 2개 버튼"), drawn the way the rest of the app is drawn: a
 * square-cornered node on the grid, not Material's tonal card. `AlertDialog` is a container with
 * its own shape, its own elevation tint and its own 28dp corners, and none of those are in docs/09
 * — so this is `Dialog` plus the same surface, 1dp border and 4dp radius as [StateNode].
 *
 * There is no motion to reduce: a `Dialog` window is not animated, so the system's own reduce-motion
 * setting has nothing to switch off here. Dark arrives through the palette.
 *
 * [content] scrolls on its own so a long body (the disconnect warnings) never pushes [actions] off
 * the screen; the card is capped at [dialogMaxHeight] of the window. docs/09 §유동 타이포 makes the
 * font size the user's, so the two things that grow with it are both bounded: the title keeps
 * [TITLE_LINES] in the header and spills the rest into that same scroll region, and [actions] stack
 * when a row of them would no longer fit (see [DialogActions]).
 */
@Composable
fun BlueprintDialog(
    title: String,
    onDismissRequest: () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    val maxHeight = dialogMaxHeight(LocalConfiguration.current.screenHeightDp)
    // What did not fit in the header. Clipping the rest away is not on offer — a question with its
    // end cut off, or ellipsed, is not a question — so it is re-shown at the top of the body, which
    // scrolls. Empty for every title that fits, which is all of them at the default font size.
    var spill by remember(title) { mutableStateOf("") }
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .border(palette.line, palette.grid, shape)
                .background(palette.surface, shape)
                .padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = palette.text,
                maxLines = TITLE_LINES,
                overflow = TextOverflow.Clip,
                onTextLayout = { layout ->
                    spill = if (layout.hasVisualOverflow) {
                        title.substring(layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)).trimStart()
                    } else {
                        ""
                    }
                },
            )
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (spill.isNotEmpty()) {
                    Text(spill, style = MaterialTheme.typography.titleLarge, color = palette.text)
                }
                content()
            }
            DialogActions(actions)
        }
    }
}

/**
 * The answers, in a row while they fit and in a stack when they do not. What decides it is nearly
 * always the user's font size and not the width of the device (docs/09 §유동 타이포), and a clipped
 * answer makes the question unanswerable — so the row is measured rather than assumed.
 *
 * Stacked, each answer is full width and they keep their order, which puts the primary one at the
 * bottom: last, the same as it is last on the right, and nearest the thumb.
 */
@Composable
private fun DialogActions(actions: @Composable () -> Unit) {
    val spacing = with(LocalDensity.current) { Space.s.roundToPx() }
    Layout(content = actions, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        // maxIntrinsicWidth and not a trial measure: a measurable may only be measured once, and
        // this has to know the answer before it can choose the constraints to measure with.
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val stacked = stackActions(width, natural, spacing)
        val placeables = measurables.map { measurable ->
            measurable.measure(
                if (stacked) Constraints.fixedWidth(width) else Constraints(maxWidth = width),
            )
        }
        val gaps = spacing * (placeables.size - 1).coerceAtLeast(0)
        val height = if (stacked) {
            placeables.sumOf { it.height } + gaps
        } else {
            placeables.maxOfOrNull { it.height } ?: 0
        }
        layout(width, height) {
            if (stacked) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacing
                }
            } else {
                // Aligned to the end, as [Arrangement.End] had them.
                var x = width - placeables.sumOf { it.width } - gaps
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + spacing
                }
            }
        }
    }
}

/**
 * Whether the answers have to stack: they do the moment the row they would make is wider than the
 * card, gaps included.
 */
internal fun stackActions(available: Int, widths: List<Int>, spacing: Int): Boolean =
    widths.sum() + spacing * (widths.size - 1).coerceAtLeast(0) > available

/** How much of a long question stays pinned above the scroll region. */
private const val TITLE_LINES = 3

/**
 * How tall the card may get before its body scrolls: most of the window, never all of it, so the
 * dialog still reads as something sitting *on* the screen rather than as another screen.
 */
fun dialogMaxHeight(screenHeightDp: Int): Dp = (screenHeightDp * HEIGHT_FRACTION).dp

private const val HEIGHT_FRACTION = 0.8f

/** docs/09: the body is 14–16, and what it means decides the colour. */
enum class DialogTone { BODY, MUTED, DANGER }

/** One line of a dialog body. */
@Composable
fun BlueprintDialogText(text: String, modifier: Modifier = Modifier, tone: DialogTone = DialogTone.BODY) {
    val palette = blueprint
    Text(
        text,
        modifier = modifier,
        style = if (tone == DialogTone.BODY) {
            MaterialTheme.typography.bodyLarge
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = when (tone) {
            DialogTone.BODY -> palette.text
            DialogTone.MUTED -> palette.textMuted
            DialogTone.DANGER -> palette.danger
        },
    )
}

/**
 * One of several answers, as one accessibility node: the row is `selectable`, so what a screen
 * reader focuses is "<label>, radio button, selected" and not an unnamed mark beside a label
 * (docs/09 "접근성"). The row is [MinTouch] tall whatever the label does.
 */
@Composable
fun BlueprintRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionRow(
        label = label,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        mark = { SelectionMark(selected = selected, filled = false) },
    )
}

/** The same row as [BlueprintRadioRow] for an answer that is on or off rather than one of a set. */
@Composable
fun BlueprintCheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionRow(
        label = label,
        modifier = modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        mark = { SelectionMark(selected = checked, filled = true) },
    )
}

@Composable
private fun OptionRow(label: String, modifier: Modifier, mark: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = MinTouch),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mark()
        Text(label, style = MaterialTheme.typography.bodyMedium, color = blueprint.text)
    }
}

/**
 * docs/09 "형태": no circles and no pills, so both marks are squares on the badge radius. A radio
 * holds a smaller square inside its outline; a checkbox fills, because "on" is a state and "this
 * one of the two" is a position.
 *
 * The outline is [app.recly.android.ui.theme.BlueprintColors.line] — the same token the hairline
 * and every bordered node take. An unchecked box is nothing *but* its outline.
 */
@Composable
private fun SelectionMark(selected: Boolean, filled: Boolean) {
    val palette = blueprint
    val ink = selectionInk(palette, selected)
    val shape = RoundedCornerShape(Radius.badge)
    Box(
        modifier = Modifier
            .size(MARK)
            .border(palette.line, ink, shape)
            .background(if (selected && filled) ink else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && !filled) Box(Modifier.size(INNER).background(ink, RoundedCornerShape(1.dp)))
        if (selected && filled) Box(Modifier.size(INNER).background(palette.onAccent, RoundedCornerShape(1.dp)))
    }
}

private val MARK: Dp = 18.dp
private val INNER: Dp = 8.dp

/** docs/09: a chosen option is the accent; an unchosen one takes the quiet border colour. */
fun selectionInk(palette: BlueprintColors, selected: Boolean): Color =
    if (selected) palette.accent else palette.textMuted

/**
 * A link inside a dialog — the Drive permissions page, the consent guidance. Accent and underlined,
 * because a link that is only a colour is invisible to a colour-blind reader (docs/09 "모든 상태는
 * 색 + 텍스트"), and [MinTouch] tall because it is something you tap.
 */
@Composable
fun BlueprintDialogLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = MinTouch)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = blueprint.accent,
            textDecoration = TextDecoration.Underline,
        )
    }
}
