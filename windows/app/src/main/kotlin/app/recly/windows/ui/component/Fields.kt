package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/**
 * docs/09 "Shape" · "Accessibility": a field, as a label over a bordered box. Material's `OutlinedTextField`
 * is the component this replaces, for two reasons and not one:
 *
 * 1. Its label *floats* — an animation of a size and a position, which is the decorative motion
 *    docs/09 "Motion" bans. A label written above the box never moves.
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
    /** How many lines a field that is not [singleLine] shows empty, and how many it grows to before it scrolls. */
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    /** Monospace by default: nearly every field in this app holds data rather than prose. */
    monospace: Boolean = true,
    minHeight: Dp = MinTouch,
    placeholder: String? = null,
    /** A key: drawn as dots, never as what was typed. */
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    FieldFrame(label, modifier, hint, enabled, monospace, minHeight, if (value.isEmpty()) placeholder else null, focusRequester) { box, style, decoration ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = box,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = style,
            cursorBrush = SolidColor(blueprint.accent),
            decorationBox = decoration,
        )
    }
}

/** The same field over a value that carries its selection — for a key that edits where the cursor is. */
@Composable
fun BlueprintTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    monospace: Boolean = true,
    minHeight: Dp = MinTouch,
    placeholder: String? = null,
    focusRequester: FocusRequester? = null,
) {
    FieldFrame(label, modifier, hint, enabled, monospace, minHeight, if (value.text.isEmpty()) placeholder else null, focusRequester) { box, style, decoration ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = box,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = style,
            cursorBrush = SolidColor(blueprint.accent),
            decorationBox = decoration,
        )
    }
}

/**
 * Everything a [BlueprintTextField] is but the editable text itself: the label over the box, the box and what it
 * says empty, the hint under it, and the node's name. [field] draws the text in the box modifier and style it is
 * handed, with the decoration it is handed.
 */
@Composable
private fun FieldFrame(
    label: String,
    modifier: Modifier,
    hint: String?,
    enabled: Boolean,
    monospace: Boolean,
    minHeight: Dp,
    /** What the box says while it is empty, or null. */
    placeholder: String?,
    focusRequester: FocusRequester?,
    field: @Composable (box: Modifier, style: TextStyle, decoration: @Composable (@Composable () -> Unit) -> Unit) -> Unit,
) {
    val palette = blueprint
    // docs/09 "Typography": data reads left to right in every language; what a person writes takes its direction
    // from its own first letters (2026-10-10).
    val style: TextStyle = if (monospace) {
        mono.bodySmall.copy(textDirection = TextDirection.Ltr)
    } else {
        MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    }
    val spoken = fieldSemantics(label, hint)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textMuted)
        field(
            Modifier
                .fillMaxWidth()
                // Merged into the node `BasicTextField` puts its own editable semantics on, so this
                // names the field without taking anything off it.
                .semantics {
                    contentDescription = spoken.description
                    spoken.state?.let { stateDescription = it }
                }
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .defaultMinSize(minHeight = minHeight)
                .fieldBox()
                .padding(horizontal = Space.s, vertical = 10.dp),
            style.copy(color = if (enabled) palette.text else palette.textMuted),
        ) { innerTextField ->
            Box {
                placeholder?.let { Text(it, style = style, color = palette.textMuted) }
                innerTextField()
            }
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        }
    }
}

/** What the editable node of a [BlueprintTextField] says about itself. */
internal data class FieldSemantics(val description: String, val state: String?)

/**
 * docs/09 "Accessibility": a field names itself, because the label above it cannot name it for it.
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
 * docs/09 "Shape": the box every field is drawn in — the search fields and the editors' as much as
 * [BlueprintTextField]'s (2026-10-10): the input border, and the accent a step heavier while the field has the focus,
 * which is the same cue the chips and the graph nodes use.
 */
fun Modifier.fieldBox(): Modifier = composed {
    val palette = blueprint
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(if (focused) palette.selectedLine else palette.line, if (focused) palette.accent else palette.inputBorder, RoundedCornerShape(Radius.node))
        .background(palette.surface, RoundedCornerShape(Radius.node))
}

/**
 * docs/09 "Shape" · "Motion": a menu, as a square bordered card on the grid. Material's `DropdownMenu`
 * scales and fades itself in — decorative motion docs/09 bans — and its surface carries an
 * elevation tint this palette has no room for, so this is a plain [Popup] with the same border and
 * radius as every other node.
 *
 * A menu is an answer to a click, so it opens at the thing that was clicked: under it, and above it when the
 * window has no room below ([MenuPosition], 2026-10-10).
 */
