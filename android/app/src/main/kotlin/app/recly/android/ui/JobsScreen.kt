@file:OptIn(ExperimentalTime::class, ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import app.recly.android.ui.component.LedgerHeader
import app.recly.android.ui.component.LedgerRow
import app.recly.android.ui.component.LedgerStatus
import app.recly.android.ui.component.LedgerTitleInset
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.ProcessingState
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.StatusBadge
import app.recly.android.ui.component.ledgerColumns
import app.recly.android.ui.component.ledgerLayout
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
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
    onSignIn: () -> Unit,
    onOpenDetail: (JobItem) -> Unit,
    /** docs/08 AUTH_REJECTED: "check the key" is only useful with the editor behind it. */
    onCheckKey: (JobItem) -> Unit,
    /** docs/10: the banner is not a notice, it is the way to the screen that fixes the thing. */
    onFix: (JobAlert) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    state.confirmDelete?.let { request ->
        DeleteDialog(request = request, onCancel = onCancelDelete, onDelete = onDelete)
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_jobs),
            meta = stringResource(
                R.string.jobs_summary,
                state.items.size,
                state.items.count { it.state.waiting() },
                state.items.count { it.state.failing() },
            ),
        )

        AlertBanner(state.alerts, onFix)

        if (state.loading || state.items.isEmpty()) {
            HairLine()
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(Space.l),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!state.loading) {
                    Text(
                        stringResource(R.string.jobs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center,
                    )
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

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    state.message?.let { message ->
                        item {
                            Text(
                                message.text(),
                                modifier = Modifier.padding(Space.m),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.danger,
                            )
                        }
                    }
                    items(state.items, key = { it.recordingId }) { item ->
                        val open = expanded == item.recordingId
                        LedgerRow(
                            date = ledgerColumn(item.startedAt, LEDGER_DATE),
                            time = ledgerColumn(item.startedAt, LEDGER_TIME),
                            title = item.title ?: stringResource(R.string.jobs_untitled),
                            subtitle = item.recordingId,
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
                                onSignIn = onSignIn,
                                onOpenDetail = { onOpenDetail(item) },
                                onCheckKey = { onCheckKey(item) },
                            )
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
    onSignIn: () -> Unit,
    onOpenDetail: () -> Unit,
    onCheckKey: () -> Unit,
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
        item.error?.let { error ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    coreMessage(error).text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                )
                coreMessageDetail(error)?.let { detail ->
                    Text(detail, style = mono.small, color = palette.textMuted)
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            item.link?.let { link ->
                BlueprintButton(
                    label = stringResource(R.string.jobs_open_drive),
                    onClick = { context.openUrl(link) },
                )
            }
            when (item.state) {
                ItemState.NEEDS_AUTH -> {
                    BlueprintButton(stringResource(R.string.jobs_sign_in), onSignIn)
                    ProcessingButton(stringResource(R.string.action_retry), action, onRetry)
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
                    if (item.waitingMinutes == null) {
                        ProcessingButton(stringResource(R.string.action_retry), action, onRetry)
                    }

                // A `PENDING` job is due already. A recording with no job, and one too short to
                // have earned one, offer no upload.
                ItemState.PENDING, ItemState.NO_JOB, ItemState.SKIPPED_SHORT,
                ItemState.RECORDING, ItemState.RUNNING, ItemState.DONE,
                -> Unit
            }
            // docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where this goes.
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
            BlueprintButton(
                label = stringResource(R.string.detail_open),
                onClick = onOpenDetail,
                modifier = Modifier.testTag("open-detail"),
            )
            if (item.state != ItemState.RECORDING && item.state != ItemState.RUNNING) {
                BlueprintButton(
                    label = stringResource(R.string.action_delete),
                    onClick = onDelete,
                    tone = ButtonTone.DANGER,
                )
            }
        }
    }
}

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the same lines the notifications carry, at the top
 * of the list where the recordings they are about are. One row per reason, however many jobs are
 * behind it, and the row is the way to the screen that fixes it.
 */
@Composable
private fun AlertBanner(alerts: List<JobAlert>, onFix: (JobAlert) -> Unit) {
    if (alerts.isEmpty()) return
    val palette = blueprint
    Column(Modifier.fillMaxWidth().background(palette.surface).testTag("alert-banner")) {
        HairLine()
        alerts.forEach { alert ->
            val reason = stringResource(alert.reason.label)
            val waiting = pluralStringResource(R.plurals.alert_waiting, alert.count, alert.count)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onFix(alert) })
                    // docs/09 "접근성": one node with a sentence in it, not a reason, a count and a
                    // code read out as three separate things (the same rule as [LedgerRow]).
                    .semantics(mergeDescendants = true) { contentDescription = "$reason $waiting" }
                    .padding(horizontal = Space.m, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = palette.danger)
                    Text(waiting, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                }
                StatusBadge(LedgerStatus(alert.reason.name, BadgeTone.WARNING))
            }
        }
        HairLine()
    }
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
            )
            BlueprintButton(
                label = stringResource(R.string.action_delete),
                onClick = { onDelete(request, request.remote || deleteDrive) },
                tone = ButtonTone.DANGER,
                modifier = Modifier.testTag("delete-confirm"),
            )
        },
    ) {
        if (request.remote) {
            BlueprintDialogText(
                stringResource(R.string.delete_remote_body),
                tone = DialogTone.DANGER,
                modifier = Modifier.testTag("delete-remote"),
            )
            return@BlueprintDialog
        }
        if (request.unuploaded > 0) {
            BlueprintDialogText(
                pluralStringResource(
                    R.plurals.delete_unuploaded,
                    request.unuploaded,
                    request.unuploaded,
                ),
                tone = DialogTone.DANGER,
                modifier = Modifier.testTag("delete-unuploaded"),
            )
        }
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
    ItemState.NO_JOB -> LedgerStatus("NO_JOB", BadgeTone.NEUTRAL)
    ItemState.PENDING -> LedgerStatus("PENDING", BadgeTone.NEUTRAL)
    ItemState.RUNNING -> LedgerStatus("UPLOADING", BadgeTone.ACCENT)
    ItemState.WAITING -> LedgerStatus("RETRY", BadgeTone.WARNING)
    ItemState.DONE -> LedgerStatus("DONE", BadgeTone.SUCCESS)
    ItemState.FAILED -> LedgerStatus("FAILED", BadgeTone.DANGER)
    ItemState.NEEDS_AUTH -> LedgerStatus("NEEDS_AUTH", BadgeTone.WARNING)
    ItemState.NEEDS_SPACE -> LedgerStatus("NO_SPACE", BadgeTone.WARNING)
    ItemState.SKIPPED_SHORT -> LedgerStatus("SKIPPED", BadgeTone.NEUTRAL)
}

