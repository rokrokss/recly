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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import app.recly.windows.APP_NAME
import app.recly.windows.detect.MeetingDetectionRule
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.text
import app.recly.windows.jobs.RecentItem
import app.recly.windows.jobs.Recents
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BannerRow
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.EmptyList
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.LedgerHeader
import app.recly.windows.ui.component.LedgerRow
import app.recly.windows.ui.component.LiveWaveform
import app.recly.windows.ui.component.MonoTimer
import app.recly.windows.ui.component.NodeSpec
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.StateNodeRow
import app.recly.windows.ui.component.statusColumnWidth
import app.recly.windows.ui.component.textColumnWidth
import app.recly.windows.ui.theme.BlueprintColors
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.dotGrid
import app.recly.windows.ui.theme.mono
import java.util.Locale
import kotlinx.coroutines.delay
import recly.core.job.JobStatus

/**
 * docs/09 screen principle 6: the tray's window — three state nodes, the recordings as a ledger a page at a
 * time (docs/12 "Menu bar"), and the actions. The AWT menu that used to carry all of this can only put
 * one run of system text on a line (`trayMenu`), which is why everything with a shape is here
 * instead.
 *
 * docs/09 trend 7 keeps glass for chrome the platform owns, and Compose Desktop has none to borrow —
 * so the header and the footer are simply the surface and the ledger between them sits on the dotted
 * page, which is the separation the Mac's popover draws with `.ultraThinMaterial`.
 */
@Composable
fun TrayPopup(model: ShellModel, strings: Strings, onQuit: () -> Unit) {
    val palette = blueprint
    // docs/03 "Recordings from other devices": the ledger has just come on screen, so what the other devices have
    // uploaded since is asked for now rather than at the next job pass ([ShellModel.pullRemote]).
    LaunchedEffect(Unit) { model.pullRemote() }

    Column(Modifier.fillMaxSize().dotGrid(palette)) {
        Header(model, strings)
        HairLine()
        Ledger(model, strings, Modifier.weight(1f).fillMaxWidth())
        HairLine()
        Footer(model, strings, onQuit)
    }
}

// --- chrome ---------------------------------------------------------------------------------------

