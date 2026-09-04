package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/**
 * docs/09 "형태" · 화면 원칙 5: the app's two menus — the recording screen's workflow picker and the
 * editor's add-a-step list — as a square card on the grid border.
 *
 * `DropdownMenu` is what this replaces. It is a Material `Surface` with a tonal elevation and a
 * shadow under it, and it scales and fades in on every open; docs/09 has no shadows over content
 * and no decorative motion, and a reduce-motion user gets that animation anyway because it is the
 * component's own and not the app's. A `Popup` has neither, so there is nothing to switch off.
 */
@Composable
fun BlueprintMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Popup(
        popupPositionProvider = remember { UnderAnchor() },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                // The rows fill the widest row rather than the whole window, which is what a
                // `fillMaxWidth` row inside a popup would otherwise do.
                .width(IntrinsicSize.Max)
                .border(palette.line, palette.grid, shape)
                .background(palette.surface, shape),
        ) {
            Column(
                modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/**
 * One row of a [BlueprintMenu] — square, [MinTouch] tall, and separated by the one rule we have.
 *
 * [selected] is what kind of row this is, in three states. `null` — the default — is a command, and
 * a menu of those (the editor's add-a-step list) is a plain list with no gutter at all. `true` and
 * `false` are the two halves of a choice: the chosen one wears the check the watch's workflow rows
 * already wear, and the others keep the same width of gutter empty so every name starts on one
 * column. The check is drawn transparent rather than dropped, which is what makes the two widths
 * the same glyph's width at any font scale.
 */
@Composable
fun BlueprintMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    selected: Boolean? = null,
    enabled: Boolean = true,
) {
    val palette = blueprint
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MinTouch)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                // docs/09 "접근성": a row of a choice says it is one, so the state is spoken rather
                // than left to a glyph that the marker below keeps out of the tree anyway.
                .then(selected?.let { Modifier.semantics { this.selected = it } } ?: Modifier)
                .padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Text(
                    SELECTED_MARK,
                    // The mark is decoration in both states — the row itself carries the selection.
                    modifier = Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) palette.accent else Color.Transparent,
                    maxLines = 1,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) palette.text else palette.textMuted,
                maxLines = 1,
            )
        }
        if (divider) HairLine()
    }
}

/** The check a chosen row wears. Punctuation rather than a word, so it is not a translated string. */
private const val SELECTED_MARK = "✓"

/** A menu with more rows than this scrolls rather than growing past the window. */
private val MENU_MAX_HEIGHT: Dp = 320.dp

/** [menuOffset], as the platform wants it. */
private class UnderAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = menuOffset(anchorBounds, windowSize, popupContentSize)
}

/**
 * Where a menu lands: under the thing that opened it, left edges lined up, and flipped above it when
 * the space below is not tall enough. Both axes are clamped to the window, because a menu half off
 * the screen is a menu with entries nobody can reach.
 */
internal fun menuOffset(anchor: IntRect, window: IntSize, menu: IntSize): IntOffset {
    val x = anchor.left.coerceIn(0, (window.width - menu.width).coerceAtLeast(0))
    val y = if (anchor.bottom + menu.height <= window.height) {
        anchor.bottom
    } else {
        (anchor.top - menu.height).coerceAtLeast(0)
    }
    return IntOffset(x, y)
}
