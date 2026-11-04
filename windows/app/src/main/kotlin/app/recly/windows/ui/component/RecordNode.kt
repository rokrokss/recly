package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import app.recly.windows.ui.theme.processingHoldMs
import kotlinx.coroutines.delay

/**
 * docs/09 "Shape": the phones' square record node — 72dp, outlined while idle and filled while recording, with
 * the stop square in the page colour — in the tray's popup in place of the Start and Stop buttons (2026-11-04),
 * so a start and a stop look alike on every device. Android's `RecordingScreen.RecordNode` is the same node.
 *
 * [working]: the recorder is between two states — Starting or Saving. What the node shows then stays up for the
 * processing hold after the recorder has moved on ([processingHoldMs]): a start answered in 40 ms would otherwise
 * flash a state nobody can read. [label] is its name, since it draws a square and no words.
 */
@Composable
fun RecordNode(
    recording: Boolean,
    working: Boolean,
    enabled: Boolean,
    label: String,
    busyText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    var busy by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(working) {
        if (working) {
            startedAt = System.currentTimeMillis()
            busy = true
            return@LaunchedEffect
        }
        val start = startedAt ?: return@LaunchedEffect
        startedAt = null
        delay(processingHoldMs(System.currentTimeMillis() - start))
        busy = false
    }
    val shape = RoundedCornerShape(Radius.node)
    val on = enabled && !working && !busy
    Box(
        modifier = modifier
            .size(72.dp)
            .focusRing(shape)
            .border(3.dp, palette.danger, shape)
            .background(if (recording) palette.danger else palette.surface, shape)
            .pressCursor(on)
            .clickable(enabled = on, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(if (recording) "stop" else "start"),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Text(busyText, style = mono.title, color = palette.danger)
        } else {
            Box(
                Modifier
                    .size(22.dp)
                    .background(if (recording) palette.background else palette.danger, RoundedCornerShape(Radius.badge)),
            )
        }
    }
}
