package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/**
 * The one line at the top of every screen: what this is, and — in monospace, on the right — the
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
            .padding(start = Space.m, end = Space.m, top = Space.m, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = blueprint.text,
            maxLines = 1,
            // A workflow the user named, or a recording's title: the header is the one place a
            // string this app did not write ends up, so it ends rather than being cut mid-glyph.
            overflow = TextOverflow.Ellipsis,
        )
        meta?.let {
            Text(it, style = mono.small, color = blueprint.textMuted, maxLines = 1)
        }
        trailing?.invoke()
    }
}

/**
 * docs/09 화면 원칙 4: a settings screen is a table, and a table has section headings. Only the
 * vertical rhythm is baked in — the caller owns the horizontal inset, because an inspector already
 * has one and a full-bleed table does not.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = Space.m, bottom = Space.s),
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
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = blueprint
    Column(modifier.fillMaxWidth().background(palette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.m, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = palette.text)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                }
            }
            trailing?.invoke()
        }
        HairLine()
    }
}

/**
 * A [TableRow] that *is* its switch: `toggleable` sits on the whole row, so what TalkBack focuses
 * is one merged node with a name — "<title>, switch, on" — and not an unnamed track next to a
 * title it cannot see (docs/09 "접근성"). The row is taller than [MinTouch], so the target only grows.
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
        modifier = modifier.toggleable(
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
 * docs/09 "형태": no rounded pills, so the switch is a square track with a square thumb. The track
 * is 40x22, drawn inside a [MinTouch] box so that whoever makes it toggleable — [SwitchRow], or the
 * editor's own inspector row — never ends up with a target smaller than the rule.
 */
@Composable
fun SwitchTrack(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val palette = blueprint
    val ink = when {
        !enabled -> palette.grid
        checked -> palette.accent
        else -> palette.textMuted
    }
    Box(modifier = modifier.size(MinTouch), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                // docs/09 "선": the same token as every other border, rather than a 1.5 of its own.
                .border(palette.line, ink, RoundedCornerShape(Radius.badge))
                .padding(2.dp),
            horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).background(ink, RoundedCornerShape(Radius.badge)))
        }
    }
}
