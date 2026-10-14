package app.recly.android.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint

/**
 * docs/09 "Component measurements": a list with nothing in it — what is missing in the body colour at 16, and what
 * to do about it, or why, in the secondary colour at 14, centred in the room the list would have taken. No button:
 * the way on is already on the screen (docs/09 screen principle 8).
 */
@Composable
fun EmptyList(title: String, hint: String, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(
        modifier = modifier.padding(Space.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.text, textAlign = TextAlign.Center)
        Text(hint, Modifier.padding(top = Space.xs), style = MaterialTheme.typography.bodyMedium, color = palette.textMuted, textAlign = TextAlign.Center)
    }
}
