@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package app.recly.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.component.GlyphIcon
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.BadgeTone
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintRadioRow
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LedgerHeader
import app.recly.android.ui.component.LedgerRow
import app.recly.android.ui.component.LedgerStatus
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.ledgerColumns
import app.recly.android.ui.component.ledgerLayout
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.android.work.ModelDownloadState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import app.recly.android.ui.component.focusRing
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.MessageBanner
import app.recly.android.ui.component.AlertLine
import app.recly.android.ui.component.EmptyList

/**
 * docs/11 A4, drawn as docs/09 screen principle 2 asks: a ledger. One row per recording — when, what, how
 * long, and the state as a code — and the detail is behind the row rather than in front of it: a tap on the
 * row opens it, and what the recording needs, Open in Drive and Delete are there (2026-10-28).
 */
@Composable
fun JobsScreen(
    state: JobsUiState,
    onOpenDetail: (JobItem) -> Unit,
    /** docs/10: the banner is not a notice, it is the way to the screen that fixes the thing. */
    onFix: (JobAlert) -> Unit,
    /** The speech model download: null (the banner) for the saved settings' language. */
    onDownloadModel: (language: String?, onWifi: Boolean) -> Unit,
    onCancelDownload: () -> Unit,
    /** docs/03: the pull — this phone's list and what the other devices have put in Drive. */
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    /** docs/03 "Naming rules": the files the system picker returned, to import one after another. */
    onImport: (List<Uri>) -> Unit = {},
    onDismissImport: () -> Unit = {},
    /** docs/09 "Search": the field's text, and a result's tap. */
    onSearch: (String) -> Unit = {},
    onOpenHit: (SearchHit) -> Unit = {},
) {
    val palette = blueprint
    val metered = rememberMeteredGate(state.download.info?.modelBytes)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onImport(uris) }

    val resources = LocalContext.current.resources
    Column(modifier.fillMaxSize()) {
        // The tab is "List"; the screen is what is in it, as on the iPhone.
        ScreenHeader(
            title = stringResource(R.string.jobs_title),
            meta = headerCounts(
                state.items.size,
                state.items.count { it.waiting() },
                state.items.count { it.state.failing() },
                waitingWord = { resources.getString(R.string.jobs_summary_waiting, it) },
                failedWord = { resources.getString(R.string.jobs_summary_failed, it) },
            ),
            // docs/09 "Import": at the header's end, the system picker for audio and video.
            trailing = {
                GlyphButton(Glyph.IMPORT, stringResource(R.string.import_audio), { picker.launch(arrayOf("audio/*", "video/*")) },
                    Modifier.testTag("import"))
            },
            stackable = false,
        )

        SearchField(state.query, onSearch, Modifier.padding(start = Space.m, end = Space.m, bottom = Space.s))

        AlertBanner(
            alerts = state.alerts,
            download = state.download,
            onFix = onFix,
            onDownloadModel = { metered { wifi -> onDownloadModel(null, wifi) } },
            onCancelDownload = onCancelDownload,
        )

        // What the last delete or retry had to say, above the ledger and whatever is in it — an
        // empty list included, since the delete that emptied it is what it is about. A warning and
        // not red (docs/09 "Accessibility": red is a failed state or a delete), and a tap puts it away.
        state.message?.let { message ->
            MessageBanner(
                text = message.text(),
                onDismiss = onDismissMessage,
                modifier = Modifier.padding(start = Space.m, end = Space.m, bottom = Space.s),
            )
        }
        // docs/03 "Naming rules": a failed import leaves no row, so this is where it says why.
        state.importFailure?.let { code ->
            MessageBanner(
                text = stringResource(R.string.import_failed) + "\n" + coreMessage(code).text(),
                onDismiss = onDismissImport,
                modifier = Modifier.padding(start = Space.m, end = Space.m, bottom = Space.s),
            )
        }

        // docs/09 "Search": the results take the ledger's place while the field has text.
        if (state.query.isNotBlank()) {
            HairLine()
            SearchResults(state.hits, onOpenHit, Modifier.fillMaxWidth().weight(1f),
                lengths = state.items.associate { it.recordingId to it.durationSec })
            return@Column
        }

        if (state.loading || state.items.isEmpty()) {
            HairLine()
            Refreshable(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                // A list of one page-sized item, so the pull reaches it however little is in it.
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        if (state.loading) {
                            Column(
                                modifier = Modifier.fillParentMaxSize().padding(Space.l),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(stringResource(R.string.list_loading), style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
                            }
                        } else {
                            // docs/09 screen principle 8: no button here — the tab bar right below already says Record.
                            EmptyList(
                                title = stringResource(R.string.jobs_empty),
                                hint = stringResource(R.string.jobs_empty_hint),
                                modifier = Modifier.fillParentMaxSize(),
                            )
                        }
                    }
                }
            }
            return@Column
        }

        // docs/09 screen principle 2: every length and every code the ledger can show, measured — so
        // `NEEDS_AUTH` and `00:09` both fit at a font scale of 1.3, and neither column moves from
        // row to row as the list is scrolled. The headings are measured with them, in the wider of
        // the two styles, because a heading that no longer fits is the same bug.
        val columns = ledgerColumns(
            lengths = listOf(
                stringResource(R.string.jobs_column_length),
                EMPTY_LENGTH,
            ) + state.items.mapNotNull { item -> item.durationSec?.let { duration(it) } },
            codes = BADGE_CODES,
        )

        // Measured columns cost the title what they take, and on a narrow screen at a large font
        // size that is everything: the shape is decided once here, from the width the ledger
        // actually has, and the header and every row take it together.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val layout = ledgerLayout(maxWidth, columns)
            Column(Modifier.fillMaxSize()) {
                LedgerHeader(
                    time = stringResource(R.string.jobs_column_time),
                    title = stringResource(R.string.jobs_column_title),
                    length = stringResource(R.string.jobs_column_length),
                    status = stringResource(R.string.jobs_column_status),
                    columns = columns,
                    layout = layout,
                )

                Refreshable(
                    refreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    // A row that arrives on top while the list is at its top — an import — is shown, not
                    // left above the first row the list was anchored on.
                    val ledger = rememberLazyListState()
                    val newest = state.items.firstOrNull()?.recordingId
                    LaunchedEffect(newest) { if (ledger.firstVisibleItemIndex <= 1) ledger.scrollToItem(0) }
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = ledger) {
                        items(state.items, key = { it.recordingId }) { item ->
                            val filling = state.fills.any { it.recordingId == item.recordingId }
                            val organizing = item.recordingId in state.organizing
                            val title = item.title ?: stringResource(R.string.jobs_untitled)
                            // UX decisions of 2026-10-08: the transcript's first words under the title,
                            // and nothing — no line, no space — for a row without one here.
                            val preview = state.previews[item.recordingId].orEmpty()
                            LedgerRow(
                                modifier = Modifier.testTag("recording-${item.recordingId}"),
                                date = ledgerColumn(item.startedAt, LEDGER_DATE),
                                time = ledgerColumn(item.startedAt, LEDGER_TIME),
                                title = title,
                                subtitle = preview,
                                length = item.durationSec?.let { duration(it) } ?: EMPTY_LENGTH,
                                status = item.badge(filling, organizing),
                                columns = columns,
                                announce = stringResource(
                                    R.string.jobs_row_description,
                                    if (preview.isEmpty()) title else "$title, $preview",
                                    startedAt(item.startedAt, R.string.jobs_started_at_format),
                                    item.durationSec?.let { duration(it) } ?: EMPTY_LENGTH,
                                    label(item, filling, organizing),
                                ),
                                // docs/09 screen principle 2: the row is the recording's detail — parts, the
                                // transcript when there is one, and what it needs — on every shell alike. A
                                // recording still being written to is a thing to look at as well, and the detail
                                // says so itself (`DetailState.writing`).
                                onClick = { onOpenDetail(item) },
                                layout = layout,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * docs/10 "Failures the user can fix, and their notices": the same lines the notifications carry, at the top
 * of the list where the recordings they are about are. One row per reason, however many jobs are
 * behind it, and the row is the way to the screen that fixes it.
 */
@Composable
private fun AlertBanner(
    alerts: List<JobAlert>,
    download: ModelDownloadState,
    onFix: (JobAlert) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    if (alerts.isEmpty()) return
    val palette = blueprint
    Column(Modifier.fillMaxWidth().background(palette.surface).testTag("alert-banner")) {
        HairLine()
        alerts.forEach { alert ->
            // The recordings wait for the model, and the fix is the download itself, right here.
            // While it runs, the line is its progress and the button its cancel.
            if (alert.reason == AlertReason.LOCAL_MODEL_REQUIRED) {
                AlertLine(
                    code = alert.reason.code,
                    lines = {
                        if (download.active) {
                            ModelDownloadLines(download, download.info)
                        } else {
                            Text(stringResource(alert.reason.label), style = MaterialTheme.typography.bodyMedium, color = palette.warningInk)
                            Text(
                                stringResource(R.string.alert_waiting, alert.count),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textMuted,
                            )
                        }
                    },
                ) {
                    if (download.active) {
                        BlueprintButton(stringResource(R.string.processing_cancel_download), onCancelDownload,
                            tone = ButtonTone.QUIET, modifier = Modifier.testTag("alert-cancel-download"))
                    } else {
                        BlueprintButton(modelDownloadLabel(download.info), onDownloadModel,
                            modifier = Modifier.testTag("alert-download-model"))
                    }
                }
                return@forEach
            }
            if (alert.reason == AlertReason.NEEDS_AUTH) {
                AlertLine(
                    code = null,
                    lines = {
                        Text(
                            stringResource(R.string.alert_uploads_waiting, alert.count),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textMuted,
                        )
                    },
                ) {
                    BlueprintButton(
                        stringResource(R.string.drive_connect),
                        onClick = { onFix(alert) },
                        modifier = Modifier.testTag("alert-fix"),
                    )
                }
                return@forEach
            }
            val reason = stringResource(alert.reason.label)
            val waiting = stringResource(R.string.alert_waiting, alert.count)
            AlertLine(
                code = alert.reason.code,
                // A failure's `Failed` in the failure's own red, as its row says it; a wait in the warning.
                tone = if (alert.reason.wait) BadgeTone.WARNING else BadgeTone.DANGER,
                // docs/09 "Accessibility": one node with a sentence in it, not a reason, a count and a code
                // read out as three separate things (the same rule as [LedgerRow]).
                description = "$reason $waiting",
                onClick = { onFix(alert) },
                lines = {
                    // Red is for a failure; a wait (Drive out of space) is the warning its badge is.
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = if (alert.reason.wait) palette.warningInk else palette.danger)
                    Text(waiting, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                },
            ) {
                // docs/10: "A tap goes to the screen that can fix it". The row goes there when it is pressed,
                // but only the button says *where* — a line that is tappable without saying what
                // the tap opens is a fix the user has to guess at.
                BlueprintButton(
                    stringResource(alert.reason.fix.label),
                    onClick = { onFix(alert) },
                    modifier = Modifier.testTag("alert-fix"),
                )
            }
        }
        HairLine()
    }
}

/**
 * docs/03: a pull is the user asking for everything — this phone's list and what the other devices
 * have put in Drive. docs/09 "Motion": the platform's own pull indicator, the one place the system's
 * spinner is allowed (2026-09-29): it follows the finger and settles back without a word, so a pull
 * that Drive answers at once does not flash a line of text on and off.
 */
@Composable
private fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) { content() }
}

/**
 * docs/03 "Deleting in the app": one recording, two answers about Drive, and the default is the one that
 * can be undone — the files in Drive are the user's own and something downstream may already have
 * read the folder. What is still only on this phone is said first, because that is the part of the
 * deletion nothing anywhere else can give back.
 *
 * A recording another device uploaded has no local half to keep, so there is no choice to offer:
 * deleting it is deleting the Drive folder, and the dialog says so and asks that (docs/03
 * "Recordings from other devices").
 *
 * Asked from the detail's More → Delete, and drawn by the List tab over whichever of its pages is up.
 */
@Composable
internal fun DeleteDialog(
    request: DeleteRequest,
    onCancel: () -> Unit,
    onDelete: (DeleteRequest, Boolean) -> Unit,
) {
    var deleteDrive by rememberSaveable(request.recordingId) { mutableStateOf(false) }
    BlueprintDialog(
        title = stringResource(
            R.string.delete_title,
            request.title ?: stringResource(R.string.jobs_untitled),
        ),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                tone = ButtonTone.QUIET,
                minWidth = MinTouch,
            )
            BlueprintButton(
                label = stringResource(R.string.action_delete),
                onClick = { onDelete(request, request.remote || deleteDrive) },
                tone = ButtonTone.DANGER,
                modifier = Modifier.testTag("delete-confirm"),
                minWidth = MinTouch,
            )
        },
    ) {
        if (request.remote) {
            // What happens, said plainly: the red is the Delete button's (docs/09 "Accessibility").
            BlueprintDialogText(
                stringResource(R.string.delete_remote_body),
                modifier = Modifier.testTag("delete-remote"),
            )
            return@BlueprintDialog
        }
        // That some of it is only here, not how many parts — a count of files is not the user's
        // to read (docs/09 screen principle 2).
        if (request.unuploaded > 0) {
            BlueprintDialogText(
                stringResource(if (request.folder) R.string.delete_unuploaded_folder else R.string.delete_unuploaded),
                tone = DialogTone.DANGER,
                modifier = Modifier.testTag("delete-unuploaded"),
            )
        }
        // Nothing ever reached Drive, so there is no second answer to choose between.
        if (!request.hasDriveFolder) return@BlueprintDialog
        BlueprintRadioRow(
            label = stringResource(R.string.delete_local_only),
            selected = !deleteDrive,
            onSelect = { deleteDrive = false },
            modifier = Modifier.testTag("delete-local-only"),
        )
        BlueprintRadioRow(
            label = stringResource(if (request.folder) R.string.delete_with_folder else R.string.delete_with_drive),
            selected = deleteDrive,
            onSelect = { deleteDrive = true },
            modifier = Modifier.testTag("delete-with-drive"),
        )
    }
}

