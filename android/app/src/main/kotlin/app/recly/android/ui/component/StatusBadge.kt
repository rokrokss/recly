package app.recly.android.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.recly.android.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/**
 * docs/09 screen principle 2: state is never colour alone. The tone picks the colour, the word is the text,
 * and a reader who sees neither hue gets the same answer from the letters.
 */
enum class BadgeTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

/**
 * The code and its tone — what [LedgerRow] shows in its last column. [busy] turns the square loader
 * beside the code, for work running with no percentage to show (`IMPORTING`).
 */
data class LedgerStatus(val code: String, val tone: BadgeTone, val busy: Boolean = false)

/**
 * A square badge: 1dp of the tone, the state's word ([badgeLabel]) in monospace, on the surface.
 * The letters are drawn in an ink that clears WCAG AA on both the surface and the page — which for
 * amber is not the same colour as the border (see `BlueprintColors.warningInk`).
 */
@Composable
fun StatusBadge(status: LedgerStatus, modifier: Modifier = Modifier) {
    val palette = blueprint
    Row(
        modifier = modifier
            .border(palette.line, status.tone.line(), RoundedCornerShape(Radius.badge))
            .padding(horizontal = BADGE_PAD, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.busy) BlueprintLoader(status.tone.ink())
        // UX decisions of 2026-10-08: a word wider than the column ([statusColumnWidth]'s cap) takes a
        // second line inside its badge, centred and never ellipsized — between words, and inside one
        // only when that one word alone is wider than the column. Phrase breaking keeps a Korean word
        // whole, where the default would break between any two syllables.
        Text(
            text = badgeLabel(status.code),
            style = mono.small.copy(lineBreak = BADGE_LINE_BREAK),
            color = status.tone.ink(),
            textAlign = TextAlign.Center,
        )
    }
}

/** What the badge puts between its border and its letters, on each side. */
private val BADGE_PAD = Space.s

/** Lines break between words — Korean ones too — and inside a word only when nothing else fits. */
private val BADGE_LINE_BREAK = LineBreak(
    strategy = LineBreak.Strategy.Simple,
    strictness = LineBreak.Strictness.Normal,
    wordBreak = LineBreak.WordBreak.Phrase,
)

/**
 * The cap on the status column (UX decisions of 2026-10-08, the same rule on the iPhone): as wide as the
 * old code `TRANSCRIBING` needed in the badge's font, so the title column is never narrower than it was
 * when the badges said codes. A word wider than that wraps in its badge instead of widening the column.
 */
private const val STATUS_CAP_SAMPLE = "TRANSCRIBING"

/**
 * How wide the ledger's status column is: the widest word the ledger can show *in the current
 * language* — what each code is drawn as — measured in the style the badge draws, up to the cap of
 * [STATUS_CAP_SAMPLE], plus what the badge adds around it. No state is ever ellipsized, in any language,
 * at any scale: a word past the cap wraps inside its badge.
 */
@Composable
fun statusColumnWidth(codes: List<String>): Dp = statusColumn(
    // A dp of slack on both: a word measured to the pixel must not wrap on the pixel the layout rounds away.
    widest = textColumnWidth(codes.map { badgeLabel(it) }, mono.small, padding = 1.dp),
    line = blueprint.line,
    cap = textColumnWidth(listOf(STATUS_CAP_SAMPLE), mono.small, padding = 1.dp),
)

/**
 * The rule, without a screen to measure on: the widest word up to [cap], plus the badge's own padding
 * and border on each side. Up to the cap no word wraps; past it the badge takes a second line.
 */
internal fun statusColumn(widest: Dp, line: Dp, cap: Dp = Dp.Infinity): Dp = minOf(widest, cap) + (BADGE_PAD + line) * 2

@Composable
fun BadgeTone.ink(): Color = when (this) {
    BadgeTone.NEUTRAL -> blueprint.textMuted
    BadgeTone.ACCENT -> blueprint.accent
    BadgeTone.SUCCESS -> blueprint.success
    BadgeTone.WARNING -> blueprint.warningInk
    BadgeTone.DANGER -> blueprint.danger
}

/** The border, which is a graphic and so may use the documented amber rather than its dark ink. */
@Composable
private fun BadgeTone.line(): Color =
    if (this == BadgeTone.WARNING) blueprint.warning else ink()

/**
 * UX decisions of 2026-10-08: the badge says the state in a word of the app's language. The code stays
 * what the screens, the tests and the logs hold; a code with no word of its own (a banner's failure
 * reason) is still drawn as the code.
 */
@Composable
internal fun badgeLabel(code: String): String = badgeWord(code)?.let { stringResource(it) } ?: code

/** The word a badge [code] is drawn as, or null for a code the table has no word for. */
@StringRes
internal fun badgeWord(code: String): Int? = when (code) {
    "DONE" -> R.string.job_state_done
    "FAILED" -> R.string.job_state_failed
    "RETRY" -> R.string.badge_retrying
    "PENDING" -> R.string.job_state_pending
    "UPLOADING" -> R.string.job_state_running
    "RECEIVING" -> R.string.badge_receiving
    "TRANSCRIBING" -> R.string.badge_transcribing
    "IMPORTING" -> R.string.job_state_importing
    "REC" -> R.string.job_state_recording
    "NEEDS_AUTH" -> R.string.drive_pending
    "NEEDS_CONSENT" -> R.string.badge_needs_permission
    "NEEDS_MODEL" -> R.string.badge_waiting_model
    "NEEDS_SPACE", "NO_SPACE" -> R.string.badge_storage_full
    "SKIPPED" -> R.string.job_state_skipped_short
    "WAITING_FOLDER" -> R.string.badge_waiting_folder
    else -> null
}
