package app.recly.windows.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextDirection
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.coreMessage
import app.recly.windows.i18n.coreMessageDetail
import app.recly.windows.i18n.finalCoreMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
import app.recly.windows.jobs.RecentItem
import app.recly.windows.ui.component.BadgeTone
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LedgerStatus
import app.recly.windows.ui.component.ink
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import recly.core.job.StepReport

/**
 * docs/09 screen principle 2: every row state is a code *and* a tone, so a reader who cannot tell the hues
 * apart still gets the answer from the letters. The codes are the ones the core and the logs already
 * use, and they are the phone's (`android/.../JobsScreen.badge`) — one product, one vocabulary. What the
 * badge *says* is the code's word in the app's language (the UX decisions of 2026-10-08): `Done`,
 * `Waiting for Drive`, never `DONE` or `NEEDS_AUTH`. The code stays the state's internal name.
 *
 * The map is keyed by what [app.recly.windows.jobs.Recents.stateLabel] produces, which is the only
 * thing that ever reaches the ledger.
 */
val LedgerStates: Map<Str, LedgerStatus> = mapOf(
    Str.STATUS_RECORDING to LedgerStatus("REC", BadgeTone.DANGER, word = Str.STATUS_RECORDING),
    Str.STATUS_WAITING to LedgerStatus("PENDING", BadgeTone.NEUTRAL, word = Str.STATUS_WAITING),
    Str.STATE_UPLOADING to LedgerStatus("UPLOADING", BadgeTone.ACCENT, word = Str.STATE_UPLOADING),
    Str.STATE_RETRY_WAIT to LedgerStatus("RETRY", BadgeTone.WARNING, word = Str.BADGE_RETRY),
    // docs/08 "Polling · status": a job parked while a provider transcribes is waiting on someone else,
    // not on a retry timer, so it is its own code.
    Str.STATE_WAITING_TRANSCRIPTION to LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT, word = Str.BADGE_TRANSCRIBING),
    Str.PROCESSING_LOCAL_RUNNING to LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT, word = Str.BADGE_TRANSCRIBING),
    Str.PROCESSING_LOCAL_PENDING to LedgerStatus("PENDING", BadgeTone.NEUTRAL, word = Str.STATUS_WAITING),
    // docs/03 "Recordings from other devices" (2026-09-04): work in flight somewhere else — the watch sending, or
    // another device uploading or transcribing. The accent of every "something is happening", and the
    // same codes the local states wear: to a reader the news is the news, and *where* it is happening
    // is what the row's sentence says (`STATE_REMOTE_*`).
    Str.STATE_RECEIVING to LedgerStatus("RECEIVING", BadgeTone.ACCENT, word = Str.BADGE_RECEIVING),
    Str.STATE_REMOTE_UPLOADING to LedgerStatus("UPLOADING", BadgeTone.ACCENT, word = Str.STATE_UPLOADING),
    Str.STATE_REMOTE_TRANSCRIBING to LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT, word = Str.BADGE_TRANSCRIBING),
    Str.STATE_DONE to LedgerStatus("DONE", BadgeTone.SUCCESS, word = Str.STATE_DONE),
    Str.STATE_FAILED to LedgerStatus("FAILED", BadgeTone.DANGER, word = Str.STATE_FAILED),
    // A wait for the Drive connection, never red (docs/09 "Accessibility"): `Waiting for Drive`.
    Str.STATUS_SIGN_IN_NEEDED to LedgerStatus("NEEDS_AUTH", BadgeTone.NEUTRAL, word = Str.DRIVE_PENDING),
    // docs/10 "Drive out of space": a job parked because Drive is full — nothing is lost and nothing
    // retries, and the banner beside it is what offers the storage page.
    Str.STATE_CONSENT_REQUIRED to LedgerStatus("NEEDS_CONSENT", BadgeTone.WARNING, word = Str.BADGE_NEEDS_CONSENT),
    Str.STATE_NO_SPACE to LedgerStatus("NO_SPACE", BadgeTone.WARNING, word = Str.BADGE_STORAGE_FULL),
    // Waiting for the on-device speech model: like consent, a wait the user ends, not a failure.
    Str.STATE_NEEDS_MODEL to LedgerStatus("NEEDS_MODEL", BadgeTone.WARNING, word = Str.BADGE_NEEDS_MODEL),
    // docs/03 "Storage location": the local folder cannot be reached, and picking it again is what carries
    // the job on — a wait, worn in the warning tone like the model's, never FAILED.
    Str.STATE_WAITING_FOLDER to LedgerStatus("WAITING", BadgeTone.WARNING, word = Str.BADGE_WAITING_FOLDER),
    Str.STATE_TOO_SHORT to LedgerStatus("SKIPPED", BadgeTone.NEUTRAL, word = Str.STATE_TOO_SHORT),
    // docs/03 "Naming rules": a file being turned into parts — work in hand, with the loader turning.
    Str.STATE_IMPORTING to LedgerStatus("IMPORTING", BadgeTone.ACCENT, busy = true, word = Str.STATE_IMPORTING),
)