/**
 * docs/09 screen principle 2: the badge is the state as a code, and the code is the same word the core and
 * the logs use. What it *means* is the translated [label], which is what a screen reader hears.
 */
fun ItemState.badge(): LedgerStatus = when (this) {
    ItemState.RECORDING -> LedgerStatus("REC", BadgeTone.DANGER)
    ItemState.IMPORTING -> LedgerStatus("IMPORTING", BadgeTone.ACCENT, busy = true)
    // docs/03 "Recordings from other devices": a code says what is happening, not where — an upload on another
    // device is the same word as one of this phone's own.
    ItemState.RECEIVING -> LedgerStatus("RECEIVING", BadgeTone.ACCENT)
    ItemState.REMOTE_UPLOADING -> LedgerStatus("UPLOADING", BadgeTone.ACCENT)
    ItemState.REMOTE_TRANSCRIBING -> TRANSCRIBING_BADGE
    ItemState.NO_JOB -> LedgerStatus("DONE", BadgeTone.SUCCESS)
    ItemState.PENDING -> LedgerStatus("PENDING", BadgeTone.NEUTRAL)
    ItemState.RUNNING -> LedgerStatus("UPLOADING", BadgeTone.ACCENT)
    ItemState.WAITING -> LedgerStatus("RETRY", BadgeTone.WARNING)
    ItemState.DONE -> LedgerStatus("DONE", BadgeTone.SUCCESS)
    ItemState.FAILED -> LedgerStatus("FAILED", BadgeTone.DANGER)
    ItemState.NEEDS_AUTH -> LedgerStatus("NEEDS_AUTH", BadgeTone.NEUTRAL)
    ItemState.NEEDS_CONSENT -> LedgerStatus("NEEDS_CONSENT", BadgeTone.WARNING)
    ItemState.NEEDS_MODEL -> LedgerStatus("NEEDS_MODEL", BadgeTone.WARNING)
    ItemState.NEEDS_SPACE -> LedgerStatus("NO_SPACE", BadgeTone.WARNING)
    ItemState.SKIPPED_SHORT -> LedgerStatus("SKIPPED", BadgeTone.NEUTRAL)
}

