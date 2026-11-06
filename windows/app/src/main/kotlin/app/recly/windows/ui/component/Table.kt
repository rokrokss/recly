package app.recly.windows.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/**
 * The one line at the top of every window: what this is, and — in monospace, on the right — the
 * machine's side of it (the device, the counts, the revision). No app bar, no elevation, no glass:
 * docs/09 keeps glass for chrome the platform owns.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.m, end = Space.m, top = Space.m, bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            // docs/09 "Disabled, focus and structure": a screen's title is a heading a reader can jump to.
            Text(title, style = MaterialTheme.typography.titleMedium, color = blueprint.text,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            meta?.let { SelectionContainer { Text(it, style = mono.small, color = blueprint.textMuted) } }
        }
        trailing?.invoke()
    }
}

/**
 * docs/09 screen principle 4: a settings window is a table, and a table has section headings. Only the
 * vertical rhythm is baked in — the caller owns the horizontal inset, because an inspector already
 * has one and a full-bleed table does not.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        // docs/09 "Component measurements": 8 below. A heading, as the screen's title is.
        modifier = modifier.semantics { heading() }.padding(top = Space.m, bottom = Space.s),
        style = MaterialTheme.typography.labelSmall,
        color = blueprint.textMuted,
    )
}

/**
 * One row of a section table: a title, an optional second line, and whatever the row is for on the
 * right. Square corners — docs/09 gives a table row a radius of zero.
 */
@Composable
fun TableRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * A state that needs the user, such as an ended sign-in, is said in the danger colour as well as
     * in words (docs/09 "Every state is color + text"); null is the quiet secondary colour.
     */
    subtitleColor: Color? = null,
    /** Under the second line, in the same column: a link that belongs to the row ([TextLink]). */
    below: (@Composable () -> Unit)? = null,
    /**
     * The whole of what a shortened [title] stands for — a folder's path behind its name (2026-10-11, N8): never
     * drawn in the row, only in the title's tooltip and as its value to a screen reader.
     */
    titleDetail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = blueprint
    Column(modifier.fillMaxWidth().background(palette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // docs/09 "Component measurements": 12 top and bottom, 16 at the sides.
                .padding(horizontal = Space.m, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                if (titleDetail == null) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = palette.text)
                } else {
                    DetailTooltip(titleDetail) {
                        Text(
                            title,
                            modifier = Modifier.semantics { stateDescription = titleDetail },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.text,
                        )
                    }
                }
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor ?: palette.textMuted)
                }
                below?.invoke()
            }
            trailing?.invoke()
        }
        HairLine()
    }
}

/** [detail] on hover over [content], in the menus' card: data, so monospace and left to right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailTooltip(detail: String, content: @Composable () -> Unit) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    TooltipArea(
        tooltip = {
            Text(
                detail,
                modifier = Modifier
                    .border(palette.line, palette.inputBorder, shape)
                    .background(palette.surface, shape)
                    .padding(horizontal = Space.s, vertical = Space.xs),
                style = mono.small.copy(textDirection = TextDirection.Ltr),
                color = palette.text,
            )
        },
        content = content,
    )
}

/** Supporting copy below a settings row uses the table's quiet type and inset (docs/09). */
@Composable
fun SectionFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
        style = MaterialTheme.typography.bodySmall,
        color = blueprint.textMuted,
    )
}

/**
 * A [TableRow] that *is* its switch: `toggleable` sits on the whole row, so what a screen reader
 * focuses is one merged node with a name — "<title>, switch, on" — and not an unnamed track next to
 * a title it cannot see (docs/09 "Accessibility"). The row is taller than [MinTouch], so the target only
 * grows.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    TableRow(
        title = title,
        modifier = modifier.focusRing().pressCursor(enabled).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        subtitle = subtitle,
        trailing = { SwitchTrack(checked = checked, enabled = enabled) },
    )
}

/**
 * docs/09 "Shape": no rounded pills, so the switch is a square track with a square thumb. The track
 * is 40x22, drawn inside a [MinTouch] box so that whoever makes it toggleable — [SwitchRow], or the
 * editor's own inspector row — never ends up with a target smaller than the rule. Disabled, the track
 * is dashed in the disabled ink (docs/09 "Component measurements"), never the grid, which is decoration.
 */
@Composable
fun SwitchTrack(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val palette = blueprint
    val ink = when {
        !enabled -> palette.disabled
        checked -> palette.accent
        else -> palette.textMuted
    }
    Box(modifier = modifier.size(MinTouch), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .width(40.dp)
                .height(22.dp)
                // docs/09 "Lines": the hairline token, so high contrast thickens this the way it
                // thickens every other border rather than leaving the switch at a fixed 1.5dp.
                .then(
                    if (enabled) {
                        Modifier.border(palette.line, ink, RoundedCornerShape(Radius.badge))
                    } else {
                        Modifier.dashedBorder(palette.line, ink, Radius.badge)
                    },
                )
                .padding(Space.xxs),
            horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).background(ink, RoundedCornerShape(Radius.badge)))
        }
    }
}
