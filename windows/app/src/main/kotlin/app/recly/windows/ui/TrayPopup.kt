@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.recly.windows.APP_NAME
import app.recly.windows.detect.MeetingDetectionRule
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.jobs.RecentItem
import app.recly.windows.jobs.Recents
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.LedgerHeader
import app.recly.windows.ui.component.LedgerRow
import app.recly.windows.ui.component.LiveWaveform
import app.recly.windows.ui.component.MonoTimer
import app.recly.windows.ui.component.NodeSpec
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.StateNodeRow
import app.recly.windows.ui.component.StatusBadge
import app.recly.windows.ui.theme.BlueprintColors
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.dotGrid
import app.recly.windows.ui.theme.mono
import java.util.Locale
import kotlinx.coroutines.delay
import recly.core.job.JobStatus
import recly.core.model.Source

/**
 * docs/09 화면 원칙 6: the tray's window — three state nodes, the recordings as a ledger a page at a
 * time (docs/12 "메뉴바"), and the actions. The AWT menu that used to carry all of this can only put
 * one run of system text on a line (`trayMenu`), which is why everything with a shape is here
 * instead.
 *
 * docs/09 트렌드 7 keeps glass for chrome the platform owns, and Compose Desktop has none to borrow —
 * so the header and the footer are simply the surface and the ledger between them sits on the dotted
 * page, which is the separation the Mac's popover draws with `.ultraThinMaterial`.
 */
@Composable
fun TrayPopup(model: ShellModel, strings: Strings, onQuit: () -> Unit) {
    val palette = blueprint
    var expanded by remember { mutableStateOf<String?>(null) }
    // docs/03 "다른 기기의 녹음": the ledger has just come on screen, so what the other devices have
    // uploaded since is asked for now rather than at the next job pass ([ShellModel.pullRemote]).
    LaunchedEffect(Unit) { model.pullRemote() }

    Column(Modifier.fillMaxSize().dotGrid(palette)) {
        Header(model, strings)
        HairLine()
        Ledger(model, strings, expanded, Modifier.weight(1f).fillMaxWidth()) { id ->
            expanded = if (expanded == id) null else id
        }
        HairLine()
        Footer(model, strings, onQuit)
    }
}

// --- chrome ---------------------------------------------------------------------------------------

@Composable
private fun Header(model: ShellModel, strings: Strings) {
    val palette = blueprint
    // ADR-016: the workflow this PC runs is its own pointer, and the picker below is where it is
    // moved. A refresh under the open popup that takes it away (another device deleted it, docs/05)
    // leaves nothing chosen, which is what the picker and the node then say.
    val workflow = model.selectedWorkflow

    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        // The header is one line: the source and enough of the device id to tell two machines
        // apart, exactly as the phones and the Mac write it. The whole id is in Settings → About.
        ScreenHeader(
            title = APP_NAME,
            meta = model.deviceId.take(DEVICE_ID_PREFIX).ifEmpty { null }
                ?.let { "${Source.DESKTOP.name.lowercase(Locale.ROOT)} · $it" },
        )
        StateNodeRow(
            nodes = listOf(
                NodeSpec(strings[Str.NODE_DEVICE], Source.DESKTOP.name.lowercase(Locale.ROOT)),
                NodeSpec(
                    label = strings[Str.NODE_WORKFLOW],
                    value = when {
                        workflow != null -> workflow.name
                        model.workflows.isEmpty() -> strings[Str.LABEL_NONE]
                        // ADR-016: with nothing chosen this PC would run nothing, which is a thing
                        // to fix rather than a state to report.
                        else -> strings[Str.WORKFLOW_CHOOSE]
                    },
                ),
                model.stateNode(strings[Str.NODE_STATE], palette),
            ),
            modifier = Modifier.padding(horizontal = Space.m),
        )
        // docs/09 화면 원칙 1: while it is running, the timer *is* the dashboard.
        model.recordingSince?.let { Elapsed(it) }
        // docs/09 화면 원칙 6: and under it the track being written, so the capture is visible as
        // well as counted. The levels are the helper's own write path (`HelperEvent.Level`).
        if (model.recording) {
            LiveWaveform(
                peaks = model::livePeaks,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.m)
                    .testTag("live-waveform"),
            )
        }

        // docs/09 §유동 타이포 · i18n: four buttons of Korean labels are wider than a 520dp popup, so
        // the row wraps rather than pushing the last of them off the window.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (model.recording) {
                // docs/09 트렌드 2: starting and stopping a recording are the high-risk actions this
                // window has, and both go through the helper — so both show that they happened.
                ProcessingButton(
                    label = strings[Str.TRAY_STOP],
                    state = model.action,
                    strings = strings,
                    onClick = model::stop,
                    tone = ButtonTone.DANGER,
                )
            } else {
                ProcessingButton(
                    label = strings[Str.TRAY_START],
                    state = model.action,
                    strings = strings,
                    onClick = { model.start(null) },
                    tone = ButtonTone.PRIMARY,
                    enabled = model.ready && !model.helperMissing && model.titlePrompt == null,
                )
                WorkflowPicker(model)
                // docs/14 "감지": an AWT balloon has no buttons (`TrayNotifier`), so the offer it
                // made stands here for as long as it stands at all.
                if (model.meetingOffer == MeetingDetectionRule.Prompt.START) {
                    BlueprintButton(strings[Str.TRAY_START_DETECTED], model::startDetected)
                }
                // The helper died under a recording; that one was finalized and this offers another.
                if (model.helperCrashed) {
                    BlueprintButton(strings[Str.TRAY_START_AGAIN], onClick = { model.start(null) })
                }
            }
        }
    }
}