/**
 * docs/08 "Polling · status": a job parked while a provider transcribes is waiting on someone else, not on
 * a retry timer, so it is its own code rather than the `RETRY` its `WAITING` status would give it —
 * the same code the desktop's ledger shows (`windows/.../Ledger.LedgerStates`).
 *
 * docs/09 screen principle 2: [filling] — the recording is in `observeFills()` — says `Filling in`, read after `REC`
 * and `RECEIVING` and ahead of everything else; [organizing] — in `notes.observeOrganizing()` — says `Organizing` on a
 * row that would say `Done`. The header count and the row's actions still go by the job.
 */
fun JobItem.badge(filling: Boolean = false, organizing: Boolean = false): LedgerStatus = when {
    state == ItemState.RECORDING || state == ItemState.RECEIVING -> state.badge()
    filling -> FILLING_BADGE
    organizing && state.badge().code == "DONE" -> ORGANIZING_BADGE
    waitingMinutes != null || localRunning -> TRANSCRIBING_BADGE
    localPending -> ItemState.PENDING.badge()
    // docs/03 "Storage location": waiting for the folder to be picked again, not for a retry timer.
    waitsForFolder() -> FOLDER_BADGE
    else -> state.badge()
}

private val TRANSCRIBING_BADGE = LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT)