/**
 * docs/09 screen principle 2: every label a ledger badge can wear in [strings]' language — each state's word
 * and `Unknown` — which is what the status column is measured against, so none of them is ever cut to
 * fit.
 */
fun ledgerBadgeLabels(strings: Strings): List<String> =
    LedgerStates.keys.flatMap { key ->
        val status = key.message().ledgerStatus(strings)
        // Two spaces stand in for the loader a busy badge turns in front of its code — about its 12dp.
        listOfNotNull(status.label, "  ${status.label}".takeIf { status.busy })
    } + strings[Str.BADGE_UNKNOWN]

/**
 * The state as a badge, its word in [strings]' language (or its code, with none). Anything the map
 * does not know is still a badge — `Unknown` — never a blank cell.
 */
fun UiMessage.ledgerStatus(strings: Strings? = null): LedgerStatus {
    val status = LedgerStates[(this as? UiMessage.Res)?.key]
        ?: LedgerStatus(UNKNOWN_STATE, BadgeTone.NEUTRAL, word = Str.BADGE_UNKNOWN)
    return status.worded(strings)
}

/** The badge with its word put in, when there is a language to put it in. */
fun LedgerStatus.worded(strings: Strings?): LedgerStatus {
    val word = word ?: return this
    return if (strings == null) this else copy(label = strings[word])
}

/** The code of a state this ledger does not know. */
private const val UNKNOWN_STATE = "UNKNOWN"

/**
 * docs/07 §5 · docs/08 "Errors": what the core last said about this row — the sentence translated, the
 * diagnostic that came with it never, on its own line in monospace under it. [CheckKeyButton] under
 * it with an `onCheckKey`, for a surface with no actions line of its own to put it in.
 *
 * Written once and drawn twice: the tray popup's expanded row and the recordings window's sidebar
 * are two views of the same recording, and a row that named a failure in one place and stayed
 * silent in the other was two answers to one question (the Mac's popover draws both from `row`).
 * Nothing at all for a row that is not stuck.
 */
@Composable
fun FailureReason(item: RecentItem, strings: Strings, onCheckKey: (() -> Unit)? = null) {
    if (item.jobStatus == recly.core.job.JobStatus.NEEDS_AUTH) return
    // Waiting for the speech model is not a failure: the state and the row's Download say it.
    if (item.jobStatus == recly.core.job.JobStatus.NEEDS_MODEL) return
    val error = item.lastError ?: return
    val palette = blueprint
    // A job the queue gave up on will not try again by itself, so its line does not say it will (2026-10-10).
    val failed = item.jobStatus == recly.core.job.JobStatus.FAILED
    Text(
        (if (failed) finalCoreMessage(error) else coreMessage(error)).text(strings),
        style = MaterialTheme.typography.bodySmall,
        color = reasonTone(item.jobStatus).ink(),
    )
    coreMessageDetail(error)?.let {
        Text(it, style = mono.small.copy(textDirection = TextDirection.Ltr), color = palette.textMuted)
    }
    if (onCheckKey != null) CheckKeyButton(item, strings, onCheckKey)
}

/**
 * docs/09 "Every state is color + text": red says failure and nothing else. A job the queue gave up on
 * ([JobStatus.FAILED]) says why in the failure colour; one that is waiting — for consent, a sign-in,
 * space, the model, or its next attempt — says it in the warning tone its badge wears.
 */
