package app.recly.windows.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import app.recly.windows.ui.theme.Space
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/**
 * docs/09 screen principle 2: state is never colour alone. The tone picks the colour, the code is the text,
 * and a reader who sees neither hue gets the same answer from the letters.
 */
enum class BadgeTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

/** The code and its tone — what [LedgerRow] shows in its last column. [busy] turns the loader in front of the code. */
data class LedgerStatus(val code: String, val tone: BadgeTone, val label: String = code, val busy: Boolean = false)

/**
 * A square badge: 1dp of the tone (2dp in high contrast), the code in monospace, on the surface.
 * The letters are drawn in an ink that clears WCAG AA on both the surface and the page — which for
 * amber is not the same colour as the border (see [app.recly.windows.ui.theme.BlueprintColors]).
 */
@Composable
fun StatusBadge(status: LedgerStatus, modifier: Modifier = Modifier) {
    val palette = blueprint
    Row(
        modifier = modifier
            .border(palette.line, status.tone.line(), RoundedCornerShape(Radius.badge))
            .padding(horizontal = BADGE_PAD, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.busy) BlueprintLoader(status.tone.ink())
        Text(
            text = status.label,
            style = mono.small,
            color = status.tone.ink(),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** The badge's own inset either side of its code. */
private val BADGE_PAD: Dp = 6.dp

/**
 * The ledger's status column, measured rather than guessed: the widest of every [labels] the ledger
 * can show, in the badge's own type — so the fluid scale and the language come with it, and no badge
 * is ever cut to fit (`NEEDS_MODEL` was, at a fixed 92dp). Android's `statusColumnWidth` is the same
 * rule.
 */
@Composable
fun statusColumnWidth(labels: List<String>): Dp = statusColumn(textColumnWidth(labels, mono.small), blueprint.line)

/** The widest code, plus the badge's padding and border on each side. */
internal fun statusColumn(widest: Dp, line: Dp): Dp = widest + (BADGE_PAD + line) * 2

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