/** docs/08 "Titles and speaker names": ChatGPT is filling in the title or the names — work in hand, the loader turning. */
private val FILLING_BADGE = LedgerStatus("FILLING", BadgeTone.ACCENT, busy = true)

/** docs/08 "Organizing": a done recording whose notes are being organized now — the detail's `Organizing…` on its row. */
private val ORGANIZING_BADGE = LedgerStatus("ORGANIZING", BadgeTone.ACCENT, busy = true)

/** A `WAITING` job held up by the local folder — the warning a retry wait already is, in its own word. */
private val FOLDER_BADGE = LedgerStatus("WAITING_FOLDER", BadgeTone.WARNING)

/**
 * The tone of the sentence a recording's detail gives for its state: red only for a failure, the warning
 * of a wait's own badge (`NEEDS_MODEL`, `NEEDS_CONSENT`, `NO_SPACE`, `RETRY`), and quiet otherwise.
 */
internal fun ItemState.reasonTone(): BadgeTone = when (badge().tone) {
    BadgeTone.DANGER -> BadgeTone.DANGER
    BadgeTone.WARNING -> BadgeTone.WARNING
    BadgeTone.NEUTRAL, BadgeTone.ACCENT, BadgeTone.SUCCESS -> BadgeTone.NEUTRAL
}

