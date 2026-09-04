@file:OptIn(ExperimentalTime::class)

package app.recly.wear.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.recly.recording.RecorderState
import app.recly.wear.R
import app.recly.wear.ui.theme.ReclyWearTheme
import app.recly.wear.ui.theme.WearBlueprint
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import recly.core.sync.WorkflowSummary
import kotlin.time.Clock as TimeClock

/**
 * docs/11 W2. Two screens and no navigation library: on a watch the only journey is "record", and
 * the picker is a detour off it.
 */
@Composable
fun MainScreen(
    state: WearUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    ReclyWearTheme {
        AppScaffold {
            var picking by rememberSaveable { mutableStateOf(false) }
            var informing by rememberSaveable { mutableStateOf(false) }
            when {
                // A recording that started while the picker was open closes it: the pick no longer
                // applies to anything until the next start.
                picking && state.canStart -> WorkflowPicker(
                    workflows = state.workflows,
                    selectedId = state.selectedWorkflowId,
                    onSelect = {
                        onSelect(it)
                        picking = false
                    },
                )

                informing -> InfoScreen(onBack = { informing = false })

                else -> RecordScreen(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onPick = { picking = true },
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
    onPick: () -> Unit,
    onInfo: () -> Unit,
) {
    val scrollState = rememberScrollState()
    ScreenScaffold(scrollState = scrollState) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WearBlueprint.background)
                .verticalScroll(scrollState)
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // docs/09 §7: the workflow is the one word of context a watch has room for. Square
            // and outlined, because docs/09 has no pills.
            CompactButton(
                onClick = onPick,
                enabled = state.canStart,
                shape = RoundedCornerShape(WearBlueprint.radius),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = BorderStroke(WearBlueprint.line, WearBlueprint.grid),
                label = {
                    Text(
                        text = state.selected?.name ?: stringResource(R.string.workflow_phones),
                        maxLines = 1,
                    )
                },
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = formatElapsed(elapsedSeconds(state.startedAt)),
                style = WearBlueprint.timer,
                color = WearBlueprint.text,
                maxLines = 1,
            )

            // docs/09 §7 "상태 한 줄": the state as a code and a colour, and — when a stop had
            // something to report — what it was, because on a watch there is nowhere else to put it.
            Text(
                text = state.message?.text() ?: stringResource(statusLabel(state)),
                style = WearBlueprint.small,
                color = if (state.canStop) WearBlueprint.danger else WearBlueprint.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )

            Spacer(Modifier.height(10.dp))
            RecordNode(recording = state.canStop, busy = state.busy, onClick = if (state.canStop) onStop else onStart)

            Spacer(Modifier.height(8.dp))
            // docs/11 "주의": Samsung will delay the worker, so the badge says "n waiting" rather
            // than pretending the phone has it. A refusal is worse news than a wait and gets its
            // own line — the audio is still on this watch and nothing will retry it.
            // docs/11 W2: while a pass has a phone and is handing files over, the same count is
            // "n sending" — a delayed worker and a transfer in flight are the user's two questions
            // about the same number, and only the sender can tell them apart.
            Text(
                text = stringResource(
                    if (state.handingOver) R.string.sending_badge else R.string.pending_badge,
                    state.pending,
                ),
                style = WearBlueprint.small,
                color = WearBlueprint.textMuted,
                maxLines = 1,
            )
            if (state.failed > 0) {
                Text(
                    text = stringResource(R.string.transfer_failed_badge, state.failed),
                    style = WearBlueprint.small,
                    color = WearBlueprint.danger,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(6.dp))
            CompactButton(
                onClick = onInfo,
                shape = RoundedCornerShape(WearBlueprint.radius),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = BorderStroke(WearBlueprint.line, WearBlueprint.grid),
                label = { Text(text = stringResource(R.string.info_open), maxLines = 1) },
            )
        }
    }
}

/** docs/09 "형태": the round button is a square node here too — filled while it is recording. */
@Composable
private fun RecordNode(recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when {
            recording -> R.string.recording_stop
            busy -> R.string.recording_busy
            else -> R.string.recording_start
        },
    )
    Box(
        modifier = Modifier
            .size(56.dp)
            .border(WearBlueprint.nodeEdge, WearBlueprint.danger, RoundedCornerShape(WearBlueprint.radius))
            .background(
                if (recording) WearBlueprint.danger else WearBlueprint.background,
                RoundedCornerShape(WearBlueprint.radius),
            )
            .clickable(enabled = !busy, onClickLabel = label, role = Role.Button, onClick = onClick),
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

/**
 * docs/11 W2: the crown scrolls it. `ScalingLazyColumn` wires rotary input to its own state by
 * default, so the bezel on a Galaxy Watch works without this screen asking for it.
 */
@Composable
private fun WorkflowPicker(
    workflows: List<WorkflowSummary>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = stringResource(R.string.workflow_pick),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            // Always offered, and the only option when the phone has published nothing: a recording
            // with no pick runs the *phone's* own default when the phone enqueues it (ADR-016).
            item {
                PickerRow(
                    name = stringResource(R.string.workflow_phones),
                    selected = selectedId == null,
                    onClick = { onSelect(null) },
                )
            }
            items(workflows.size) { index ->
                val workflow = workflows[index]
                PickerRow(
                    name = workflow.name,
                    selected = workflow.id == selectedId,
                    onClick = { onSelect(workflow.id) },
                )
            }
        }
    }
}

/**
 * docs/09 "형태" · "모든 상태는 색 + 텍스트": the chosen workflow is marked, not merely filled. A fill
 * is the only difference Wear's own selected button offers, and a fill is a colour — the mark is
 * there for the reader who cannot tell the two backgrounds apart, and for the one whose watch is in
 * the sun. Square, on the theme's 4dp, because docs/09 has no pills.
 */
@Composable
private fun PickerRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(WearBlueprint.radius),
        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
        border = if (selected) null else BorderStroke(WearBlueprint.line, WearBlueprint.grid),
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selected) SELECTED_MARK else UNSELECTED_MARK,
                    style = WearBlueprint.label,
                    maxLines = 1,
                )
                Text(text = name, maxLines = 1)
            }
        },
    )
}