@Composable
private fun Header(model: ShellModel, strings: Strings) {
    val palette = blueprint

    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        // The header is the app's name alone (2026-10-08): the device id is in Settings → About,
        // and nothing takes the line it used to have.
        ScreenHeader(title = APP_NAME)
        StateNodeRow(
            nodes = listOf(
                NodeSpec(strings[Str.NODE_DEVICE], strings[Str.NODE_DESKTOP]),
                NodeSpec(
                    label = strings[Str.PROCESSING_TRANSCRIPTION],
                    value = model.processing?.summary?.let { if (it.mode == recly.core.processing.TranscriptionMode.EXTERNAL) it.external?.provider?.let(recly.core.transcribe.SttProviders::displayName).orEmpty() else strings[it.mode.label()] } ?: strings[Str.PROCESSING_LOCAL],
                ),
                model.stateNode(strings, palette),
            ),
            modifier = Modifier.padding(horizontal = Space.m),
        )
        // docs/09 screen principle 1: while it is running, the timer *is* the dashboard — and while it saves,
        // the recording's final length stays on it until the recorder is idle (2026-10-08).
        model.recordingSince?.let { Elapsed(it, model.recordingEndedAt) }
        // docs/09 screen principle 6: and under it the track being written, so the capture is visible as
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

        // docs/09 §Fluid typography · i18n: four buttons of Korean labels are wider than a 520dp popup, so
        // the row wraps rather than pushing the last of them off the window.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (model.recording) {
                // docs/09 trend 2: starting and stopping a recording are the high-risk actions this
                // window has, and both go through the helper — so both show that they happened.
                ProcessingButton(
                    label = strings[Str.TRAY_STOP],
                    state = model.action,
                    strings = strings,
                    onClick = model::stop,
                    tone = ButtonTone.DANGER,
                )
                // docs/03 "Metadata": the moment the timer is at, marked; the line under this row says when.
                BlueprintButton(strings[Str.HIGHLIGHT], model::highlightNow, enabled = model.transition == null)
            } else {
                ProcessingButton(
                    label = strings[Str.TRAY_START],
                    state = model.action,
                    strings = strings,
                    onClick = model::start,
                    tone = ButtonTone.PRIMARY,
                    // Off while a capture is coming up or being saved (STARTING, STOPPING) as well.
                    enabled = model.ready && !model.helperMissing && model.titlePrompt == null && model.transition == null,
                )
                // docs/14 "Detection": an AWT balloon has no buttons (`TrayNotifier`), so the offer it
                // made stands here for as long as it stands at all.
                if (model.meetingOffer == MeetingDetectionRule.Prompt.START) {
                    BlueprintButton(strings[Str.TRAY_START_DETECTED], model::startDetected)
                }
            }
        }
        // `Highlight · 00:12:34`, under the button that marked it rather than at the foot of the ledger (2026-10-10).
        // The line is there for the whole recording, blank until a mark, so the ledger does not move when one comes
        // and goes.
        if (model.recording) {
            Text(
                model.status.takeIf { it.isHighlightLine() }?.text(strings).orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** The status is the line [ShellModel.highlightNow] says for a moment. */
private fun UiMessage.isHighlightLine(): Boolean = (this as? UiMessage.Res)?.key == Str.HIGHLIGHT_MARKED

/**
 * Whether the ledger's last line says the status (2026-10-10): not while a line above says the same — the highlight
 * just marked, under the Highlight button, or the wait for Drive that the banner counts.
 */
internal fun statusLineShown(status: UiMessage, alerts: List<JobAlert>): Boolean = when {
    status.isHighlightLine() -> false
    (status as? UiMessage.Res)?.key == Str.STATUS_SIGN_IN_NEEDED -> alerts.none { it.reason == AlertReason.NEEDS_AUTH }
    else -> true
}

/**
 * docs/09 "Typography": `12:34`, or `01:02:03` from the hour (2026-10-08), counted from the moment the recorder
 * said it had started — and stopped at [until], the moment it ended, while the stop saves.
 */
@Composable
private fun Elapsed(since: Long, until: Long?) {
    var now by remember(since) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since, until) {
        while (until == null) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    MonoTimer(
        text = LedgerFormat.clock((until ?: now) - since),
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
        BlueprintButton(strings[Str.TRAY_SETTINGS], { model.settingsOpen = true }, tone = ButtonTone.QUIET)
        Box(Modifier.weight(1f))
        // Never disabled while a capture is in flight: the quit waits for the stop to finalize and
        // queue the recording ([ShellModel.shutdown]), and the label is what says so.
        BlueprintButton(
            label = strings[if (model.recording) Str.TRAY_QUIT_SAVING else Str.TRAY_QUIT],
            onClick = onQuit,
            tone = ButtonTone.QUIET,
            minWidth = MinTouch,
        )
    }
}

// --- the ledger (docs/09 screen principle 2) ---------------------------------------------------------------

/**
 * docs/12 "Menu bar": [Recents.PAGE] rows a page, and scrolling onto the last one reads the next page.
 * Lazy rather than a scrolling `Column` for exactly that reason — a `Column` composes every row it
 * has whether or not anybody has scrolled to it, so the last row would ask for the next page the
 * moment it was loaded and the ledger would read itself to the end of the recordings.
 */
@Composable
private fun Ledger(
    model: ShellModel,
    strings: Strings,
    modifier: Modifier,
) {
    val palette = blueprint
    // docs/09 screen principle 2: the status column is as wide as the widest badge this ledger can wear.
    val statusWidth = maxOf(
        statusColumnWidth(ledgerBadgeLabels(strings)),
        textColumnWidth(listOf(strings[Str.LEDGER_STATUS]), MaterialTheme.typography.labelSmall),
    )
    LazyColumn(modifier) {
        // The first-run offer of the speech model, at the top while the model is really missing.
        if (model.modelCardShown) item { ModelCard(model, strings) }
        item { AlertBanner(model, strings) }
        item {
            LedgerHeader(
                time = strings[Str.LEDGER_TIME],
                title = strings[Str.LEDGER_TITLE],
                length = strings[Str.LEDGER_LENGTH],
                status = strings[Str.LEDGER_STATUS],
                statusWidth = statusWidth,
            )
        }
        items(model.recents, key = { it.id }) { item ->
            // The last loaded row is on screen, so the page after it is asked for.
            if (item.id == model.recents.last().id) {
                LaunchedEffect(item.id) { model.loadMoreRecents() }
            }
            RecentRow(item, model, strings, statusWidth)
        }
        if (model.recents.isEmpty()) {
            // Before the first read the list is not empty, it is not read yet (D15).
            item { EmptyList(strings[if (model.recentsLoading) Str.LIST_LOADING else Str.LEDGER_EMPTY], Modifier.fillMaxWidth()) }
        }
        if (statusLineShown(model.status, model.alerts)) item {
            Text(
                model.status.text(strings),
                // docs/09 "Disabled, focus and structure": a status line that changes while the user looks at it is
                // announced politely.
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = Space.m, vertical = Space.s),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
            )
            // docs/07 §5: a core code's `|detail` is a diagnostic, never translated, and it goes
            // under the sentence in monospace rather than inside it.
            model.statusDetail?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s),
                    style = mono.small.copy(textDirection = TextDirection.Ltr),
                    color = palette.textMuted,
                )
            }
        }
    }
}

/**
 * docs/10 "Failures the user can fix, and their notices": one line per reason, with the count of the jobs on it
 * and the way to fix it — the sign-in, Drive's storage page, or the processing settings where the
 * key and the transcription are set. Never "open the app".
 *
 * The lines come and go with the queue: a reason nothing is blocked on any more is not in
 * [ShellModel.alerts], so its row is simply not drawn (docs/10 rule 3).
 */