/**
 * Every code the ledger's last column can hold, so the column can be as wide as the widest of them
 * rather than as wide as whatever happens to be on screen — the width must not change as rows
 * arrive. Derived from [badge] itself, so a state added later is measured without anyone
 * remembering to come back here.
 */
internal val BADGE_CODES: List<String> =
    ItemState.entries.map { it.badge().code } + TRANSCRIBING_BADGE.code + FOLDER_BADGE.code + FILLING_BADGE.code + ORGANIZING_BADGE.code

/** docs/09 screen principle 2: "14 · 2 waiting · 1 failed" — a count of none is not said ("3 · 1 waiting"). */
internal fun headerCounts(total: Int, waiting: Int, failed: Int, waitingWord: (Int) -> String, failedWord: (Int) -> String): String =
    listOfNotNull(total.toString(), waiting.takeIf { it > 0 }?.let(waitingWord), failed.takeIf { it > 0 }?.let(failedWord)).joinToString(" · ")

/**
 * The two counts the header carries, so "14 · 2 waiting · 1 failed" is one glance. A recording on
 * its way here — from the watch, or from another device's upload — is one the list is waiting for
 * (docs/03 "Recordings from other devices"); one another device is transcribing has already arrived. A job parked
 * until the user allows a transfer or downloads the speech model is waiting too, not failed — the
 * iPhone's `Recents.summary` counts the same states.
 */
