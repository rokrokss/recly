package app.recly.windows.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/**
 * docs/09 화면 원칙 2: state is never colour alone. The tone picks the colour, the code is the text,
 * and a reader who sees neither hue gets the same answer from the letters.
 */
enum class BadgeTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER }

/** The code and its tone — what [LedgerRow] shows in its last column. */
data class LedgerStatus(val code: String, val tone: BadgeTone)

/**
 * A square badge: 1dp of the tone (2dp in high contrast), the code in monospace, on the surface.
 * The letters are drawn in an ink that clears WCAG AA on both the surface and the page — which for
 * amber is not the same colour as the border (see [app.recly.windows.ui.theme.BlueprintColors]).
 */
@Composable
fun StatusBadge(status: LedgerStatus, modifier: Modifier = Modifier) {
    val palette = blueprint
    Text(
        text = status.code,
        modifier = modifier
            .border(palette.line, status.tone.line(), RoundedCornerShape(Radius.badge))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = mono.small,
        color = status.tone.ink(),
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
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