@Composable
fun BlueprintMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    /** Its end edge on the clicked thing's end edge — for a button at a window's end; otherwise the start edges meet. */
    end: Boolean = false,
    /** How tall it gets before it scrolls: a menu of actions that should be seen whole asks for more. */
    maxHeight: Dp = MENU_MAX_HEIGHT,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Popup(
        popupPositionProvider = remember(end) { MenuPosition(end) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = MENU_MIN_WIDTH)
                // The same edge as the dropdown box it opens from (docs/09 principle 4).
                .border(palette.line, palette.inputBorder, shape)
                .background(palette.surface, shape)
                .padding(vertical = Space.xs)
                // A list that outgrows the window (languages, workflows) scrolls instead of
                // clipping its tail into rows that exist but cannot be picked. Focus traversal
                // brings an off-screen item into view on its own, so keyboard users lose nothing.
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/**
 * Where a [BlueprintMenu] opens: under the thing clicked, and above it when the window has no room below for the
 * whole menu — and when neither side has, as far down as it fits. Its start edge is the thing's start edge, or its
 * end the thing's end ([end]); either way it stays inside the window.
 */
internal class MenuPosition(private val end: Boolean) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = end == (layoutDirection == LayoutDirection.Ltr)
        val x = if (right) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val y = when {
            anchorBounds.bottom + popupContentSize.height <= windowSize.height -> anchorBounds.bottom
            anchorBounds.top - popupContentSize.height >= 0 -> anchorBounds.top - popupContentSize.height
            else -> windowSize.height - popupContentSize.height
        }
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/** A menu as wide as its widest row, so the rows' backgrounds and targets line up. */
@Composable
fun BlueprintMenuColumn(content: @Composable () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = MENU_MIN_WIDTH)) { content() }
}

/**
 * One line of a [BlueprintMenu]: the label at the start, a quieter [secondary] line under it — what the row makes,
 * or why it cannot run now — and [trailing] at the end. [MinTouch] tall.
 *
 * [mark] is the column before the label (2026-10-10): null for a menu with none, `""` to keep it empty, or the
 * glyph in it — [SELECTION_MARK] on the one that is chosen, [BACK_MARK] on a list's heading. Every row of a menu
 * with a chosen one keeps the column, so the labels line up whichever row wears it.
 */
@Composable
fun BlueprintMenuRow(
    label: AnnotatedString,
    onClick: () -> Unit,
    enabled: Boolean = true,
    secondary: String? = null,
    mark: String? = null,
    /** Data — a speed — read left to right in every language. */
    ltr: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = blueprint
    val ink = if (enabled) palette.text else palette.textMuted
    val style = MaterialTheme.typography.labelLarge
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s + Space.xs, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mark?.let { Box(Modifier.width(MARK_COLUMN)) { Text(it, style = style, color = ink) } }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = if (ltr) style.copy(textDirection = TextDirection.Ltr) else style, color = ink)
            secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
        }
        trailing?.invoke()
    }
}

@Composable
fun BlueprintMenuRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    secondary: String? = null,
    mark: String? = null,
    ltr: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) = BlueprintMenuRow(AnnotatedString(label), onClick, enabled, secondary, mark, ltr, trailing)

/** The hairline between two groups of a menu's rows (docs/09 "Lines"). */
@Composable
fun BlueprintMenuDivider() {
    HairLine(Modifier.padding(vertical = Space.xs))
}

/**
 * docs/09 "Shape": one of a set of choices that is expected to grow — the language, and whatever
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
    /** Monospace for a value that is data — a provider id (docs/09 "Typography"), not a word. */
    monospace: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box(modifier) {
        BlueprintButton(
            label = "$current $DROPDOWN_MARK",
            onClick = { expanded = true },
            // docs/09 "Accessibility": the button says the value, and the row above it says what the value
            // is of — a reader given only the value would hear "한국어, button" and no question.
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = current
            },
            tone = ButtonTone.QUIET,
            monospace = monospace,
        )
        // A menu like the detail's (2026-10-10): flat rows under the box, its end on the box's end, as wide as its
        // longest choice.
        BlueprintMenu(expanded = expanded, onDismissRequest = { expanded = false }, end = true) {
            BlueprintMenuColumn {
                options.forEach { (value, text) ->
                    BlueprintMenuRow(
                        label = text,
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                        // docs/09 "Every state is color + text": which one is chosen is a mark, the same one a chip
                        // wears, and not a colour a monochrome reader loses.
                        mark = if (value == selected) SELECTION_MARK else "",
                    )
                }
            }
        }
    }
}

/**
 * The glyph on the closed dropdown. A character rather than an icon, so it sits in the label's own
 * line of text and grows with it (docs/09 "Fluid typography"), like [SELECTION_MARK].
 */
const val DROPDOWN_MARK: String = "▾"

private val MENU_MIN_WIDTH: Dp = 200.dp

/** About nine rows — enough to see a long list is a list before scrolling it. */
private val MENU_MAX_HEIGHT: Dp = 320.dp

/** The column a menu's [SELECTION_MARK] or [BACK_MARK] stands in: as wide as either at the largest type. */
private val MARK_COLUMN: Dp = 16.dp

/** On a list's heading row: back to the menu the list was opened from. */
const val BACK_MARK: String = "‹"
