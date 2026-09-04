@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package app.recly.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintCheckRow
import app.recly.android.ui.component.BlueprintChip
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogLink
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintMenu
import app.recly.android.ui.component.BlueprintMenuItem
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.LiveWaveform
import app.recly.android.ui.component.MonoTimer
import app.recly.android.ui.component.NodeSpec
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.StateNodeRow
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.android.ui.theme.processingHoldMs
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import recly.core.model.Source

/** docs/09 화면 원칙 1: the recording screen is a dashboard — three state nodes, a monospace timer
 * and one square node that starts and stops it. Everything slower than a tap — the service, the
 * core, the disk — is behind [RecordingViewModel]. */
@Composable
fun RecordingSection(
    state: RecordingUiState,
    recorder: RecorderState,
    /** [ledgerCode]: what the state node says while nothing is recording, or null for nothing. */
    ledger: String? = null,
    onSelectWorkflow: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMicDenied: () -> Unit,
    /** The permission is there again — the refusal in [RecordingUiState.micRefused] is over. */
    onMicGranted: () -> Unit,
    onConsumeAutoStart: () -> Boolean,
    onSaveTitle: (String, Int?) -> Unit,
    onSkipTitle: () -> Unit,
    onConsentAnswered: (Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = rememberPermissionGate(
        refused = state.micRefused,
        onStart = onStart,
        onMicDenied = onMicDenied,
        onMicGranted = onMicGranted,
    )
    val begin = gate.begin
    val palette = blueprint
    val context = LocalContext.current

    // docs/11 A9: a tile, widget or shortcut tap arrives as a flag on the ViewModel and is spent
    // here — through the same permission gate as the button, because the entry point cannot ask.
    LaunchedEffect(state.autoStart) {
        if (state.autoStart == null) return@LaunchedEffect
        // Spent whether or not it is acted on, and only acted on while it is still what the user
        // asked for: a tap the app took too long to reach is not a recording (docs/11 A9).
        if (onConsumeAutoStart() && recorder is RecorderState.Idle) begin()
    }

    val elapsed = elapsedSeconds(recorder)
    val recording = recorder is RecorderState.Recording
    // docs/09 화면 원칙 1: what the ledger has to say, but only while the recorder has nothing of
    // its own — `REC` and the rest are about the recorder, so they win.
    val borrowed = if (recorder is RecorderState.Idle) ledger else null
    val busy = rememberBusyHold(recorder is RecorderState.Starting || recorder is RecorderState.Stopping)
    var picking by remember { mutableStateOf(false) }
    val selected = state.workflows.firstOrNull { it.id == state.selectedWorkflowId }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.app_name),
            // The header is one line: the source and enough of the device id to tell two phones
            // apart. The whole id is in Settings → About, where it is the point.
            meta = "${Source.PHONE.name.lowercase()} · ${rememberDeviceId().take(8)}",
        )
        // The picker hangs off this box, so the box is the padded row and not the whole screen —
        // otherwise the menu opens flush against the left edge, outside the app's own margin.
        Box(Modifier.fillMaxWidth().padding(horizontal = Space.m)) {
            // docs/09 화면 원칙 1: the workflow node is the picker — it is the only node on this
            // screen that is a choice, and so the only one that takes a tap. The device and the
            // state are readouts.
            val canPick = recorder is RecorderState.Idle && state.workflows.isNotEmpty()
            val pickLabel = stringResource(R.string.recording_workflow_pick)
            StateNodeRow(
                nodes = listOf(
                    NodeSpec(
                        label = stringResource(R.string.node_device),
                        // docs/07 rule 4: the source is a code the document carries, not a word —
                        // the same `phone` the header's meta and the other three shells show.
                        value = Source.PHONE.name.lowercase(),
                    ),
                    NodeSpec(
                        label = stringResource(R.string.node_workflow),
                        // ADR-016: the node names the workflow this phone runs — the selection is
                        // the pointer itself, so there is nothing else it could be showing. Nothing
                        // selected is not a word for a state, it is a thing to fix, and the node is
                        // where the user is when it matters.
                        value = selected?.name ?: stringResource(R.string.recording_workflow_choose),
                        onClick = if (canPick) ({ picking = true }) else null,
                        onClickLabel = pickLabel,
                    ),
                    NodeSpec(
                        label = stringResource(R.string.node_state),
                        // docs/09 화면 원칙 1: with nothing to record, the node borrows the ledger's
                        // own `UPLOADING` — or its `RECEIVING`, a recording coming in from the
                        // watch (docs/03) — because that is the only thing happening and this
                        // screen is where the user is ([ledgerCode]).
                        value = borrowed ?: recorder.code(),
                        valueColor = when {
                            recording -> palette.danger
                            borrowed != null -> palette.accent
                            else -> palette.textMuted
                        },
                        active = recording || borrowed != null,
                        busy = borrowed != null,
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            // ADR-016: the picker offers the workflows and nothing else — picking one is what sets
            // this phone's pointer, so there is no separate "no pick" entry to make.
            BlueprintMenu(expanded = picking, onDismissRequest = { picking = false }) {
                state.workflows.forEach { workflow ->
                    BlueprintMenuItem(
                        label = workflow.name,
                        onClick = {
                            onSelectWorkflow(workflow.id)
                            picking = false
                        },
                        divider = workflow != state.workflows.last(),
                        selected = workflow.id == state.selectedWorkflowId,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MonoTimer(hms(elapsed))
            // docs/09 화면 원칙 6: while it records, the track being written, so the microphone is
            // something the screen shows rather than something it claims. Nothing while idle —
            // an empty strip would be a recording with no sound in it.
            if (recording) {
                LiveWaveform(
                    peaks = RecorderService::livePeaks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.m)
                        .testTag("live-waveform"),
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            RecordNode(recorder = recorder, busy = busy, onStart = begin, onStop = onStop)
            Text(
                stringResource(if (busy) R.string.recording_busy else recorder.actionLabel()),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textMuted,
            )
            // One line, however many things the stop had to report (`map` is inline, so the lookups
            // are allowed to be composable; `joinToString`'s transform would not be).
            if (state.messages.isNotEmpty()) {
                Text(
                    state.messages.map { it.text() }.joinToString(" · "),
                    modifier = Modifier.padding(horizontal = Space.m),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            // docs/13 deliverable 1: a refusal is not something the app can retry its way out of —
            // after the second one the system dialog does not open at all — so the screen offers
            // the one thing that can undo it, in the iPhone's own words.
            if (gate.microphoneDenied) {
                Text(
                    stringResource(R.string.recording_mic_required),
                    modifier = Modifier.padding(horizontal = Space.m),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                    textAlign = TextAlign.Center,
                )
                BlueprintButton(
                    label = stringResource(R.string.action_open_settings),
                    onClick = { context.openAppSettings() },
                    modifier = Modifier.testTag("open-settings"),
                )
            }
        }
    }

    // The recording is already finalized on disk by the time this appears; the answer only decides
    // what title the queued job carries.
    if (state.untitled != null) {
        TitleDialog(onSave = onSaveTitle, onSkip = onSkipTitle)
    }

    if (state.consentPrompt) {
        ConsentDialog(onAnswer = onConsentAnswered)
    }
}

/**
 * docs/12 M8 · ADR-011: the recording-consent reminder, in the Mac's own words — the question, the
 * three jurisdictions, the link and the suppression box are the same text in both languages, and
 * `ConsentTextTest` holds them together. There is no covert mode and this is not a permission
 * screen: it is the app saying once that telling the other people is the user's job.
 */
@Composable
private fun ConsentDialog(onAnswer: (Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    var suppress by rememberSaveable { mutableStateOf(false) }
    BlueprintDialog(
        title = stringResource(R.string.consent_question),
        onDismissRequest = { onAnswer(false, suppress) },
        actions = {
            BlueprintButton(
                label = stringResource(R.string.action_cancel),
                onClick = { onAnswer(false, suppress) },
                tone = ButtonTone.QUIET,
            )
            BlueprintButton(
                label = stringResource(R.string.consent_confirm),
                onClick = { onAnswer(true, suppress) },
                tone = ButtonTone.PRIMARY,
                modifier = Modifier.testTag("consent-confirm"),
            )
        },
    ) {
        BlueprintDialogText(
            stringResource(R.string.consent_body),
            modifier = Modifier.testTag("consent-body"),
        )
        // A link and not a third button, for the same reason as on the Mac: the question the
        // dialog is asking is still open.
        BlueprintDialogLink(
            label = stringResource(R.string.consent_link),
            onClick = { context.openUrl(CONSENT_GUIDANCE_URL) },
        )
        BlueprintCheckRow(
            label = stringResource(R.string.consent_suppress),
            checked = suppress,
            onCheckedChange = { suppress = it },
            modifier = Modifier.testTag("consent-suppress"),
        )
    }
}

/**
 * Wikipedia's summary of recording-consent law until Recly has a page of its own to point at — the
 * same URL the Mac opens (`RecMac/MenuModel.consentGuidanceLink`).
 */
private const val CONSENT_GUIDANCE_URL = "https://en.wikipedia.org/wiki/Telephone_call_recording_laws"

/**
 * docs/09 "형태": the round record button is replaced by a square node with a thick border — 72dp,
 * outlined while idle and filled while recording, with the stop square in the page colour.
 *
 * [busy] is [rememberBusyHold]'s, which is where the reason it outlasts the recorder lives.
 */
@Composable
private fun RecordNode(recorder: RecorderState, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val palette = blueprint
    val recording = recorder is RecorderState.Recording
    val label = stringResource(if (busy) R.string.recording_busy else recorder.actionLabel())
    Box(
        modifier = Modifier
            .size(72.dp)
            .border(3.dp, palette.danger, RoundedCornerShape(Radius.node))
            .background(
                if (recording) palette.danger else palette.surface,
                RoundedCornerShape(Radius.node),
            )
            .clickable(
                enabled = !busy,
                onClickLabel = label,
                role = Role.Button,
                onClick = if (recording) onStop else onStart,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Text(stringResource(R.string.action_processing), style = mono.title, color = palette.danger)
        } else {
            Box(
                Modifier
                    .size(22.dp)
                    .background(
                        if (recording) palette.background else palette.danger,
                        RoundedCornerShape(Radius.badge),
                    ),
            )
        }
    }
}

/**
 * docs/09 트렌드 2 · "모션": start and stop are two of the rare high-risk actions, so what the screen
 * shows while [working] stays up for [processingHoldMs] after the recorder has already moved on. A
 * start the service answered in 40ms would otherwise flash a state nobody can read, and the tap
 * would look like it did nothing at all.
 *
 * Reduce motion does not shorten it: the hold is the text state, and the text state is what
 * docs/09 keeps when it takes the animations away.
 */
@Composable
private fun rememberBusyHold(working: Boolean): Boolean {
    var busy by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(working) {
        if (working) {
            startedAt = SystemClock.elapsedRealtime()
            busy = true
            return@LaunchedEffect
        }
        val start = startedAt ?: return@LaunchedEffect
        startedAt = null
        delay(processingHoldMs(SystemClock.elapsedRealtime() - start))
        busy = false
    }
    return busy
}

/**
 * docs/09 화면 원칙 1: the ledger's own `UPLOADING` — a job that is running right now. The rows are
 * already live through `jobs.observe()`, so the node follows a pass that starts and ends while the
 * recording screen is the one on top, without asking the core anything of its own.
 */
fun uploading(items: List<JobItem>): Boolean = items.any { it.state == ItemState.RUNNING }

/** docs/03 "워치 → 폰 전송 계약": the watch is handing a recording over to this phone right now. */
fun receiving(items: List<JobItem>): Boolean = items.any { it.state == ItemState.RECEIVING }

/**
 * docs/09 화면 원칙 1: the code the state node borrows from the ledger while nothing is recording,
 * or null when the ledger has nothing to say and the node is the recorder's own code. A pass of
 * this phone's own wins: it is the one thing on this device the user could be waiting for.
 */
fun ledgerCode(items: List<JobItem>): String? = when {
    uploading(items) -> "UPLOADING"
    receiving(items) -> "RECEIVING"
    else -> null
}

/** docs/09: state is a code, in monospace, and never colour alone. */
private fun RecorderState.code(): String = when (this) {
    RecorderState.Idle -> "IDLE"
    RecorderState.Starting -> "STARTING"
    is RecorderState.Recording -> "REC"
    RecorderState.Stopping -> "STOPPING"
}

private fun RecorderState.actionLabel(): Int = when (this) {
    RecorderState.Idle -> R.string.recording_start
    is RecorderState.Recording -> R.string.recording_stop
    RecorderState.Starting, RecorderState.Stopping -> R.string.recording_busy
}

/**
 * What the record node does, and what the screen says when the microphone has been refused —
 * [microphoneDenied] is the state the sentence and the `Open Settings` button hang off.
 */
private class PermissionGate(val begin: () -> Unit, val microphoneDenied: Boolean)

/**
 * Notifications first, then the microphone, then start — never two dialogs at once, and never a
 * start while one is up (a while-in-use foreground service refuses to launch from behind a
 * permission dialog).
 *
 * Both callbacks ask the system rather than trusting the result they were handed: the boolean is
 * about *this* request, not about what the app currently holds.
 */
@Composable
private fun rememberPermissionGate(
    /** [RecordingUiState.micRefused]: the microphone was asked for and refused, and stays refused
     * until the permission itself says otherwise. */
    refused: Boolean,
    onStart: () -> Unit,
    onMicDenied: () -> Unit,
    onMicGranted: () -> Unit,
): PermissionGate {
    val context = LocalContext.current
    // A refusal is what the screen reports, and the permission itself is what says it is over: a
    // phone that has never been asked has refused nothing, and one that came back from Settings
    // with the microphone on has nothing left to say.
    var microphone by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    // The way back on is outside this process, so the answer is read again on the way back rather
    // than remembered from the request that failed (the same rule as `observeSystemReduceMotion`).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val held = granted(context, Manifest.permission.RECORD_AUDIO)
                microphone = held
                if (held) onMicGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val answered = { held: Boolean ->
        microphone = held
        if (held) onMicGranted() else onMicDenied()
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val held = granted(context, Manifest.permission.RECORD_AUDIO)
        answered(held)
        if (held) onStart()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Its denial costs the user the ongoing chip, never the recording.
        if (granted(context, Manifest.permission.RECORD_AUDIO)) {
            answered(true)
            onStart()
        } else {
            request.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return PermissionGate(
        begin = {
            when {
                !granted(context, Manifest.permission.POST_NOTIFICATIONS) ->
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)

                !granted(context, Manifest.permission.RECORD_AUDIO) ->
                    request.launch(Manifest.permission.RECORD_AUDIO)

                else -> {
                    answered(true)
                    onStart()
                }
            }
        },
        microphoneDenied = refused && !microphone,
    )
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * docs/03: the name, and how many people were in the room. The participant count is a hint the
 * `transcribe` step trusts over the workflow's own `speakers` (docs/08), and "unknown" — the
 * default — writes nothing at all rather than guessing.
 */
@Composable
private fun TitleDialog(onSave: (String, Int?) -> Unit, onSkip: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var participants by remember { mutableStateOf<Int?>(null) }
    BlueprintDialog(
        title = stringResource(R.string.recording_title_prompt),
        onDismissRequest = onSkip,
        actions = {
            BlueprintButton(
                label = stringResource(R.string.recording_title_skip),
                onClick = onSkip,
                tone = ButtonTone.QUIET,
            )
            BlueprintButton(
                label = stringResource(R.string.recording_title_save),
                onClick = { onSave(title, participants) },
                tone = ButtonTone.PRIMARY,
            )
        },
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
        BlueprintDialogText(
            stringResource(R.string.recording_title_hint),
            tone = DialogTone.MUTED,
        )
        BlueprintDialogText(
            stringResource(R.string.recording_participants),
            tone = DialogTone.MUTED,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            PARTICIPANT_CHOICES.forEach { choice ->
                BlueprintChip(
                    label = participantLabel(choice),
                    selected = participants == choice,
                    onClick = { participants = choice },
                    modifier = Modifier.testTag("participants-${choice ?: "unknown"}"),
                )
            }
        }
    }
}

/** docs/03: 2 · 3 · 4 · 5 · 6+ · unknown, and unknown — `null` — is where the dialog starts. */
private val PARTICIPANT_CHOICES = listOf(null, 2, 3, 4, 5, 6)

@Composable
private fun participantLabel(choice: Int?): String = when (choice) {
    null -> stringResource(R.string.recording_participants_unknown)
    // docs/08 caps the hint at 10 speakers; "6+" asks for six and lets the provider find more.
    6 -> stringResource(R.string.recording_participants_many)
    else -> choice.toString()
}

/** Ticks once a second while recording and not at all otherwise. */
@Composable
private fun elapsedSeconds(recorder: RecorderState): Long {
    if (recorder !is RecorderState.Recording) return 0
    var seconds by remember(recorder.recordingId) { mutableStateOf(elapsedSec(recorder)) }
    LaunchedEffect(recorder.recordingId) {
        while (true) {
            seconds = elapsedSec(recorder)
            delay(1000)
        }
    }
    return seconds
}

private fun elapsedSec(recorder: RecorderState.Recording): Long =
    (Clock.System.now() - recorder.startedAt).inWholeSeconds.coerceAtLeast(0)