/**
 * ADR-016: the workflows, with the one this PC runs marked — and picking another *is* moving the
 * pointer ([ShellModel.selectWorkflow]), which is the write the workflows window's row makes.
 *
 * docs/09 화면 원칙 1: chips rather than a menu, because what a menu hid was the one thing this row
 * is for — which workflow the next recording runs. The Mac's popover draws exactly this
 * (`MenuPopover.workflowPicker`). They are emitted into the caller's [FlowRow], so a set of them
 * wider than the popup wraps onto the next line instead of pushing the actions off it.
 *
 * Nothing at all when the document has no workflows in it: there is no choice to offer, and the
 * State node above is where a PC with nothing chosen is told so.
 */
@Composable
private fun WorkflowPicker(model: ShellModel) {
    model.workflows.forEach { workflow ->
        BlueprintChip(
            label = workflow.name,
            selected = model.selectedWorkflow?.id == workflow.id,
            onClick = { model.selectWorkflow(workflow.id) },
        )
    }
}

/** docs/09 "타이포": `00:12:34`, counted from the moment the recorder said it had started. */
@Composable
private fun Elapsed(since: Long) {
    var now by remember(since) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    MonoTimer(
        text = LedgerFormat.elapsed(now - since),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
        color = blueprint.danger,
    )
}

@Composable
private fun Footer(model: ShellModel, strings: Strings, onQuit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(blueprint.surface)
            .padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlueprintButton(strings[Str.TRAY_EDIT_WORKFLOWS], { model.editorOpen = true }, tone = ButtonTone.QUIET)
        BlueprintButton(strings[Str.TRAY_SETTINGS], { model.settingsOpen = true }, tone = ButtonTone.QUIET)
        Box(Modifier.weight(1f))
        // Never disabled while a capture is in flight: the quit waits for the stop to finalize and
        // queue the recording ([ShellModel.shutdown]), and the label is what says so.
        BlueprintButton(
            label = strings[if (model.recording) Str.TRAY_QUIT_SAVING else Str.TRAY_QUIT],
            onClick = onQuit,
            tone = ButtonTone.QUIET,
        )
    }
}

// --- the ledger (docs/09 화면 원칙 2) ---------------------------------------------------------------

/**
 * docs/12 "메뉴바": [Recents.PAGE] rows a page, and scrolling onto the last one reads the next page.
 * Lazy rather than a scrolling `Column` for exactly that reason — a `Column` composes every row it
 * has whether or not anybody has scrolled to it, so the last row would ask for the next page the
 * moment it was loaded and the ledger would read itself to the end of the recordings.
 */
