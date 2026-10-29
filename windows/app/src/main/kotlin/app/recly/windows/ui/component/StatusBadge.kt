package app.recly.windows.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import app.recly.windows.ui.theme.Space
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.ui.theme.LocalReduceMotion
import app.recly.windows.ui.theme.Motion
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.forWord
import app.recly.windows.ui.theme.mono

/**
 * docs/09 screen principle 2: state is never colour alone. The tone picks the colour, the code is the text,
 * and a reader who sees neither hue gets the same answer from the letters.
 */
enum class BadgeTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

/**
 * The code and its tone — what [LedgerRow] shows in its last column. [busy] turns the loader in front of the code.
 * The code stays internal (logs, tests); what the badge says is [label] — the translated [word] once a
 * language is applied (the UX decisions of 2026-10-08: words instead of codes).
 */
data class LedgerStatus(
    val code: String,
    val tone: BadgeTone,
    val label: String = code,
    val busy: Boolean = false,
    val word: Str? = null,
)

/**
 * A square badge: 1dp of the tone (2dp in high contrast), the word in monospace — or in the sans face for
 * Hangul, Kana and Han ([forWord]) — on the surface.
 * The letters are drawn in an ink that clears WCAG AA on both the surface and the page — which for
 * amber is not the same colour as the border (see [app.recly.windows.ui.theme.BlueprintColors]).
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
        // docs/09 "Component measurements": words wider than the column ([statusColumnWidth]'s cap) wrap inside the
        // badge, between words and centred — no word is ever cut short. Korean words carry their joiners
        // (`joinKoreanWords`), so they break between words as well.
        Text(
            text = status.label,
            style = mono.small.forWord(status.label),
            color = status.tone.ink(),
            textAlign = TextAlign.Center,
            // On the sans 12's line box, as a compact control's label is: Consolas' own box is 2 shorter than Segoe UI's,
            // and a time drawn at the badge's size must be one height with it (docs/09 "Component measurements").
            modifier = Modifier.onLabelBaseline(MaterialTheme.typography.labelMedium),
        )
    }
}

/**
 * docs/09 "Motion": a badge whose state changes in place fades, [Motion.BADGE_FADE_MS] on the standard easing — the one
 * transition a list row has — and with reduce motion the new state is simply there.
 */
@Composable
fun FadingStatusBadge(status: LedgerStatus, modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) {
        StatusBadge(status, modifier)
    } else {
        Crossfade(
            targetState = status,
            modifier = modifier,
            animationSpec = tween(durationMillis = Motion.BADGE_FADE_MS, easing = Motion.Standard),
        ) { swapped ->
            StatusBadge(swapped)
        }
    }
}

/** The badge's own inset either side of its code (docs/09 "Component measurements": 8 × 4). */
private val BADGE_PAD: Dp = Space.s

/**
 * The cap on the status column (UX decisions of 2026-10-08, the phones' rule): as wide as the old code `TRANSCRIBING`
 * in the badge's font, so the title column is never narrower than it was when the badges said codes. A word wider than
 * that wraps in its badge instead of widening the column.
 */
private const val STATUS_CAP_SAMPLE = "TRANSCRIBING"

/**
 * The ledger's status column, measured rather than guessed: the widest of every [labels] the ledger
 * can show, in the badge's own type — so the fluid scale and the language come with it, and no badge
 * is ever cut to fit (`NEEDS_MODEL` was, at a fixed 92dp) — up to the cap of [STATUS_CAP_SAMPLE].
 * Android's `statusColumnWidth` is the same rule.
 */
@Composable
fun statusColumnWidth(labels: List<String>): Dp {
    // Each word measured in the face it is drawn in ([forWord]): a Korean word is not as wide in sans as in mono.
    val style = mono.small
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(labels, style, density) {
        val px = labels.maxOfOrNull { sample ->
            // The spaces in front of a busy badge's word stand in for its loader ([ledgerBadgeLabels]), so they
            // stay the mono face's width whatever face the word takes.
            val word = sample.trimStart()
            val text = buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = style.fontFamily)) { append(sample.substring(0, sample.length - word.length)) }
                append(word)
            }
            measurer.measure(text, style.forWord(word), maxLines = 1).size.width
        } ?: 0
        // A dp of slack: a word measured to the pixel must not wrap on the pixel the layout rounds away.
        with(density) { px.toDp() } + 1.dp
    }
    val cap = textColumnWidth(listOf(STATUS_CAP_SAMPLE), style) + 1.dp
    return statusColumn(widest, blueprint.line, cap)
}

/**
 * The widest code up to [cap], plus the badge's padding and border on each side. Up to the cap no word wraps; past it
 * the badge takes a second line.
 */
internal fun statusColumn(widest: Dp, line: Dp, cap: Dp = Dp.Infinity): Dp = minOf(widest, cap) + (BADGE_PAD + line) * 2

/** The widest of [samples] on one line, in [style]. */
@Composable
fun textColumnWidth(samples: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(samples, style, density) {
        val widest = samples.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}

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
