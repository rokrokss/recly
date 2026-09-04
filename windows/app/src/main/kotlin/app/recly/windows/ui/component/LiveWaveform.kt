package app.recly.windows.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.blueprint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * docs/09 화면 원칙 6: the strip that runs under the timer while a recording is going — one bar per
 * tenth of a second of the track being written, newest at the right edge, so the whole thing walks
 * leftwards and a microphone that has stopped hearing anything is visible as a flat end. The Mac's
 * `LiveWaveformView` is the same drawing.
 *
 * It draws what [peaks] answers with rather than anything of its own, and it asks ten times a
 * second, which is the rate the helper finishes a window at. A `Canvas` and not a row of boxes:
 * three hundred bars are three hundred layouts, ten times a second.
 *
 * docs/09 "모션": nothing here is animated. Each tick is a fresh drawing of a new reading — a state
 * change, not a transition — so reduce motion has nothing to turn off.
 */
@Composable
fun LiveWaveform(peaks: () -> List<Float>, modifier: Modifier = Modifier) {
    val ink = blueprint.danger
    // The levels are the recorder's and change on the helper's reader without Compose noticing; a
    // state of three hundred floats at 10 Hz would instead recompose everything that reads the
    // model. This counter is the whole of the state, and reading it is what asks for a new reading.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            tick++
        }
    }
    // The tick is read *here*, in the composition, and the recorder is asked in the same breath: a
    // drawing that only called [peaks] would, from outside, be the same closure over the same
    // nothing every time — and a strip that stands still is the one answer this must never give.
    val levels = tick.let { peaks() }
    Canvas(
        modifier
            .height(ROW)
            // The timer above it says the same thing in words; a picture of it would be read twice.
            .clearAndSetSemantics {},
    ) {
        val step = STEP.toPx()
        val bars = min(levels.size, (size.width / step).toInt())
        for (index in 0 until bars) {
            // From the right: the newest window is the one at the edge the strip grows from.
            val height = max(barHeight(levels[levels.size - 1 - index]) * size.height, MIN_BAR.toPx())
            drawRect(
                color = ink,
                topLeft = Offset(size.width - (index + 1) * step, (size.height - height) / 2),
                size = Size(BAR.toPx(), height),
            )
        }
    }
}

/**
 * The fraction of the row a peak fills. `sqrt` rather than the peak itself: full scale is the only
 * level linear amplitude gives a tall bar to, and a quiet room at 0.05 would be a line
 * indistinguishable from silence — which is the one thing this must not show while it records.
 */
fun barHeight(peak: Float): Float = sqrt(peak.coerceIn(0f, 1f))

/** docs/09 "간격": a 2dp bar on a 3dp step, the rhythm the detail screen's waveform is drawn on. */
private val BAR = 2.dp
private val STEP = 3.dp

/** A window with nothing in it is still a window that was recorded. */
private val MIN_BAR = 1.dp

/** The height of the buttons it sits between in the popup. */
private val ROW = 44.dp

/** The rate the helper finishes a window at. */
private const val TICK_MS = 100L