fun reasonTone(status: recly.core.job.JobStatus?): BadgeTone =
    if (status == recly.core.job.JobStatus.FAILED) BadgeTone.DANGER else BadgeTone.WARNING

/**
 * docs/09 screen principle 2 lists `Check the key` among a row's actions, in the one line the others are in —
 * so a surface that has such a line draws it there itself (the popup's expanded row, and the Mac's
 * `MenuPopover.actions` in the same order). The recordings window's sidebar has no actions line, and
 * for it the button stays under the reason, which is what [FailureReason] does with an `onCheckKey`.
 *
 * Nothing at all unless the key is what the core refused (`AUTH_REJECTED`).
 */
@Composable
fun CheckKeyButton(item: RecentItem, strings: Strings, onClick: () -> Unit) {
    if (!StepReport.needsKey(item.lastError ?: return)) return
    BlueprintButton(
        label = strings[Str.REASON_CHECK_KEY],
        onClick = onClick,
        tone = ButtonTone.QUIET,
    )
}

/**
 * The ledger's two-line time column and its spoken form. docs/09 puts the columns in monospace, so
 * they are fixed-width patterns; the sentence a screen reader hears is a date, so it goes through
 * the locale's own formatter (docs/07 rule 7).
 */
object LedgerFormat {
    private val DATE = DateTimeFormatter.ofPattern("MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun date(startedAt: String): String = format(startedAt, DATE)

    fun time(startedAt: String): String = format(startedAt, TIME)

    /** The spoken one: "Aug 27, 10:00" / "8월 27일 오전 10:00", from the app's own language. */
    fun spoken(startedAt: String, locale: Locale): String =
        format(startedAt, DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

    /**
     * `00:12:34`, hours always written — what a screen reader is told about a moment of a recording
     * (a transcript time, a highlight). The UX decisions of 2026-10-08 changed what is *drawn* ([clock])
     * and left what is spoken as it was. Hours are not wrapped at 24; a recording is not a clock.
     */
    fun elapsed(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /**
     * A time as it is drawn (the UX decisions of 2026-10-08): `MM:SS` under an hour, `HH:MM:SS` from one.
     * [spanMillis] picks the format — inside one recording's screen it is that recording's whole length,
     * so every time there has one width; a live timer passes none and follows its own time
     * (`59:59` → `01:00:00`).
     */
    fun clock(millis: Long, spanMillis: Long? = null): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        val span = maxOf(seconds, (spanMillis ?: 0) / 1000)
        return if (span >= HOUR) {
            "%02d:%02d:%02d".format(seconds / HOUR, (seconds / 60) % 60, seconds % 60)
        } else {
            "%02d:%02d".format(seconds / 60, seconds % 60)
        }
    }

    /** [clock] for a moment [atSec] of a recording [totalSec] long — null when its length is not known. */
    fun clock(atSec: Double, totalSec: Double?): String =
        clock((atSec * 1000).toLong(), totalSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() })

    /**
     * docs/09 screen principle 2: the ledger's length column — `42:10`, or `01:02:33` from the hour (2026-10-08),
     * the same rule as every other drawn time ([clock]).
     *
     * A recording that has not been finalized has no length yet, and [NO_LENGTH] is what says so: a
     * blank cell reads like a value that went missing rather than like one that is not in yet.
     */
    fun length(seconds: Double?): String {
        if (seconds == null || seconds < 0) return NO_LENGTH
        return clock(seconds.toLong() * 1000)
    }

    /** The widest thing [length] writes, which the column is measured against. */
    const val LONGEST_LENGTH: String = "00:00:00"

    private const val HOUR = 3600L

    /** The same three shells write it the same way — it is a clock face, not a sentence. */
    const val NO_LENGTH: String = "--:--"

    /** An unparseable timestamp is shown as it stands — it is data, and hiding it helps nobody. */
    private fun format(startedAt: String, formatter: DateTimeFormatter): String = runCatching {
        formatter.format(Instant.parse(startedAt).atZone(ZoneId.systemDefault()))
    }.getOrDefault(startedAt)
}
