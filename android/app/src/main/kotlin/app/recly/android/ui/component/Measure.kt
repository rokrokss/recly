package app.recly.android.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide a column of monospace data has to be for [samples] to fit — measured at the size the
 * user actually reads them, which is the fluid scale of docs/09 "유동 타이포" *and* whatever the
 * system font size adds on top of it.
 *
 * A fixed dp is what this replaces. `NEEDS_AUTH` in a 76dp column lost its last letters at a font
 * scale of 1.3, and no constant can be right for every language and every scale at once.
 */
@Composable
fun textColumnWidth(samples: List<String>, style: TextStyle, padding: Dp = 0.dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(samples, style, padding, density) {
        val widest = samples.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() } + padding
    }
}
