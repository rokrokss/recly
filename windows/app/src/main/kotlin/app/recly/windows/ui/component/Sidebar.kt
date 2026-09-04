package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint

/**
 * docs/09 화면 원칙 4: the width of the list down the left of a master-detail window. Both of this
 * app's windows are that shape, and a list that was a different width in each would read as two
 * different apps.
 */
val SidebarWidth: Dp = 300.dp

/** The detail half with nothing picked: one quiet line, centred, and no chrome around it. */
@Composable
fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = blueprint.textMuted)
    }
}

/**
 * One row of that list: a tint and an accent [title] while it is the row on show, whatever [body]
 * has to add underneath, and the controls that are *not* opening it in a row of their own below.
 *
 * [controls] is outside the row's own click target on purpose — deleting a recording or marking a
 * workflow is not one of the things opening it should be able to do by accident. The tint and the
 * accent are colour only, and colour is not announced, so [selected] is also on the click node.
 */
@Composable
fun SidebarRow(
    title: String,
    selected: Boolean,
    onOpen: () -> Unit,
    controls: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val palette = blueprint
    Column(Modifier.fillMaxWidth().background(if (selected) palette.background else palette.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { this.selected = selected }
                .padding(horizontal = Space.m, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) palette.accent else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            body()
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = Space.m, end = Space.m, bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
            content = controls,
        )
        HairLine()
    }
}
