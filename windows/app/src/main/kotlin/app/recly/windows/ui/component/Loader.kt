package app.recly.windows.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint

/**
 * The one loader this design has: an 8dp square outline turning beside a value, for work that is
 * running with no percentage to show for it — the state node's `UPLOADING`, a model download, a
 * recording coming back from Drive.
 *
 * docs/09 "모션": motion is a state signal. Straight edges, no rounding and no fade — the square is
 * the same shape everything else on the screen is. It always turns: docs/09 says only the shells the
 * system tells follow reduce motion, and Windows tells a Compose Desktop app nothing about it. The
 * words beside it are the whole message either way.
 */
@Composable
fun BlueprintLoader(color: Color) {
    val turn = rememberInfiniteTransition()
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(LOADER_TURN_MS, easing = LinearEasing)),
    )
    Box(
        Modifier
            .size(LOADER)
            .graphicsLayer { rotationZ = angle }
            .border(width = blueprint.line, color = color)
            .clearAndSetSemantics {},
    )
}

/** A sentence about work in progress, with the loader beside it in the sentence's own colour. */
@Composable
fun LoadingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
        BlueprintLoader(color)
        Text(text, style = style, color = color)
    }
}

/** docs/09: 8dp, and one full turn slow enough to read as "still working" rather than "hurry". */
private val LOADER = 8.dp
private const val LOADER_TURN_MS = 1_200