// NEEDS_AUTH is a wait: its badge says "Waiting for Drive", and it goes on by itself once Drive is connected.
fun ItemState.waiting(): Boolean =
    this == ItemState.PENDING || this == ItemState.WAITING || this == ItemState.NEEDS_AUTH ||
        this == ItemState.NEEDS_CONSENT || this == ItemState.NEEDS_MODEL ||
        this == ItemState.RECEIVING || this == ItemState.REMOTE_UPLOADING || this == ItemState.IMPORTING

/**
 * The row's own count: a transcription on this device — queued, running, or held back for heat —
 * is waited for whatever the job's status says while it runs (`RUNNING` included).
 */
fun JobItem.waiting(): Boolean = localPending || state.waiting()

/**
 * docs/09 screen principle 2: a recording whose file is still being written — by the recorder, an
 * import or a transfer from the watch — so its Delete waits ([deleteReason]). An upload is not one:
 * the core stops it before deleting (docs/03 "Deleting in the app"), and neither is another
 * device's, whose folder the dialog takes from Drive (docs/03 "Recordings from other devices").
 */
fun ItemState.inFlight(): Boolean =
    this == ItemState.RECORDING || this == ItemState.IMPORTING || this == ItemState.RECEIVING

/**
 * The row's own answer: a transcription on this device is not a file being written, and deleting
 * its recording is something the core does for it — it stops the transcription first
 * (`LocalTranscriptionService.deleting`). The iPhone offers Delete there too (`RecentItem.canDelete`).
 */
fun JobItem.inFlight(): Boolean = !localPending && state.inFlight()

/**
 * Why the detail's More → Delete waits, or null when it does not ([inFlight]): `Still recording` the way every other
 * item says it, or the row's own state word — `Importing`, `Receiving from the watch` (the desktops' rule).
 */
internal fun JobItem.deleteReason(): Int? = when {
    !inFlight() -> null
    state == ItemState.RECORDING -> R.string.detail_still_recording
    state == ItemState.IMPORTING -> R.string.job_state_importing
    else -> R.string.job_state_receiving
}

/**
 * docs/03 "Storage location": a `WAITING` job held up because the local folder cannot be used — waiting
 * for the folder to be picked again, not for a retry timer, so the row says so instead of "when".
 */
internal fun JobItem.waitsForFolder(): Boolean =
    state == ItemState.WAITING && error?.let(CoreMessageRef::parse)?.message == CoreMessage.FOLDER_UNAVAILABLE

fun ItemState.failing(): Boolean =
    this == ItemState.FAILED || this == ItemState.NEEDS_SPACE ||
        this == ItemState.SKIPPED_SHORT

/**
 * A `WAITING` job says when, because "waiting" on its own reads like "stuck" — and while a provider
 * is transcribing there is no "when" to give, only how long it has been (docs/08 "Polling · status").
 * A row the badge says `Filling in` or `Organizing` for is heard so too ([JobItem.badge]).
 */
