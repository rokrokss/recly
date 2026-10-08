@file:OptIn(ExperimentalTime::class)

package app.recly.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.recly.recording.RecorderState
import app.recly.wear.R
import app.recly.wear.ui.theme.ReclyWearTheme
import app.recly.wear.ui.theme.WearBlueprint
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlin.time.Clock as TimeClock

/**
 * docs/11 W2. Two screens and no navigation library: on a watch the only journey is "record", and
 * the info page is a detour off it.
 */
@Composable
fun MainScreen(
    state: WearUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHighlight: () -> Unit,
) {
    ReclyWearTheme {
        AppScaffold {
            var informing by rememberSaveable { mutableStateOf(false) }
            when {
                informing -> InfoScreen(onBack = { informing = false })

                else -> RecordScreen(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onHighlight = onHighlight,
                    onInfo = { informing = true },
                )
            }
        }
    }
}

@Composable
private fun RecordScreen(
    state: WearUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHighlight: () -> Unit,
    onInfo: () -> Unit,
) {
    val scrollState = rememberScrollState()
    ScreenScaffold(scrollState = scrollState) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WearBlueprint.background)
                .verticalScroll(scrollState)
                // docs/11 W2: Help is below the fold, and a Galaxy Watch is scrolled with its bezel.
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollState), remember { FocusRequester() }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // docs/11 W2: the record screen is the whole first screen. The guide is read once; the
            // button is what every visit is for.
            Column(
                modifier = Modifier
                    .height(LocalConfiguration.current.screenHeightDp.dp)
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                FitToWatch {
                    Text(
                        text = formatElapsed(elapsedSeconds(state.recorder)),
                        style = WearBlueprint.timer,
                        color = WearBlueprint.text,
                        maxLines = 1,
                    )

                    // docs/09 §7 "one-line status": the one thing worth saying, and nothing when
                    // there is none. Blank, the line still holds a real one's height, measured in the
                    // watch's own script — a Hangul line stands taller than a Latin or empty one, and
                    // the node would hop when something came back to say.
                    val line = statusLine(state)
                    if (line != null && state.message == null && state.canStop && state.highlightedSec != null) {
                        // The mark's line is said on one line, smaller where it must be: its two seconds
                        // should not move the stop node, and a Korean sentence must not break inside a word.
                        BasicText(
                            text = line.first,
                            style = WearBlueprint.small.copy(color = line.second, textAlign = TextAlign.Center),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = WearBlueprint.small.fontSize * HIGHLIGHT_MIN_SCALE,
                                maxFontSize = WearBlueprint.small.fontSize,
                                stepSize = 0.5.sp,
                            ),
                        )
                    } else Text(
                        text = line?.first ?: stringResource(R.string.recording_active),
                        modifier = if (line == null) Modifier.clearAndSetSemantics {} else Modifier,
                        style = WearBlueprint.small,
                        color = line?.second ?: Color.Transparent,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )

                    Spacer(Modifier.height(10.dp))
                    RecordNode(recording = state.canStop, busy = state.busy, onClick = if (state.canStop) onStop else onStart)
                    // docs/03 "Metadata": below the stop node, only while it records — the node slides up
                    // to make room, the way the phone's record node slides aside.
                    AnimatedVisibility(
                        visible = state.canStop,
                        enter = expandVertically(tween(MOTION_MS)),
                        exit = shrinkVertically(tween(MOTION_MS)),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(8.dp))
                            HighlightButton(onHighlight)
                        }
                    }
                }
            }
            FitToWatch {
                CompactButton(
                    onClick = onInfo,
                    shape = RoundedCornerShape(WearBlueprint.radius),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = BorderStroke(WearBlueprint.line, WearBlueprint.grid),
                    label = { Text(text = stringResource(R.string.info_open), maxLines = 1) },
                )
            }
            Spacer(Modifier.height(padding.calculateBottomPadding()))
        }
    }
}

/**
 * docs/09 §7 "one-line status", in the order it is worth saying: what a stop had to report, what the
 * recorder is doing, then what is still on this watch. Idle with nothing left it is `null` — the
 * hollow node and 00:00 already do.
 *
 * docs/11 "Caveats": Samsung will delay the worker, so the line says "waiting to send" rather than
 * pretending the phone has it, and W2: while a pass has a phone and is handing files over, the same
 * recordings are "sending". No count — what the user asks is whether any are left. A refusal is
 * worse news than a wait and outranks it — the audio is still on this watch and nothing will retry it.
 */