/**
 * The check the chosen row wears, and the space that keeps every other row's name on the same
 * column. Both are punctuation rather than words, so neither is a translated string.
 */
private const val SELECTED_MARK = "✓"
private const val UNSELECTED_MARK = " "

/** docs/07: the ViewModel names the string, the screen says it in the watch's language. */
@Composable
private fun WearMessage.text(): String = when (this) {
    is WearMessage.Saved -> stringResource(R.string.recording_saved, parts, durationSec)
    WearMessage.SaveDeferred -> stringResource(R.string.recording_save_deferred)
    is WearMessage.Failed -> stringResource(R.string.recording_failed, reason)
    WearMessage.MicDenied -> stringResource(R.string.recording_mic_denied)
}

private fun statusLabel(state: WearUiState): Int = when (state.recorder) {
    RecorderState.Idle -> R.string.recording_idle
    RecorderState.Starting -> R.string.recording_busy
    RecorderState.Stopping -> R.string.recording_stopping
    is RecorderState.Recording -> R.string.recording_active
}

/**
 * Ticks only while something is drawing it. The screen is off for most of a three-hour recording
 * and the notification's own chronometer covers that (docs/11 W3) — a timer that kept running here
 * would be a wake-up an hour of battery cannot pay for.
 */
@Composable
private fun elapsedSeconds(startedAt: Instant?): Long {
    if (startedAt == null) return 0
    var now by remember(startedAt) { mutableStateOf(TimeClock.System.now()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(1000)
            now = TimeClock.System.now()
        }
    }
    return (now - startedAt).inWholeSeconds
}
