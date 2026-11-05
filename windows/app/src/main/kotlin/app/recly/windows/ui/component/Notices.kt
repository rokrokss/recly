package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint

/**
 * docs/10 "Failures the user can fix, and their notices": one line of the banner — what it says over how many wait, and
 * the way to fix it at the end. The popup's lines and the speech model's alike.
 *
 * No badge (2026-11-05, user decision): `Failed` at a button's size read as a second button that did nothing, and it
 * only said again what the rows' own badges say.
 */
@Composable
fun BannerRow(action: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(blueprint.surface)
            .padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs), content = content)
        action()
    }
}

/**
 * docs/09 screen principle 8 · "Component measurements": an empty list says so in the middle of the space the list would
 * fill — a title (16), a quieter hint under it (14), and the one action a clear step below, all centred.
 */
@Composable
fun EmptyList(title: String, modifier: Modifier = Modifier, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.padding(Space.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = blueprint.text, textAlign = TextAlign.Center)
        hint?.let {
            Text(
                it,
                modifier = Modifier.padding(top = Space.xs),
                style = MaterialTheme.typography.bodyMedium,
                color = blueprint.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        action?.let { Box(Modifier.padding(top = Space.l)) { it() } }
    }
}
