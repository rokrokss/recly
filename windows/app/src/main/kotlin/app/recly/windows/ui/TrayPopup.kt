package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.theme.forWord
import app.recly.windows.ui.component.RecordNode
import app.recly.windows.ui.component.statusColumnWidth
import app.recly.windows.ui.component.textColumnWidth
import app.recly.windows.ui.theme.BlueprintColors
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
        // docs/12 "Menu bar" (2026-11-04): the devices a start would take, read again while the popup is up — the
        // defaults change under it — and not at all while it is closed, when this effect is gone with it.
        LaunchedEffect(Unit) {
            while (true) {
                model.refreshNextDevices()
                delay(DEVICES_MS)
            }
        }
        // docs/09 screen principle 1 (2026-11-04): the phones' square record node, with the timer beside it —
        // `00:00` while idle, so a start and a stop happen in the one place and nothing below them moves.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, top = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Off while a capture is coming up or being saved (STARTING, STOPPING), as Start was.
            val startable = model.ready && !model.helperMissing && model.titlePrompt == null && model.transition == null
            RecordNode(
                recording = model.recording,
                working = model.transition != null,
                enabled = model.recording || startable,
                label = strings[if (model.recording) Str.TRAY_STOP else Str.TRAY_START],
                busyText = strings[Str.ACTION_PROCESSING],
                onClick = { if (model.recording) model.stop() else model.start() },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                // While it saves, the recording's final length stays on it until the recorder is idle (2026-10-08).
                Elapsed(model.recordingSince, model.recordingEndedAt)
                // What a start made now would capture. Only before a start: the helper names the defaults, and
                // what a running capture follows is its own (docs/09 screen principle 1).
                if (!model.recording && model.transition == null) {
                    model.nextDevices.microphone?.let { DeviceLine(strings[Str.TRAY_MICROPHONE, it]) }
                    model.nextDevices.output?.let { DeviceLine(strings[Str.TRAY_SYSTEM_AUDIO, it]) }
                }
            }
        }
        // docs/09 screen principle 6: and under them the track being written, so the capture is visible as
        // well as counted. The levels are the helper's own write path (`HelperEvent.Level`).
        if (model.recording) {
            LiveWaveform(
                peaks = model::livePeaks,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.m, end = Space.m, top = Space.sm)
                    .testTag("live-waveform"),
            )
        }
        // docs/14 "Detection": an AWT balloon has no buttons (`TrayNotifier`), so the offer it
        // made stands here for as long as it stands at all.
        if (!model.recording && model.meetingOffer == MeetingDetectionRule.Prompt.START) {
            BlueprintButton(
                strings[Str.TRAY_START_DETECTED],
                model::startDetected,
                modifier = Modifier.padding(start = Space.m, end = Space.m, top = Space.sm),
            )
        }
        // docs/08 "Memo": the take being recorded has its row under Stop, empty or not — it is where the memo is written.
        if (model.recording) model.liveRecordingId?.let { TrayMemo(model, it, strings) }
        Spacer(Modifier.height(Space.sm))
    }
}

/** A device a start would capture, in the status lines' muted mono (Hangul, Kana and Han in the sans). */
@Composable
private fun DeviceLine(text: String) {
    Text(text, style = mono.small.forWord(text), color = blueprint.textMuted)
}

/**
 * Whether the ledger's last line says the status (2026-10-10): not while a line above says the same — the wait for
 * Drive that the banner counts.
 */
internal fun statusLineShown(status: UiMessage, alerts: List<JobAlert>): Boolean = when {
    (status as? UiMessage.Res)?.key == Str.STATUS_SIGN_IN_NEEDED -> alerts.none { it.reason == AlertReason.NEEDS_AUTH }
    else -> true
}

/**
 * docs/09 "Typography": `12:34`, or `01:02:03` from the hour (2026-10-08), counted from the moment the recorder
 * said it had started — and stopped at [until], the moment it ended, while the stop saves. `00:00` with no
 * recording ([since] null), as on the phones.
 */
@Composable
private fun Elapsed(since: Long?, until: Long?) {
    MonoTimer(
        text = since?.let { rememberClock(it, until) } ?: LedgerFormat.clock(0),
        color = if (since != null) blueprint.danger else null,
    )
}

/**
 * The recording's length as the timer says it, ticking once a second until [until] — shared by the popup's timer
 * and the tray icon's tooltip, so the two never say different lengths.
 */
@Composable
internal fun rememberClock(since: Long, until: Long?): String {
    var now by remember(since) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since, until) {
        while (until == null) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    return LedgerFormat.clock((until ?: now) - since)
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
        // At the status badge's size, as Restore is, and the band around them as low as the boxes (2026-11-04).
        BlueprintButton(strings[Str.TRAY_SETTINGS], { model.settingsOpen = true }, tone = ButtonTone.QUIET, compact = true, tight = true)
        Box(Modifier.weight(1f))
        // Never disabled while a capture is in flight: the quit waits for the stop to finalize and
        // queue the recording ([ShellModel.shutdown]), and the label is what says so.
        BlueprintButton(
            label = strings[if (model.recording) Str.TRAY_QUIT_SAVING else Str.TRAY_QUIT],
            onClick = onQuit,
            tone = ButtonTone.QUIET,
            compact = true,
            tight = true,
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
    // docs/09 screen principle 2: `Filling in` while ChatGPT fills it in, `Organizing` while its notes are, and heard so too.
    val status = item.ledgerStatus(strings, filling = model.fills.any { it.recordingId == item.id }, organizing = item.id in model.organizing)
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

private const val TICK_MS = 1_000L

/** How often the popup reads the default devices again while it is up. */
private const val DEVICES_MS = 3_000L

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