@Composable
private fun statusLine(state: WearUiState): Pair<String, Color>? {
    val message = state.message
    val highlighted = state.highlightedSec
    return when {
        message != null -> message.text() to WearBlueprint.textMuted
        highlighted != null && state.canStop -> stringResource(R.string.highlight_status, stamp(highlighted)) to WearBlueprint.accent
        state.recorder is RecorderState.Recording -> stringResource(R.string.recording_active) to WearBlueprint.danger
        state.recorder == RecorderState.Starting -> stringResource(R.string.recording_busy) to WearBlueprint.textMuted
        state.recorder == RecorderState.Stopping -> stringResource(R.string.recording_stopping) to WearBlueprint.textMuted
        state.failed > 0 -> stringResource(R.string.transfer_failed_badge) to WearBlueprint.danger
        state.pending > 0 -> stringResource(
            if (state.handingOver) R.string.sending_badge else R.string.pending_badge,
        ) to WearBlueprint.textMuted
        else -> null
    }
}

/**
 * docs/09 "Fluid typography": the record screen is drawn for a large round watch, and a smaller one gets
 * the same screen scaled down to its width — at the drawn size the Help button sat under the curve
 * of a 192dp watch. Larger watches keep the drawn size.
 */
@Composable
private fun FitToWatch(content: @Composable () -> Unit) {
    val scale = (LocalConfiguration.current.screenWidthDp / DRAWN_FOR_WIDTH_DP).coerceAtMost(1f)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
        content = content,
    )
}

/** The large round watch (454 px at xhdpi) the record screen's sizes were chosen on. */
private const val DRAWN_FOR_WIDTH_DP = 227f

/** docs/09 "Motion": the standard 200 ms; the system's own animation scale switches it off. */
private const val MOTION_MS = 200

/** How far the mark's line may shrink to stay on one line. */
private const val HIGHLIGHT_MIN_SCALE = 0.7f

/** The mark's time as every shell writes a live timer — `00:12`, `01:02:03` (docs/09 "Typography"). */
private fun stamp(seconds: Long): String = formatElapsed(seconds)

/**
 * docs/09 screen principle 7: the phone's highlight node under the stop node — an accent outline and a
 * small filled accent square, no word. The name is for screen readers only.
 */
@Composable
private fun HighlightButton(onClick: () -> Unit) {
    val label = stringResource(R.string.highlight)
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(1.5.dp, WearBlueprint.accent, RoundedCornerShape(WearBlueprint.radius))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).background(WearBlueprint.accent, RoundedCornerShape(2.dp)))
    }
}

/** docs/09 "Shape": the round button is a square node here too — filled while it is recording. */
@Composable
private fun RecordNode(recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when {
            recording -> R.string.recording_stop
            busy -> R.string.recording_busy
            else -> R.string.recording_start
        },
    )
    // The node draws a square and no words, so this is its name (docs/09 "Accessibility") — the
    // phone's own: Start recording, or Stop.
    val name = stringResource(if (recording) R.string.recording_stop else R.string.tile_start)
    Box(
        modifier = Modifier
            .size(56.dp)
            .border(WearBlueprint.nodeEdge, WearBlueprint.danger, RoundedCornerShape(WearBlueprint.radius))
            .background(
                if (recording) WearBlueprint.danger else WearBlueprint.background,
                RoundedCornerShape(WearBlueprint.radius),
            )
            .clickable(enabled = !busy, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(
                    if (recording) WearBlueprint.onDanger else WearBlueprint.danger,
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/** docs/07: the ViewModel names the string, the screen says it in the watch's language. */
@Composable
private fun WearMessage.text(): String = when (this) {
    WearMessage.SaveDeferred -> stringResource(R.string.recording_save_deferred)
    is WearMessage.Failed -> stringResource(R.string.recording_failed, reason)
    WearMessage.MicDenied -> stringResource(R.string.recording_mic_denied)
}

/**
 * Ticks only while something is drawing it. The screen is off for most of a three-hour recording
 * and the notification's own chronometer covers that (docs/11 W3) — a timer that kept running here
 * would be a wake-up an hour of battery cannot pay for.
 *
 * While the recording is being saved it holds the length it ended at; only an idle recorder goes back
 * to `00:00` (UX decisions of 2026-10-08).
 */
@Composable
private fun elapsedSeconds(recorder: RecorderState): Long {
    // Not state: the start the stop is measured from, kept across Recording → Stopping, which has none.
    val seen = remember { SeenStart() }
    when (recorder) {
        is RecorderState.Recording -> seen.startedAt = recorder.startedAt
        RecorderState.Idle -> seen.startedAt = null
        RecorderState.Starting, RecorderState.Stopping -> Unit
    }
    if (recorder == RecorderState.Stopping) {
        return remember(seen.startedAt) { seen.startedAt?.let { (TimeClock.System.now() - it).inWholeSeconds.coerceAtLeast(0) } ?: 0 }
    }
    val startedAt = (recorder as? RecorderState.Recording)?.startedAt ?: return 0
    var now by remember(startedAt) { mutableStateOf(TimeClock.System.now()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            now = TimeClock.System.now()
        }
    }
    return (now - startedAt).inWholeSeconds
}

private class SeenStart {
    var startedAt: Instant? = null
}