@Composable
private fun Ledger(
    model: ShellModel,
    strings: Strings,
    expanded: String?,
    modifier: Modifier,
    onExpand: (String) -> Unit,
) {
    val palette = blueprint
    LazyColumn(modifier) {
        item { AlertBanner(model, strings) }
        // docs/06: a device with no grant at all has nothing parked yet to say so — the banner above
        // is what speaks once something is. Both offer the same sign-in.
        if (!model.signedIn && model.alerts.none { it.reason == AlertReason.NEEDS_AUTH }) {
            item {
                ProcessingButton(
                    label = strings[Str.TRAY_SIGN_IN],
                    state = model.action,
                    strings = strings,
                    onClick = model::signIn,
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                )
            }
        }
        item {
            LedgerHeader(
                time = strings[Str.LEDGER_TIME],
                title = strings[Str.LEDGER_TITLE],
                length = strings[Str.LEDGER_LENGTH],
                status = strings[Str.LEDGER_STATUS],
            )
        }
        items(model.recents, key = { it.id }) { item ->
            // The last loaded row is on screen, so the page after it is asked for.
            if (item.id == model.recents.last().id) {
                LaunchedEffect(item.id) { model.loadMoreRecents() }
            }
            RecentRow(item, model, strings, expanded == item.id) { onExpand(item.id) }
        }
        if (model.recents.isEmpty()) {
            item {
                Text(
                    strings[Str.LEDGER_EMPTY],
                    modifier = Modifier.padding(Space.l),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
        }
        item {
            Text(
                model.status.text(strings),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
            )
            // docs/07 §5: a core code's `|detail` is a diagnostic, never translated, and it goes
            // under the sentence in monospace rather than inside it.
            model.statusDetail?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s),
                    style = mono.small,
                    color = palette.textMuted,
                )
            }
        }
    }
}

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": one line per reason, with the count of the jobs on it
 * and the way to fix it — the sign-in, Drive's storage page, the secret form under the step that
 * asked for the key, or the workflow the step belongs to. Never "open the app".
 *
 * The lines come and go with the queue: a reason nothing is blocked on any more is not in
 * [ShellModel.alerts], so its row is simply not drawn (docs/10 rule 3).
 */
@Composable
private fun AlertBanner(model: ShellModel, strings: Strings) {
    val palette = blueprint
    model.alerts.forEach { alert ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(alert.reason.badge())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    strings[alert.reason.label],
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                )
                Text(
                    strings[Str.ALERT_WAITING, alert.count],
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
            // The sign-in is the one fix that happens here rather than on another screen, so it is
            // the one that has a result to show (docs/09 트렌드 2).
            if (alert.reason.fix == FixSurface.SIGN_IN) {
                ProcessingButton(
                    label = strings[alert.reason.fix.label],
                    state = model.action,
                    strings = strings,
                    onClick = { model.fix(alert) },
                )
            } else {
                BlueprintButton(strings[alert.reason.fix.label], { model.fix(alert) })
            }
        }
        HairLine()
    }
}

@Composable
private fun RecentRow(
    item: RecentItem,
    model: ShellModel,
    strings: Strings,
    open: Boolean,
    onClick: () -> Unit,
) {
    val locale = Locale.forLanguageTag(strings.language)
    val length = LedgerFormat.length(item.durationSec)
    LedgerRow(
        date = LedgerFormat.date(item.startedAt),
        time = LedgerFormat.time(item.startedAt),
        title = item.title.text(strings),
        subtitle = item.id,
        length = length,
        status = item.state.ledgerStatus(),
        announce = strings[
            Str.LEDGER_ANNOUNCE,
            item.title.text(strings),
            LedgerFormat.spoken(item.startedAt, locale),
            length,
            item.state.text(strings),
        ],
        onClick = onClick,
    )
    if (open) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(blueprint.background)
                .padding(start = EXPANSION_INSET, end = Space.m)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // docs/08 "오류": why this row is stuck and what to do about it, in the window the user
            // is already in — the same block the recordings window's sidebar draws.
            FailureReason(item, strings)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (item.link != null) {
                    BlueprintButton(strings[Str.RECENT_OPEN_DRIVE], { model.openInDrive(item) })
                }
                if (retryable(item.jobStatus, transcribing = item.waitingMinutes != null)) {
                    ProcessingButton(
                        label = strings[Str.RECENT_RETRY],
                        state = model.action,
                        strings = strings,
                        onClick = { model.retry(item) },
                    )
                }
                // docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where
                // "check the key" lands — and it belongs in this line rather than under the reason
                // above it (docs/09 화면 원칙 2, the Mac's `MenuPopover.actions`).
                CheckKeyButton(item, strings) { model.editWorkflowOf(item) }
                // docs/08 "결과 파일": the transcript, wherever it was written.
                BlueprintButton(
                    label = strings[Str.RECENT_DETAILS],
                    onClick = {
                        model.openDetail(item)
                        model.recordingsOpen = true
                    },
                )
                // docs/03 "앱에서 지우기": the dialog asks about Drive; this only opens it. Never
                // over a recording that is being written to or uploaded ([RecentItem.deletable]).
                if (item.deletable) {
                    BlueprintButton(
                        label = strings[Str.DELETE],
                        onClick = { model.askToDelete(item) },
                        tone = ButtonTone.DANGER,
                    )
                }
            }
        }
    }
}