/**
 * docs/08 "폴링 · 상태": a job parked while a provider transcribes is waiting on someone else, not on
 * a retry timer, so it is its own code rather than the `RETRY` its `WAITING` status would give it —
 * the same code the desktop's ledger shows (`windows/.../Ledger.LedgerStates`).
 */
fun JobItem.badge(): LedgerStatus =
    if (waitingMinutes != null) TRANSCRIBING_BADGE else state.badge()

private val TRANSCRIBING_BADGE = LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT)

/**
 * Every code the ledger's last column can hold, so the column can be as wide as the widest of them
 * rather than as wide as whatever happens to be on screen — the width must not change as rows
 * arrive. Derived from [badge] itself, so a state added later is measured without anyone
 * remembering to come back here.
 */
private val BADGE_CODES: List<String> =
    ItemState.entries.map { it.badge().code } + TRANSCRIBING_BADGE.code

/** The two counts the header carries, so "14 · 2 waiting · 1 failed" is one glance. */
fun ItemState.waiting(): Boolean =
    this == ItemState.PENDING || this == ItemState.WAITING || this == ItemState.NO_JOB

fun ItemState.failing(): Boolean =
    this == ItemState.FAILED || this == ItemState.NEEDS_AUTH || this == ItemState.NEEDS_SPACE ||
        this == ItemState.SKIPPED_SHORT

/**
 * A `WAITING` job says when, because "waiting" on its own reads like "stuck" — and while a provider
 * is transcribing there is no "when" to give, only how long it has been (docs/08 "폴링 · 상태").
 */
@Composable
private fun label(item: JobItem): String = when (item.state) {
    ItemState.RECORDING -> stringResource(R.string.job_state_recording)
    ItemState.NO_JOB -> stringResource(R.string.job_state_no_workflow)
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
private fun ledgerColumn(isoUtc: String, format: DateTimeFormatter): String = runCatching {
    format.format(Instant.parse(isoUtc).atZone(ZoneId.systemDefault()))
}.getOrDefault(isoUtc)

private val LEDGER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
private val LEDGER_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private const val EMPTY_LENGTH = "--:--"

private fun duration(seconds: Double): String {
    val total = seconds.toLong()
    return if (total >= 3600) {
        "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    } else {
        "%02d:%02d".format(total / 60, total % 60)
    }
}