@Composable
private fun label(item: JobItem, filling: Boolean, organizing: Boolean): String = if (item.badge(filling, organizing).code == "FILLING") {
    stringResource(R.string.badge_filling)
} else if (item.badge(filling, organizing).code == "ORGANIZING") {
    stringResource(R.string.badge_organizing)
} else if (item.localPending) stringResource(
    when {
        item.localRunning -> R.string.processing_local_running
        item.localCooling -> R.string.processing_local_cooling
        else -> R.string.processing_local_pending
    }
) else when (item.state) {
    ItemState.RECORDING -> stringResource(R.string.job_state_recording)
    ItemState.IMPORTING -> stringResource(R.string.job_state_importing)
    ItemState.RECEIVING -> stringResource(R.string.job_state_receiving)
    ItemState.REMOTE_UPLOADING -> stringResource(R.string.job_state_remote_uploading)
    ItemState.REMOTE_TRANSCRIBING -> stringResource(R.string.job_state_remote_transcribing)
    ItemState.NO_JOB -> stringResource(R.string.job_state_done)
    ItemState.PENDING -> stringResource(R.string.job_state_pending)
    ItemState.RUNNING -> stringResource(R.string.job_state_running)
    ItemState.WAITING -> item.waitingMinutes
        ?.let { stringResource(R.string.job_waiting_transcription, it) }
        ?: item.takeIf { it.waitsForFolder() }?.let { stringResource(R.string.job_state_waiting_folder) }
        ?: item.nextRunAt
            ?.let { stringResource(R.string.job_state_waiting_in, remaining(it)) }
        ?: stringResource(R.string.job_state_waiting)

    ItemState.DONE -> stringResource(R.string.job_state_done)
    ItemState.FAILED -> stringResource(R.string.job_state_failed)
    ItemState.NEEDS_AUTH -> stringResource(R.string.job_state_needs_auth)
    ItemState.NEEDS_CONSENT -> stringResource(R.string.job_state_needs_consent)
    ItemState.NEEDS_MODEL -> stringResource(R.string.job_state_needs_model)
    ItemState.NEEDS_SPACE -> stringResource(R.string.job_state_needs_space)
    ItemState.SKIPPED_SHORT -> stringResource(R.string.job_state_skipped_short)
}