/**
 * docs/09: state is a code, in monospace, and never colour alone.
 *
 * docs/09 화면 원칙 1 names four — `IDLE`·`STARTING`·`REC`·`STOPPING` — and the transitions come
 * first, because a capture that is coming up or closing is doing something the state it is between
 * does not say ([ShellModel.transition]). `OPENING`, `NO_HELPER` and `NAMING` are this shell's own,
 * for the states a Windows tray has and a Mac's menu bar does not.
 */
internal fun ShellModel.stateCode(): String = when {
    transition == Transition.STOPPING -> "STOPPING"
    recording -> "REC"
    transition == Transition.STARTING -> "STARTING"
    !ready -> "OPENING"
    helperMissing -> "NO_HELPER"
    titlePrompt != null -> "NAMING"
    else -> IDLE
}

/**
 * docs/09 화면 원칙 1: the State node. The recorder has the say — `REC` while it runs, and the codes
 * for a shell that cannot record are what the node is for — and only when the recorder is idle does
 * the executor get to speak: a pass running on one of the ledger's rows is `UPLOADING` in the accent
 * colour, with the loader turning beside it (`NodeSpec.busy`).
 */
private fun ShellModel.stateNode(label: String, palette: BlueprintColors): NodeSpec {
    val code = stateCode()
    if (code == IDLE && Recents.uploading(recents)) {
        return NodeSpec(label, "UPLOADING", valueColor = palette.accent, active = true, busy = true)
    }
    return NodeSpec(
        label = label,
        value = code,
        valueColor = if (recording) palette.danger else palette.textMuted,
        active = recording,
    )
}

/** Nothing in flight here — which is what lets the node say what the executor is doing instead. */
private const val IDLE = "IDLE"

/** Enough of the device id to tell two machines apart, and not so much that it is a column. */
private const val DEVICE_ID_PREFIX = 8

private const val TICK_MS = 1_000L

/** The expanded actions line up under the row's title, past its time column. */
private val EXPANSION_INSET = 84.dp

/**
 * The job states a retry can do anything about. A job on its way is on its way, and a recording with
 * no job has no upload to ask for. `NEEDS_SPACE` is parked rather than failed, but the user frees the
 * space and then asks for it again, which is what the button is for; `NEEDS_AUTH` is the same after a
 * sign-in.
 *
 * docs/09 화면 원칙 2 (2026-09-04): `WAITING` joined them. A job parked on its own `next_run_at` after
 * a failed attempt is a `RETRY` row, and asking for it now rather than waiting the timer out is a
 * thing to be able to do — the same core `retry()` the failures call. Except when the wait is a
 * provider transcribing ([transcribing], the row's waiting-minutes reading): that one is not this
 * device's to hurry, and the row offers nothing.
 */
fun retryable(status: JobStatus?, transcribing: Boolean): Boolean = when (status) {
    JobStatus.FAILED, JobStatus.NEEDS_AUTH, JobStatus.NEEDS_SPACE -> true
    JobStatus.WAITING -> !transcribing
    else -> false
}
