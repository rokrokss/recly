package app.recly.windows.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/** docs/09 "Typography": the timer is the one piece of data big enough to be a screen of its own. */
@Composable
fun MonoTimer(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        // docs/09 "Accessibility": it changes every second while a recording runs, and a reader that is not
        // told so hears the length the recording had when the window opened, for ever.
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = mono.timer,
        color = color ?: blueprint.text,
        maxLines = 1,
    )
}
