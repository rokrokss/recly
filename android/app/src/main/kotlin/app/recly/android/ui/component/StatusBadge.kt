package app.recly.android.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/**
 * docs/09 화면 원칙 2: state is never colour alone. The tone picks the colour, the code is the text,
 * and a reader who sees neither hue gets the same answer from the letters.
 */
enum class BadgeTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

/** The code and its tone — what [LedgerRow] shows in its last column. */
data class LedgerStatus(val code: String, val tone: BadgeTone)

/**
 * A square badge: 1dp of the tone, the code in monospace, on the surface.
 * The letters are drawn in an ink that clears WCAG AA on both the surface and the page — which for
 * amber is not the same colour as the border (see `BlueprintColors.warningInk`).
 */
@Composable
fun StatusBadge(status: LedgerStatus, modifier: Modifier = Modifier) {
    val palette = blueprint
    Text(
        text = status.code,
        modifier = modifier
            .border(palette.line, status.tone.line(), RoundedCornerShape(Radius.badge))
            .padding(horizontal = BADGE_PAD, vertical = Space.xs),
        style = mono.small,
        color = status.tone.ink(),
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

/** What the badge puts between its border and its letters, on each side. */
private val BADGE_PAD = Space.s

/**
 * How wide the ledger's status column has to be for none of [codes] to be clipped. `NEEDS_AUTH` is
 * ten characters of monospace and `UPLOADING` nine, and neither fitted the 76dp this column used to
 * be — at a font scale of 1.3 not even `NEEDS_AUTH`'s first eight did. So the column is the widest
 * code the ledger can show, measured in the style the badge draws, plus what the badge adds around
 * it: no code is ever ellipsed, in any language, at any scale.
 */
@Composable
fun statusColumnWidth(codes: List<String>): Dp =
    statusColumn(textColumnWidth(codes, mono.small), blueprint.line)

/**
 * The rule, without a screen to measure on: the widest code, plus the badge's own padding and
 * border on each side, so the column never clips a code it fitted a moment ago.
 */
internal fun statusColumn(widest: Dp, line: Dp): Dp = widest + (BADGE_PAD + line) * 2

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
