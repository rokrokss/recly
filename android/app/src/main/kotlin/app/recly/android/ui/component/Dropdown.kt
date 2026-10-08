package app.recly.android.ui.component

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import androidx.compose.ui.text.AnnotatedString

/**
 * docs/09 "Shape": one of a set of choices that is expected to grow — the app language, the provider,
 * the spoken language. A row of chips says every option out loud, which is right for two or three
 * and wrong for twenty; this says the chosen one and keeps the rest one tap away, so the setting
 * stays one row however long the list gets. The PC's and the Apple shells' `BlueprintDropdown` are
 * the same control.
 *
 * Closed, it is [BlueprintButton]'s quiet box with the value and [DROPDOWN_MARK] — the mark is what
 * says "this is a choice", where a bare value at the end of a row read as a label. It is drawn here
 * rather than reused, for the one element below.
 *
 * Material's `ExposedDropdownMenuBox` is not used for the reasons [BlueprintDialog] is not an
 * `AlertDialog`: a border of a fixed 1dp that high contrast cannot thicken, and a menu that scales and
 * fades itself in — decorative motion docs/09 "Motion" bans.
 *
 * @param label what the value is of — the row's own name, which is what a screen reader says first.
 * @param title the words for a value. The selected value is shown through it too, so a value the
 * current list no longer offers (a language the method does not support) still reads as itself.
 */
@Composable
fun <T> BlueprintDropdown(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (T) -> String,
) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = title(selected)
    Box(modifier) {
        Row(
            modifier = Modifier
                // docs/09 "Accessibility": the label is small, the target is not — in both directions.
                .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
                .border(palette.line, palette.inputBorder, shape)
                .clickable(role = Role.Button) { expanded = true }
                // One element, deliberately (the iOS dropdown's own rule): the value and the mark are
                // two texts, and a reader walking them would say the value and then "down-pointing
                // triangle". Clearing *after* the click keeps its role and action, and this one node
                // says what the value is of and then the value — a reader given only the value would
                // hear "한국어" and no question.
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = current
                }
                .padding(horizontal = Space.s, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A value is read whole: at a narrow width or a large font it wraps, as a button's label
            // does, rather than losing its end ("Deutsch (Deutsc…") — and never pushes the mark out.
            Text(current, style = MaterialTheme.typography.labelLarge, color = palette.textMuted, maxLines = 3,
                modifier = Modifier.weight(1f, fill = false))
            Text(DROPDOWN_MARK, style = MaterialTheme.typography.labelLarge, color = palette.textMuted)
        }
        if (expanded) {
            BlueprintMenu(onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    MenuOption(
                        label = title(option),
                        selected = option == selected,
                        onSelect = {
                            expanded = false
                            // Choosing what is already chosen changes nothing, and must not look as if
                            // it had — a draft would otherwise grow a Save for no change.
                            if (option != selected) onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * docs/09 "Shape" · "Motion": the open list, as a square bordered card on the grid — a plain [Popup] with
 * the same edge and radius as every other node, and no entrance animation. It opens under the box and
 * end-aligned with it (the box sits at the end of its row), or above it when there is no room below;
 * a list taller than [MENU_MAX_HEIGHT] scrolls instead of running off the screen.
 */
@Composable
fun BlueprintMenu(onDismissRequest: () -> Unit, maxHeight: Dp = MENU_MAX_HEIGHT, content: @Composable () -> Unit) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    val density = LocalDensity.current
    val gap = with(density) { Space.xs.roundToPx() }
    // The screen's own side gutter, so the list never touches the glass.
    val margin = with(density) { Space.m.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap, margin) { MenuPosition(gap, margin) },
        onDismissRequest = onDismissRequest,
        // Focusable: Back and a tap outside close it, and a screen reader moves into it.
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                // As wide as its longest line: the rows fill the list, and a list with no width
                // of its own would take the whole window for them to fill.
                .width(IntrinsicSize.Max)
                .widthIn(min = MENU_MIN_WIDTH, max = LocalConfiguration.current.screenWidthDp.dp - Space.m * 2)
                .border(palette.line, palette.grid, shape)
                .background(palette.surface, shape)
                .padding(vertical = Space.xs)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/**
 * One line of the open list, [MinTouch] tall whatever the label does. `selectable`, so a reader
 * hears "<label>, radio button, selected" — the same fact the chips put in their semantics.
 */
@Composable
fun MenuOption(label: String, selected: Boolean, onSelect: () -> Unit, monospace: Boolean = false) {
    val palette = blueprint
    val ink = if (selected) palette.accent else palette.text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = Space.m, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // docs/09 "Every state is color + text": the chosen one is a mark as well as the accent, not a
        // colour a monochrome reader loses. Every line draws the mark, unseen where it is not chosen,
        // so the labels keep one left edge at any font size; the semantics already say which one.
        Text(
            SELECTION_MARK,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) ink else Color.Transparent,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(label, style = if (monospace) mono.bodySmall else MaterialTheme.typography.bodyMedium, color = ink)
    }
}

/**
 * One action of a [BlueprintMenu]: its words and, for one that cannot run now, why — the reason is the
 * item's second line, so a disabled item is never a riddle (the detail's More menu).
 */
@Composable
fun MenuAction(label: String, onClick: () -> Unit, enabled: Boolean = true, reason: String? = null, modifier: Modifier = Modifier) =
    MenuAction(AnnotatedString(label), onClick, enabled, reason, modifier)

/** The same, for a label with data in it — a time in monospace. */
@Composable
fun MenuAction(label: AnnotatedString, onClick: () -> Unit, enabled: Boolean = true, reason: String? = null, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.xs),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) palette.text else palette.textMuted)
        reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
    }
}

/**
 * Under the anchor and end-aligned with it; above it when the list does not fit below. Clamped to
 * the window either way, so a wide list on a narrow phone is moved in rather than cut off.
 */
internal class MenuPosition(private val gap: Int, private val margin: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val end = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = end.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

/**
 * The glyph on the closed dropdown. A character rather than an icon, so it sits in the value's own
 * line of text and grows with it (docs/09 "Fluid typography"), as it does on the PC and the Apple shells.
 */
const val DROPDOWN_MARK: String = "▾"

private val MENU_MIN_WIDTH: Dp = 200.dp

/** About six lines — enough to see a long list is a list before scrolling it. */
private val MENU_MAX_HEIGHT: Dp = 320.dp
