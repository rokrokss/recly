package app.recly.android.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/** docs/09 "Typography": the timer is the one piece of data big enough to be the screen. */
@Composable
fun MonoTimer(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        modifier = modifier,
        style = mono.timer,
        color = color ?: blueprint.text,
        maxLines = 1,
    )
}
