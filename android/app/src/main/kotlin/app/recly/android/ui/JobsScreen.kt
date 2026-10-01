@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.core.coreMessage
import app.recly.android.core.coreMessageDetail
import app.recly.android.ui.component.BadgeTone
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintRadioRow
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ActionFlow
import app.recly.android.ui.component.LedgerHeader
import app.recly.android.ui.component.LedgerRow
import app.recly.android.ui.component.LedgerStatus
import app.recly.android.ui.component.LedgerTitleInset
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.ProcessingState
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.StatusBadge
import app.recly.android.ui.component.ink
import app.recly.android.ui.component.ledgerColumns
import app.recly.android.ui.component.ledgerLayout
import app.recly.android.ui.component.statusColumnWidth
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
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
import recly.core.job.StepReport

/**
 * docs/11 A4, drawn as docs/09 화면 원칙 2 asks: a ledger. One row per recording — when, what, how
 * long, and the state as a code — and the detail is behind the row rather than in front of it.
 */
@Composable
fun JobsScreen(
    state: JobsUiState,
    onRetry: (JobItem) -> Unit,
    onConfirmDelete: (JobItem) -> Unit,
    onCancelDelete: () -> Unit,
    onDelete: (DeleteRequest, Boolean) -> Unit,
    onOpenDetail: (JobItem) -> Unit,
    /** docs/08 AUTH_REJECTED: "check the key" is only useful with the key's settings behind it. */
    onCheckKey: (JobItem) -> Unit,
    /** docs/10: the banner is not a notice, it is the way to the screen that fixes the thing. */
    onFix: (JobAlert) -> Unit,
    /** The speech model download: a waiting row's step language, or null (the banner) for the saved settings'. */
    onDownloadModel: (language: String?, onWifi: Boolean) -> Unit,
    onCancelDownload: () -> Unit,
    /** docs/03: the pull — this phone's list and what the other devices have put in Drive. */
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    /** Changes when the List tab is tapped again on the list: the ledger goes back to every row closed. */
    collapse: Int = 0,
) {
    val palette = blueprint
    var expanded by rememberSaveable(collapse) { mutableStateOf<String?>(null) }
    val metered = rememberMeteredGate(state.download.info?.modelBytes)

    state.confirmDelete?.let { request ->
        DeleteDialog(request = request, onCancel = onCancelDelete, onDelete = onDelete)
    }

    Column(modifier.fillMaxSize()) {
        // The tab is "List"; the screen is what is in it, as on the iPhone.
        ScreenHeader(
            title = stringResource(R.string.jobs_title),
            meta = stringResource(
                R.string.jobs_summary,
                state.items.size,
                state.items.count { it.waiting() },
                state.items.count { it.state.failing() },
            ),
        )

        AlertBanner(
            alerts = state.alerts,
            download = state.download,
            onFix = onFix,
            onDownloadModel = { metered { wifi -> onDownloadModel(null, wifi) } },
            onCancelDownload = onCancelDownload,
        )

        // What the last delete or retry had to say, above the ledger and whatever is in it — an
        // empty list included, since the delete that emptied it is what it is about. A warning and
        // not red (docs/09 "접근성": red is a failed state or a delete), and a tap puts it away.
        state.message?.let { message ->
            MessageBanner(
                text = message.text(),
                onDismiss = onDismissMessage,
                modifier = Modifier.padding(start = Space.m, end = Space.m, bottom = Space.s),
            )
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
                        Column(
                            modifier = Modifier.fillParentMaxSize().padding(Space.l),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (state.loading) {
                                Text(stringResource(R.string.list_loading), color = palette.textMuted)
                            } else {
                                // docs/09 화면 원칙 8: no button here — the tab bar right below already says Record.
                                Text(
                                    stringResource(R.string.jobs_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.text,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    stringResource(R.string.jobs_empty_hint),
                                    modifier = Modifier.padding(top = Space.xs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textMuted,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            return@Column
        }

        // docs/09 화면 원칙 2: every length and every code the ledger can show, measured — so
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
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.recordingId }) { item ->
                            val open = expanded == item.recordingId
                            LedgerRow(
                                modifier = Modifier.testTag("recording-${item.recordingId}"),
                                date = ledgerColumn(item.startedAt, LEDGER_DATE),
                                time = ledgerColumn(item.startedAt, LEDGER_TIME),
                                title = item.title ?: stringResource(R.string.jobs_untitled),
                                subtitle = "",
                                length = item.durationSec?.let { duration(it) } ?: EMPTY_LENGTH,
                                status = item.badge(),
                                columns = columns,
                                announce = stringResource(
                                    R.string.jobs_row_description,
                                    item.title ?: stringResource(R.string.jobs_untitled),
                                    startedAt(item.startedAt, R.string.jobs_started_at_format),
                                    item.durationSec?.let { duration(it) } ?: EMPTY_LENGTH,
                                    label(item),
                                ),
                                expanded = open,
                                toggleLabel = stringResource(
                                    if (open) R.string.jobs_row_collapse else R.string.jobs_row_expand,
                                ),
                                onClick = { expanded = if (open) null else item.recordingId },
                                layout = layout,
                            )
                            if (open) {
                                ExpandedRow(
                                    item = item,
                                    action = state.action,
                                    onRetry = { onRetry(item) },
                                    onDelete = { onConfirmDelete(item) },
                                    onOpenDetail = { onOpenDetail(item) },
                                    onCheckKey = { onCheckKey(item) },
                                    onFixAuth = { onFix(JobAlert(AlertReason.NEEDS_AUTH, 1)) },
                                    download = state.download,
                                    onDownloadModel = { metered { wifi -> onDownloadModel(item.modelLanguage, wifi) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * docs/09 화면 원칙 2: what the row has to say about itself — why it is where it is — and the two or
 * three things the user can do about it.
 */
@Composable
private fun ExpandedRow(
    item: JobItem,
    action: ProcessingState,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenDetail: () -> Unit,
    onCheckKey: () -> Unit,
    onFixAuth: () -> Unit,
    download: ModelDownloadState,
    onDownloadModel: () -> Unit,
) {
    val palette = blueprint
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            // Indented to the ledger's own title column, so the expansion sits under the recording
            // it belongs to rather than under a number somebody picked.
            .padding(start = LedgerTitleInset, end = Space.m, top = Space.s, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // docs/08 "폴링 · 상태": a transcription in flight has no "when", only how long it has been
        // waiting — the badge's RETRY would otherwise read as "stuck".
        item.waitingMinutes?.let { minutes ->
            Text(
                stringResource(R.string.job_waiting_transcription, minutes),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
            )
        }

        // docs/07 §5: `last_error` is a core message key, not a sentence — and a row an older build
        // wrote is prose, which `coreMessage` shows as it stands. Whatever diagnostic rode along
        // with the key is not translated and goes under it, in monospace: for a docs/08 "오류" that
        // is the provider's own words, which are what a support question quotes.
        // A recording waiting for the model says nothing of it while the download runs: the banner
        // carries the progress, and "not downloaded yet" would be wrong a minute later.
        item.error?.takeIf { item.state != ItemState.NEEDS_AUTH && !(item.state == ItemState.NEEDS_MODEL && download.active) }?.let { error ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    coreMessage(error).text(),
                    style = MaterialTheme.typography.bodySmall,
                    // Red is for a failure; a wait says what it waits for in its badge's own tone.
                    color = item.state.reasonTone().ink(),
                )
                coreMessageDetail(error)?.let { detail ->
                    Text(detail, style = mono.small, color = palette.textMuted)
                }
            }
        }

        // docs/09 화면 원칙 2: Delete ends the last line; a recording in flight has none to offer.
        ActionFlow(
            modifier = Modifier.fillMaxWidth(),
            trailing = if (item.inFlight()) {
                null
            } else {
                {
                    BlueprintButton(
                        label = stringResource(R.string.action_delete),
                        onClick = onDelete,
                        tone = ButtonTone.DANGER,
                        modifier = Modifier.testTag("recording-delete-${item.recordingId}"),
                        minWidth = MinTouch,
                    )
                }
            },
        ) {
            // The one thing that carries a waiting recording on, first and filled. Nothing while
            // the download runs: the banner has its progress and its cancel.
            if (item.state == ItemState.NEEDS_MODEL && !download.active) {
                BlueprintButton(
                    label = modelDownloadLabel(download.info),
                    onClick = onDownloadModel,
                    tone = ButtonTone.PRIMARY,
                    modifier = Modifier.testTag("download-model"),
                )
            }
            item.link?.let { link ->
                BlueprintButton(
                    label = stringResource(R.string.jobs_open_drive),
                    onClick = { context.openUrl(link) },
                )
            }
            when (item.state) {
                // A retry cannot pass while Drive is not granted; the banner's fix is what
                // unparks these jobs (docs/06 Android), so the row's Retry runs that instead.
                ItemState.NEEDS_AUTH -> {
                    BlueprintButton(stringResource(R.string.action_retry), onClick = onFixAuth)
                }

                // docs/10 "Drive 용량 초과": nothing here retries on its own, and the only thing
                // that changes the answer is on Google's storage page.
                ItemState.NEEDS_SPACE -> {
                    BlueprintButton(
                        label = stringResource(R.string.jobs_open_storage),
                        onClick = { context.openUrl(DRIVE_STORAGE_URL) },
                        modifier = Modifier.testTag("open-storage"),
                    )
                    ProcessingButton(stringResource(R.string.action_retry), action, onRetry)
                }

                ItemState.FAILED ->
                    ProcessingButton(stringResource(R.string.action_retry), action, onRetry)

                // docs/10: a `WAITING` job is sitting out a backoff after a failed attempt, and
                // the user who has just fixed what failed (a URL, a key, a plan) should not have
                // to wait it out — `retry()` makes the next attempt now (Z Fold7, 2026-09-04).
                // Not while a provider is transcribing: that wait is on someone else's clock.
                ItemState.WAITING ->
                    if (item.waitingMinutes == null && !item.localPending) {
                        ProcessingButton(stringResource(R.string.action_retry), action, onRetry)
                    }

                // A `PENDING` job is due already. A recording with no job, and one too short to
                // have earned one, offer no upload. The three that are happening elsewhere
                // (docs/03 "다른 기기의 녹음") have nothing here to retry either — the work is not
                // this device's to make due.
                // A recording waiting for the model has its download first in the row, above.
                ItemState.NEEDS_CONSENT, ItemState.NEEDS_MODEL, ItemState.PENDING, ItemState.NO_JOB, ItemState.SKIPPED_SHORT,
                ItemState.RECORDING, ItemState.RUNNING, ItemState.DONE,
                ItemState.RECEIVING, ItemState.REMOTE_UPLOADING, ItemState.REMOTE_TRANSCRIBING,
                -> Unit
            }
            // docs/08 AUTH_REJECTED: the key is kept in the processing settings, so that is where this goes.
            if (StepReport.needsKey(item.error)) {
                BlueprintButton(
                    label = stringResource(R.string.job_reason_check_key),
                    onClick = onCheckKey,
                    modifier = Modifier.testTag("check-key"),
                )
            }
            // docs/09 화면 원칙 2: the row opens the recording's detail — parts, and the transcript
            // when there is one — on every shell alike, so it is offered on every row. A recording
            // still being written to is a thing to look at as well, and the detail says so itself
            // rather than being hidden for it (`DetailState.writing`).
            // The row's most used action, so it is not left the narrowest: "상세" is two letters.
            BlueprintButton(
                label = stringResource(R.string.detail_open),
                onClick = onOpenDetail,
                    modifier = Modifier.widthIn(min = DetailMinWidth).testTag("open-detail"),
                )
        }
    }
}

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the same lines the notifications carry, at the top
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
                                pluralStringResource(R.plurals.alert_waiting, alert.count, alert.count),
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.alert_uploads_waiting, alert.count),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textMuted,
                    )
                    BlueprintButton(
                        stringResource(R.string.drive_connect),
                        onClick = { onFix(alert) },
                        modifier = Modifier.testTag("alert-fix"),
                    )
                }
                return@forEach
            }
            val reason = stringResource(alert.reason.label)
            val waiting = pluralStringResource(R.plurals.alert_waiting, alert.count, alert.count)
            AlertLine(
                code = alert.reason.code,
                // docs/09 "접근성": one node with a sentence in it, not a reason, a count and a code
                // read out as three separate things (the same rule as [LedgerRow]).
                description = "$reason $waiting",
                onClick = { onFix(alert) },
                lines = {
                    // Red is for a failure; a wait (Drive out of space) is the warning its badge is.
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = if (alert.reason.wait) palette.warningInk else palette.danger)
                    Text(waiting, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                },
            ) {
                // docs/10: "탭하면 고칠 수 있는 화면으로 간다". The row goes there when it is pressed,
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
 * One reason: what it is, how many recordings are behind it, the code, and what to press — the
 * iPhone's `AlertLine`. The code and the button take at most half the row and fold onto a second
 * line there when they do not fit side by side, so a long code such as
 * `LOCAL_TRANSCRIPTION_UNAVAILABLE` never squeezes the sentence to a word per line.
 */
@Composable
private fun AlertLine(
    code: String,
    lines: @Composable ColumnScope.() -> Unit,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.m)) {
        // Half the row, or the code's own width where that is more: a code is never cut.
        val trailing = maxOf(maxWidth / 2, statusColumnWidth(listOf(code)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .then(
                        if (description != null) {
                            Modifier.semantics(mergeDescendants = true) { contentDescription = description }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = lines,
            )
            FlowRow(
                modifier = Modifier.widthIn(max = trailing).padding(vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(LedgerStatus(code, BadgeTone.WARNING))
                action()
            }
        }
    }
}

/**
 * What the last delete or retry had to say, as the iPhone's warning banner: the sentence in the
 * warning's ink on the surface, inside the warning's edge. A tap puts it away.
 */
@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .background(palette.surface, shape)
            .border(palette.line, palette.warning, shape)
            .clickable(onClickLabel = stringResource(R.string.action_close), onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("message"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.warningInk)
    }
}

/**
 * docs/03: a pull is the user asking for everything — this phone's list and what the other devices
 * have put in Drive. docs/09 "모션": the platform's own pull indicator, the one place the system's
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
 * docs/03 "앱에서 지우기": one recording, two answers about Drive, and the default is the one that
 * can be undone — the files in Drive are the user's own and something downstream may already have
 * read the folder. What is still only on this phone is said first, because that is the part of the
 * deletion nothing anywhere else can give back.
 *
 * A recording another device uploaded has no local half to keep, so there is no choice to offer:
 * deleting it is deleting the Drive folder, and the dialog says so and asks that (docs/03
 * "다른 기기의 녹음").
 */
@Composable
private fun DeleteDialog(
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
            // What happens, said plainly: the red is the Delete button's (docs/09 "접근성").
            BlueprintDialogText(
                stringResource(R.string.delete_remote_body),
                modifier = Modifier.testTag("delete-remote"),
            )
            return@BlueprintDialog
        }
        // That some of it is only here, not how many parts — a count of files is not the user's
        // to read (docs/09 화면 원칙 2).
        if (request.unuploaded > 0) {
            BlueprintDialogText(
                stringResource(R.string.delete_unuploaded),
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
            label = stringResource(R.string.delete_with_drive),
            selected = deleteDrive,
            onSelect = { deleteDrive = true },
            modifier = Modifier.testTag("delete-with-drive"),
        )
    }
}

/**
 * docs/09 화면 원칙 2: the badge is the state as a code, and the code is the same word the core and
 * the logs use. What it *means* is the translated [label], which is what a screen reader hears.
 */
fun ItemState.badge(): LedgerStatus = when (this) {
    ItemState.RECORDING -> LedgerStatus("REC", BadgeTone.DANGER)
    // docs/03 "다른 기기의 녹음": a code says what is happening, not where — an upload on another
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
 * docs/08 "폴링 · 상태": a job parked while a provider transcribes is waiting on someone else, not on
 * a retry timer, so it is its own code rather than the `RETRY` its `WAITING` status would give it —
 * the same code the desktop's ledger shows (`windows/.../Ledger.LedgerStates`).
 */
fun JobItem.badge(): LedgerStatus =
    if (waitingMinutes != null || localRunning) TRANSCRIBING_BADGE else if (localPending) ItemState.PENDING.badge() else state.badge()

/** The row's Details button, as wide as the detail's own Play button. */
private val DetailMinWidth = 120.dp

private val TRANSCRIBING_BADGE = LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT)

/**
 * The tone of the sentence an expanded row gives for its state: red only for a failure, the warning
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
    ItemState.entries.map { it.badge().code } + TRANSCRIBING_BADGE.code

/**
 * The two counts the header carries, so "14 · 2 waiting · 1 failed" is one glance. A recording on
 * its way here — from the watch, or from another device's upload — is one the list is waiting for
 * (docs/03 "다른 기기의 녹음"); one another device is transcribing has already arrived. A job parked
 * until the user allows a transfer or downloads the speech model is waiting too, not failed — the
 * iPhone's `Recents.summary` counts the same states.
 */
// NEEDS_AUTH is a wait: its badge says "Upload waiting", and it goes on by itself once Drive is connected.
fun ItemState.waiting(): Boolean =
    this == ItemState.PENDING || this == ItemState.WAITING || this == ItemState.NEEDS_AUTH ||
        this == ItemState.NEEDS_CONSENT || this == ItemState.NEEDS_MODEL ||
        this == ItemState.RECEIVING || this == ItemState.REMOTE_UPLOADING

/**
 * The row's own count: a transcription on this device — queued, running, or held back for heat —
 * is waited for whatever the job's status says while it runs (`RUNNING` included).
 */
fun JobItem.waiting(): Boolean = localPending || state.waiting()

/**
 * docs/09 화면 원칙 2: a recording something is doing to it right now, so there is nothing on the
 * row to offer. Deleting one would be pulling the file out from under a recorder, a transfer or
 * another device's upload (docs/03 "다른 기기의 녹음").
 */
fun ItemState.inFlight(): Boolean =
    this == ItemState.RECORDING || this == ItemState.RUNNING ||
        this == ItemState.RECEIVING || this == ItemState.REMOTE_UPLOADING

/**
 * The row's own answer: a transcription on this device is not an upload, and deleting its
 * recording is something the core does for it — it stops the transcription first
 * (`LocalTranscriptionService.deleting`). The iPhone offers Delete there too (`RecentItem.canDelete`).
 */
fun JobItem.inFlight(): Boolean = !localPending && state.inFlight()

fun ItemState.failing(): Boolean =
    this == ItemState.FAILED || this == ItemState.NEEDS_SPACE ||
        this == ItemState.SKIPPED_SHORT

/**
 * A `WAITING` job says when, because "waiting" on its own reads like "stuck" — and while a provider
 * is transcribing there is no "when" to give, only how long it has been (docs/08 "폴링 · 상태").
 */
@Composable
private fun label(item: JobItem): String = if (item.localPending) stringResource(
    when {
        item.localRunning -> R.string.processing_local_running
        item.localCooling -> R.string.processing_local_cooling
        else -> R.string.processing_local_pending
    }
) else when (item.state) {
    ItemState.RECORDING -> stringResource(R.string.job_state_recording)
    ItemState.RECEIVING -> stringResource(R.string.job_state_receiving)
    ItemState.REMOTE_UPLOADING -> stringResource(R.string.job_state_remote_uploading)
    ItemState.REMOTE_TRANSCRIBING -> stringResource(R.string.job_state_remote_transcribing)
    ItemState.NO_JOB -> stringResource(R.string.job_state_done)
    ItemState.PENDING -> stringResource(R.string.job_state_pending)
    ItemState.RUNNING -> stringResource(R.string.job_state_running)
    ItemState.WAITING -> item.waitingMinutes
        ?.let { stringResource(R.string.job_waiting_transcription, it) }
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
 * docs/09 화면 원칙 2: the ledger's time column is a fixed-width pattern, so it is the same two lines
 * in every language — a locale that says the day first would not line up under the heading. The
 * spoken date the row announces is the locale's own words, and that one stays [startedAt].
 */
internal fun ledgerColumn(isoUtc: String, format: DateTimeFormatter): String = runCatching {
    format.format(Instant.parse(isoUtc).atZone(ZoneId.systemDefault()))
}.getOrDefault(isoUtc)

internal val LEDGER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
internal val LEDGER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private const val EMPTY_LENGTH = "--:--"

private fun duration(seconds: Double): String {
    val total = seconds.toLong()
    return if (total >= 3600) {
        "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    } else {
        "%02d:%02d".format(total / 60, total % 60)
    }
}
