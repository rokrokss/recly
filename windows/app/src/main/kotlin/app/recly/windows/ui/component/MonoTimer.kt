package app.recly.windows.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/**
 * docs/09 "Typography": the timer is the one piece of data big enough to be a screen of its own — on the phones.
 * The tray's popup draws it at the title size beside its record node ([style], 2026-11-04).
 */
@Composable
fun MonoTimer(text: String, modifier: Modifier = Modifier, color: Color? = null, style: TextStyle = mono.timer) {
    Text(
        text = text,
        // docs/09 "Accessibility": it changes every second while a recording runs, and a reader that is not
        // told so hears the length the recording had when the window opened, for ever.
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = style,
        color = color ?: blueprint.text,
        maxLines = 1,
    )
}
