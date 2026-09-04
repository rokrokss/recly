package app.recly.windows.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.coreMessage
import app.recly.windows.i18n.coreMessageDetail
import app.recly.windows.i18n.text
import app.recly.windows.jobs.RecentItem
import app.recly.windows.ui.component.BadgeTone
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LedgerStatus
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import recly.core.job.StepReport

/**
 * docs/09 화면 원칙 2: every row state is a code *and* a tone, so a reader who cannot tell the hues
 * apart still gets the answer from the letters. The codes are the ones the core and the logs already
 * use, and they are the phone's (`android/.../JobsScreen.badge`) — one product, one vocabulary.
 *
 * The map is keyed by what [app.recly.windows.jobs.Recents.stateLabel] produces, which is the only
 * thing that ever reaches the ledger.
 */
val LedgerStates: Map<Str, LedgerStatus> = mapOf(
    Str.STATUS_RECORDING to LedgerStatus("REC", BadgeTone.DANGER),
    Str.STATE_NO_WORKFLOW to LedgerStatus("NO_JOB", BadgeTone.NEUTRAL),
    Str.STATUS_WAITING to LedgerStatus("PENDING", BadgeTone.NEUTRAL),
    Str.STATE_UPLOADING to LedgerStatus("UPLOADING", BadgeTone.ACCENT),
    Str.STATE_RETRY_WAIT to LedgerStatus("RETRY", BadgeTone.WARNING),
    // docs/08 "폴링 · 상태": a job parked while a provider transcribes is waiting on someone else,
    // not on a retry timer, so it is its own code.
    Str.STATE_WAITING_TRANSCRIPTION to LedgerStatus("TRANSCRIBING", BadgeTone.ACCENT),
    Str.STATE_DONE to LedgerStatus("DONE", BadgeTone.SUCCESS),
    Str.STATE_FAILED to LedgerStatus("FAILED", BadgeTone.DANGER),
    Str.STATUS_SIGN_IN_NEEDED to LedgerStatus("NEEDS_AUTH", BadgeTone.WARNING),
    // docs/10 "Drive 용량 초과": a job parked because Drive is full — nothing is lost and nothing
    // retries, and the banner beside it is what offers the storage page.
    Str.STATE_NO_SPACE to LedgerStatus("NO_SPACE", BadgeTone.WARNING),
    Str.STATE_TOO_SHORT to LedgerStatus("SKIPPED", BadgeTone.NEUTRAL),
)

/** The state as a badge. Anything the map does not know is still a code, never a blank cell. */
fun UiMessage.ledgerStatus(): LedgerStatus =
    LedgerStates[(this as? UiMessage.Res)?.key] ?: LedgerStatus("UNKNOWN", BadgeTone.NEUTRAL)

/**
 * docs/07 §5 · docs/08 "오류": what the core last said about this row — the sentence translated, the
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
    val error = item.lastError ?: return
    val palette = blueprint
    Text(
        coreMessage(error).text(strings),
        style = MaterialTheme.typography.bodySmall,
        color = palette.danger,
    )
    coreMessageDetail(error)?.let {
        Text(it, style = mono.small, color = palette.textMuted)
    }
    if (onCheckKey != null) CheckKeyButton(item, strings, onCheckKey)
}

/**
 * docs/09 화면 원칙 2 lists `키를 확인하세요` among a row's actions, in the one line the others are in —
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
     * docs/09: `00:12:34` — the recording timer from the moment the recorder said it had started,
     * and a transcript turn's offset from the start of the recording. Hours are not wrapped at 24;
     * a recording is not a clock.
     */
    fun elapsed(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /**
     * docs/09 화면 원칙 2: the ledger's 길이 column — `42:10`, or `1:02:33` past the hour, which is
     * what the phone and the Mac write in the same column (`LedgerFormat.length`, `duration`).
     *
     * A recording that has not been finalized has no length yet, and [NO_LENGTH] is what says so: a
     * blank cell reads like a value that went missing rather than like one that is not in yet.
     */
    fun length(seconds: Double?): String {
        if (seconds == null || seconds < 0) return NO_LENGTH
        val total = seconds.toLong()
        return if (total >= 3600) {
            "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        } else {
            "%02d:%02d".format(total / 60, total % 60)
        }
    }

    /** The same three shells write it the same way — it is a clock face, not a sentence. */
    const val NO_LENGTH: String = "--:--"

    /** An unparseable timestamp is shown as it stands — it is data, and hiding it helps nobody. */
    private fun format(startedAt: String, formatter: DateTimeFormatter): String = runCatching {
        formatter.format(Instant.parse(startedAt).atZone(ZoneId.systemDefault()))
    }.getOrDefault(startedAt)
}
