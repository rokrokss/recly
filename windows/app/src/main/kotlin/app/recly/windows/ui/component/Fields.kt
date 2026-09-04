package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/**
 * docs/09 "형태" · "접근성": a field, as a label over a bordered box. Material's `OutlinedTextField`
 * is the component this replaces, for two reasons and not one:
 *
 * 1. Its label *floats* — an animation of a size and a position, which is the decorative motion
 *    docs/09 "모션" bans. A label written above the box never moves.
 * 2. Its unfocused border is a fixed 1dp of `outline`, so high contrast — which is 2dp everywhere
 *    else in this design — left every field a hairline thinner than the rest of the window.
 *
 * The focused state is the accent border, which is the same cue the chips and the graph nodes use.
 *
 * What Material's component also carried, and what a label written above the box does not, is the
 * tie between the two — see [fieldSemantics], which is why the editable node names itself.
 */
@Composable
fun BlueprintTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    /** A second line under the box: what the field means, or what an empty one falls back to. */
    hint: String? = null,
    singleLine: Boolean = true,
    /** Monospace by default: nearly every field in this app holds data rather than prose. */
    monospace: Boolean = true,
    minHeight: Dp = MinTouch,
) {
    val palette = blueprint
    val style: TextStyle = if (monospace) {
        mono.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val spoken = fieldSemantics(label, hint)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                // Merged into the node `BasicTextField` puts its own editable semantics on, so this
                // names the field without taking anything off it.
                .semantics {
                    contentDescription = spoken.description
                    spoken.state?.let { stateDescription = it }
                }
                .defaultMinSize(minHeight = minHeight)
                .border(palette.line, palette.grid, RoundedCornerShape(Radius.node))
                .background(palette.surface, RoundedCornerShape(Radius.node))
                .padding(horizontal = Space.s, vertical = 10.dp),
            singleLine = singleLine,
            textStyle = style.copy(color = palette.text),
            cursorBrush = SolidColor(palette.accent),
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        }
    }
}

/** What the editable node of a [BlueprintTextField] says about itself. */
internal data class FieldSemantics(val description: String, val state: String?)

/**
 * docs/09 "접근성": a field names itself, because the label above it cannot name it for it.
 *
 * The label is a `Text` node of its own, and Compose Desktop has no `labelledBy` to tie the two
 * together — Material's `OutlinedTextField` carried the label inside its own node and this design
 * writes it above the box, so a screen reader landing on the field found an edit box with nothing to
 * call it. The label is therefore the editable node's own `contentDescription`, and the hint under
 * the box — what the field means, or what an empty one falls back to — its `stateDescription`, which
 * is what a reader announces after the value rather than in place of it.
 *
 * `label` is a required parameter of [BlueprintTextField], so every field in the app has one: the
 * eleven that were migrated off Material, and any written after them.
 */
internal fun fieldSemantics(label: String, hint: String?): FieldSemantics =
    FieldSemantics(description = label, state = hint?.takeIf { it.isNotBlank() })

/**
 * docs/09 "형태" · "모션": a menu, as a square bordered card on the grid. Material's `DropdownMenu`
 * scales and fades itself in — decorative motion docs/09 bans — and its surface carries an
 * elevation tint this palette has no room for, so this is a plain [Popup] with the same border and
 * radius as every other node.
 *
 * [offset] is where the menu opens: a menu is an answer to a click, so it belongs at the thing that
 * was clicked rather than at the origin of whatever composable happens to hold it.
 */
@Composable
fun BlueprintMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: IntOffset = IntOffset.Zero,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Popup(
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = MENU_MIN_WIDTH)
                .border(palette.line, palette.grid, shape)
                .background(palette.surface, shape)
                .padding(vertical = Space.xs)
                // A list that outgrows the window (languages, workflows) scrolls instead of
                // clipping its tail into rows that exist but cannot be picked. Focus traversal
                // brings an off-screen item into view on its own, so keyboard users lose nothing.
                .heightIn(max = MENU_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/** One line of a [BlueprintMenu]. [MinTouch] tall, whatever the label does. */
@Composable
fun BlueprintMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = androidx.compose.ui.Alignment.CenterStart,
    ) {
        BlueprintButton(
            label = label,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = 2.dp),
            tone = ButtonTone.QUIET,
            enabled = enabled,
        )
    }
}

/**
 * docs/09 "형태": one of a set of choices that is expected to grow — the language, and whatever
 * enum setting comes after it. A row of chips says every option out loud, which is right for two or
 * three and wrong for ten; this says the chosen one and keeps the rest in a [BlueprintMenu] one
 * click away, so the setting stays one line however long the list gets.
 *
 * Material's `ExposedDropdownMenuBox` is what this replaces, for the two reasons
 * [BlueprintTextField] is not an `OutlinedTextField`: a border of a fixed 1dp that high contrast
 * cannot thicken, and a menu that animates itself for decoration.
 */
@Composable
fun <T> BlueprintDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Monospace for a value that is data — a provider id (docs/09 "타이포"), not a word. */
    monospace: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box(modifier) {
        BlueprintButton(
            label = "$current $DROPDOWN_MARK",
            onClick = { expanded = true },
            // docs/09 "접근성": the button says the value, and the row above it says what the value
            // is of — a reader given only the value would hear "한국어, button" and no question.
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = current
            },
            tone = ButtonTone.QUIET,
            monospace = monospace,
        )
        BlueprintMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                BlueprintMenuItem(
                    // docs/09 "모든 상태는 색 + 텍스트": which one is chosen is a mark in the label,
                    // the same one a chip wears, and not a colour a monochrome reader loses.
                    label = if (value == selected) "$SELECTION_MARK $text" else text,
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/**
 * The glyph on the closed dropdown. A character rather than an icon, so it sits in the label's own
 * line of text and grows with it (docs/09 "유동 타이포"), like [SELECTION_MARK].
 */
const val DROPDOWN_MARK: String = "▾"

private val MENU_MIN_WIDTH: Dp = 200.dp

/** About nine rows — enough to see a long list is a list before scrolling it. */
private val MENU_MAX_HEIGHT: Dp = 320.dp