@Composable
private fun remaining(at: kotlin.time.Instant): String {
    val seconds = (at - Clock.System.now()).inWholeSeconds
    return when {
        seconds <= 0 -> stringResource(R.string.retry_soon)
        seconds < 60 -> stringResource(R.string.retry_in_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(R.string.retry_in_minutes, (seconds / 60).toInt())
        else -> stringResource(R.string.retry_in_hours, (seconds / 3600).toInt())
    }
}

/** docs/07 rule 7: the pattern is a resource, so a Korean phone reads "8월 28일 15:04". */
@Composable
private fun startedAt(isoUtc: String, pattern: Int): String {
    val format = stringResource(pattern)
    return runCatching {
        DateTimeFormatter.ofPattern(format).format(Instant.parse(isoUtc).atZone(ZoneId.systemDefault()))
    }.getOrDefault(isoUtc)
}

/**
 * docs/09 screen principle 2: the ledger's time column is a fixed-width pattern, so it is the same two lines
 * in every language — a locale that says the day first would not line up under the heading. The
 * spoken date the row announces is the locale's own words, and that one stays [startedAt].
 */
internal fun ledgerColumn(isoUtc: String, format: DateTimeFormatter): String = runCatching {
    format.format(Instant.parse(isoUtc).atZone(ZoneId.systemDefault()))
}.getOrDefault(isoUtc)

internal val LEDGER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
internal val LEDGER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private const val EMPTY_LENGTH = "--:--"

/** The ledger's length column: `MM:SS`, or `HH:MM:SS` from an hour (docs/09 "Typography"). */
private fun duration(seconds: Double): String = clock(seconds.toLong())

/**
 * docs/09 "Search": the field at the top of the list — the settings' own [BlueprintField], with a leading search
 * glyph and a clear at the end once there is something to clear. The placeholder ends in "…" where a long
 * language does not fit — never cut mid-word.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    BlueprintField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.search_placeholder),
        fieldModifier = Modifier.testTag("search-field"),
        leading = { GlyphIcon(Glyph.SEARCH, blueprint.textMuted, size = 20.dp) },
        trailing = if (query.isNotEmpty()) {
            { GlyphButton(Glyph.CLOSE, stringResource(R.string.transcript_clear_search), { onQuery("") }, Modifier.testTag("search-clear")) }
        } else {
            null
        },
    )
}

/**
 * docs/09 "Search": one row per recording found — when, the title with its matches tinted, up to two lines
 * of the first transcript match, and that match's time at the end; then the notes' first matching line. A tap
 * opens it there.
 */
@Composable
private fun SearchResults(
    hits: List<SearchHit>?,
    onOpen: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
    /** Each recording's length, which picks the format of its match's time (docs/09 "Typography"). */
    lengths: Map<String, Double?> = emptyMap(),
) {
    val palette = blueprint
    if (hits == null) {
        Box(modifier)
        return
    }
    if (hits.isEmpty()) {
        EmptyList(stringResource(R.string.search_empty), stringResource(R.string.search_empty_hint), modifier)
        return
    }
    val tint = palette.accent.copy(alpha = MATCH_TINT)
    LazyColumn(modifier.testTag("search-results")) {
        items(hits, key = { it.recordingId }) { hit ->
            val title = hit.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.jobs_untitled)
            val snippet = hit.snippets.firstOrNull()
            Column(Modifier.fillMaxWidth().background(palette.surface)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRing(palette)
                        .clickable(role = Role.Button) { onOpen(hit) }
                        .padding(horizontal = Space.m, vertical = Space.sm)
                        .testTag("hit-${hit.recordingId}"),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Column(Modifier.width(62.dp)) {
                        Text(ledgerColumn(hit.startedAt, LEDGER_DATE), style = mono.small, color = palette.textMuted, maxLines = 1)
                        Text(ledgerColumn(hit.startedAt, LEDGER_TIME), style = mono.small, color = palette.textMuted, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(tinted(title, if (hit.title.isNullOrBlank()) emptyList() else hit.titleRanges, tint, palette.text),
                            style = MaterialTheme.typography.titleSmall, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        snippet?.let {
                            Text(tinted(it.text, it.ranges, tint, palette.text), style = MaterialTheme.typography.bodySmall, color = palette.textMuted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        // The notes' first matching line, after what it is: "Summary · …".
                        hit.notes?.let {
                            val label = stringResource(R.string.notes_title) + " · "
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = palette.textMuted)) { append(label) }
                                append(tinted(it.text, it.ranges, tint, palette.text))
                            }, style = MaterialTheme.typography.bodySmall, color = palette.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("hit-notes-${hit.recordingId}"))
                        }
                        // And the memo's: "Notes · …".
                        hit.memo?.let {
                            val label = stringResource(R.string.memo_title) + " · "
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = palette.textMuted)) { append(label) }
                                append(tinted(it.text, it.ranges, tint, palette.text))
                            }, style = MaterialTheme.typography.bodySmall, color = palette.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("hit-memo-${hit.recordingId}"))
                        }
                    }
                    snippet?.let {
                        Text(clock(it.atSec.toLong(), lengths[hit.recordingId]?.toLong()), style = mono.small, color = palette.textMuted, maxLines = 1)
                    }
                }
                HairLine()
            }
        }
    }
}

/** [text] with [ranges] on the accent's 16 % — a match, in the body colour [ink] even inside a quieter line. */
private fun tinted(text: String, ranges: List<SearchRange>, tint: Color, ink: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        val end = (range.offset + range.length).coerceAtMost(text.length)
        if (range.offset in 0 until end) addStyle(SpanStyle(background = tint, color = ink), range.offset, end)
    }
}