@Composable
private fun AlertBanner(model: ShellModel, strings: Strings) {
    val palette = blueprint
    model.alerts.forEach { alert ->
        // The model's line is the download itself while one runs ([ModelBanner]).
        if (alert.reason == AlertReason.LOCAL_MODEL_REQUIRED) {
            ModelBanner(model, strings, alert)
            HairLine()
            return@forEach
        }
        val waitingForDrive = alert.reason == AlertReason.NEEDS_AUTH
        BannerRow(
            badge = if (waitingForDrive) null else alert.reason.badge().worded(strings),
            action = {
                // The sign-in is the one fix that happens here rather than on another screen, so it is
                // the one that has a result to show (docs/09 trend 2).
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
            },
        ) {
            if (waitingForDrive) {
                Text(
                    strings[Str.ALERT_UPLOADS_WAITING, alert.count],
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textMuted,
                )
            } else {
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
        }
        HairLine()
    }
}

/**
 * docs/09 screen principle 2 (2026-10-28): the row is the recording, as the Details list's row is — time, title with the
 * transcript's first words under it, length, state — and a click opens it in the Details window. What can be done about
 * it is there: the fixes under its open row, and Open in Drive, Open the folder and Delete in its More menu.
 */
@Composable
private fun RecentRow(
    item: RecentItem,
    model: ShellModel,
    strings: Strings,
    statusWidth: Dp,
) {
    val locale = Locale.forLanguageTag(strings.language)
    val title = item.title.text(strings)
    val length = LedgerFormat.length(item.durationSec)
    // The transcript's first words (2026-10-08), the Details row's own, and nothing for a recording with no transcript here.
    val preview = model.previews[item.id]
    // docs/09 screen principle 2: `Filling in` while ChatGPT fills it in, and heard so too.
    val status = item.ledgerStatus(strings, filling = model.fills.any { it.recordingId == item.id })
    LedgerRow(
        date = LedgerFormat.date(item.startedAt),
        time = LedgerFormat.time(item.startedAt),
        title = title,
        subtitle = preview.orEmpty(),
        length = length,
        status = status,
        statusWidth = statusWidth,
        announce = strings[
            Str.LEDGER_ANNOUNCE,
            // Heard after the title, as the Details row reads them, with the sentence's own separator.
            listOfNotNull(title, preview).joinToString(" · "),
            LedgerFormat.spoken(item.startedAt, locale),
            length,
            if (status.code == FillingStatus.code) status.label else item.state.text(strings),
        ],
        // Through the Details window's own leave when it is up, so an edit in it asks first (D7).
        onClick = { model.showDetail(item) },
    )
}

/**
 * docs/09: state is a code, in monospace, and never colour alone. The code is the state's internal name; the
 * node says its word ([stateWord], 2026-10-08).
 *
 * docs/09 screen principle 1 names four — `IDLE`·`STARTING`·`REC`·`STOPPING` — and the transitions come
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
 * docs/09 screen principle 1: the State node. The recorder has the say — `REC` while it runs, and the codes
 * for a shell that cannot record are what the node is for — and only when the recorder is idle does
 * the executor get to speak: a pass running on one of the ledger's rows is `UPLOADING` in the accent
 * colour, with the loader turning beside it (`NodeSpec.busy`).
 */
private fun ShellModel.stateNode(strings: Strings, palette: BlueprintColors): NodeSpec {
    val label = strings[Str.NODE_STATE]
    val code = stateCode()
    if (code == IDLE && Recents.uploading(recents)) {
        return NodeSpec(label, strings[Str.STATE_UPLOADING], valueColor = palette.accent, active = true, busy = true)
    }
    return NodeSpec(
        label = label,
        value = strings[stateWord(code)],
        valueColor = if (recording) palette.danger else palette.textMuted,
        active = recording,
    )
}

/** What the State node says for a [stateCode], in the app's language (the UX decisions of 2026-10-08). */
internal fun stateWord(code: String): Str = when (code) {
    "STOPPING" -> Str.STATUS_SAVING
    "REC" -> Str.STATUS_RECORDING
    "STARTING" -> Str.NODE_STARTING
    "OPENING" -> Str.STATUS_OPENING
    "NO_HELPER" -> Str.NODE_CANNOT_RECORD
    "NAMING" -> Str.NODE_NAMING
    else -> Str.NODE_READY
}

/** Nothing in flight here — which is what lets the node say what the executor is doing instead. */
private const val IDLE = "IDLE"

private const val TICK_MS = 1_000L

/**
 * The job states a retry can do anything about. A job on its way is on its way, and a recording with
 * no job has no upload to ask for. `NEEDS_SPACE` is parked rather than failed, but the user frees the
 * space and then asks for it again, which is what the button is for; `NEEDS_AUTH` is the same after a
 * sign-in.
 *
 * docs/09 screen principle 2 (2026-09-04): `WAITING` joined them. A job parked on its own `next_run_at` after
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
