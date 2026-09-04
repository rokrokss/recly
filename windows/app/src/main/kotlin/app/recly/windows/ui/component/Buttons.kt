package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.ProcessingPhase
import app.recly.windows.ui.theme.ProcessingState
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.doneBadgeMs
import app.recly.windows.ui.theme.mono
import app.recly.windows.ui.theme.processingHoldMs
import app.recly.windows.ui.theme.processingPhase
import kotlinx.coroutines.delay

/** docs/09: square, bordered, never a pill. Four weights is all this shell needs. */
enum class ButtonTone { PRIMARY, ACCENT, QUIET, DANGER }

@Composable
fun BlueprintButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.ACCENT,
    enabled: Boolean = true,
    leading: String? = null,
    /** Monospace for a button whose label is data — a secret name, a step id (docs/09 "타이포"). */
    monospace: Boolean = false,
) {
    val palette = blueprint
    val ink = when {
        !enabled -> palette.textMuted
        tone == ButtonTone.PRIMARY -> palette.onAccent
        tone == ButtonTone.ACCENT -> palette.accent
        tone == ButtonTone.DANGER -> palette.danger
        else -> palette.textMuted
    }
    val edge = when {
        !enabled -> palette.grid
        tone == ButtonTone.PRIMARY || tone == ButtonTone.ACCENT -> palette.accent
        tone == ButtonTone.DANGER -> palette.danger
        else -> palette.grid
    }
    val fill = if (enabled && tone == ButtonTone.PRIMARY) palette.accent else Color.Transparent

    Row(
        modifier = modifier
            // docs/09 "접근성": the label is small, the button is not.
            .defaultMinSize(minHeight = MinTouch)
            .background(fill, RoundedCornerShape(Radius.node))
            // docs/09 "고대비 모드" promotes `grid` and `textMuted` to the body colour, which left a
            // disabled button drawn in exactly the ink and the border weight of a live QUIET one.
            // A dash is the cue that survives that, because it is a shape rather than a colour.
            .then(
                if (enabled) {
                    Modifier.border(palette.line, edge, RoundedCornerShape(Radius.node))
                } else {
                    Modifier.dashedBorder(palette.line, edge, Radius.node)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = ink) }
        Text(
            label,
            style = if (monospace) mono.small else MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
            // A label that will not fit is cut with a mark that says so — a Korean button label is
            // half again as long as the English one, and a 640dp window is where that shows.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The same square outline as `Modifier.border`, dashed. Compose has no dashed border modifier, so
 * it is one stroked round-rect inset by half its own width — a stroke is centred on its path, and
 * without the inset half of it would fall outside the button.
 */
private fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH_GAP.toPx())),
        ),
    )
}

private val DASH: Dp = 3.dp
private val DASH_GAP: Dp = 3.dp

/**
 * docs/09 트렌드 2: the rare high-risk action — sign-in, a save, an upload — shows that it happened.
 * What happened is the caller's to say: [state] comes from the operation itself
 * ([app.recly.windows.ui.ShellModel.action], [app.recly.windows.ui.WorkflowsModel.action]), and the
 * button only owns the *window* around it — "…" for at least
 * [app.recly.windows.ui.theme.Motion.PROCESSING_MIN_MS] however fast the result was, a check that
 * fills out the 800ms on success, and nothing at all on failure, which the screen reports.
 */
@Composable
fun ProcessingButton(
    label: String,
    state: ProcessingState,
    strings: Strings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.ACCENT,
    enabled: Boolean = true,
) {
    // Armed by this button's own click: several buttons on a window can share one operation state,
    // and only the one that was pressed owns a window.
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var phase by remember { mutableStateOf(ProcessingPhase.IDLE) }

    LaunchedEffect(state, startedAt) {
        val start = startedAt ?: return@LaunchedEffect
        when (state) {
            // The window stays open for as long as the work does.
            ProcessingState.PROCESSING -> phase = ProcessingPhase.PROCESSING

            // The click has not reached the caller's state yet — or never will, because the
            // operation was refused. Either way the processing look is held out and then dropped.
            ProcessingState.IDLE -> {
                delay(processingHoldMs(workMs = 0))
                phase = ProcessingPhase.IDLE
                startedAt = null
            }

            ProcessingState.DONE, ProcessingState.FAILED -> {
                val workMs = (System.nanoTime() - start) / 1_000_000
                val hold = processingHoldMs(workMs)
                delay(hold)
                val succeeded = state == ProcessingState.DONE
                phase = processingPhase(succeeded, workMs, workMs + hold)
                if (phase == ProcessingPhase.DONE) delay(doneBadgeMs(workMs))
                phase = ProcessingPhase.IDLE
                startedAt = null
            }
        }
    }

    when (phase) {
        ProcessingPhase.IDLE -> BlueprintButton(
            label = label,
            onClick = {
                startedAt = System.nanoTime()
                phase = ProcessingPhase.PROCESSING
                onClick()
            },
            modifier = modifier,
            tone = tone,
            enabled = enabled,
        )

        ProcessingPhase.PROCESSING -> BlueprintButton(
            label = strings[Str.ACTION_PROCESSING],
            onClick = {},
            modifier = modifier,
            tone = tone,
            enabled = false,
        )

        ProcessingPhase.DONE -> BlueprintButton(
            label = label,
            onClick = {},
            modifier = modifier,
            tone = tone,
            enabled = false,
            leading = strings[Str.ACTION_DONE],
        )
    }
}

/**
 * docs/09 "형태": a choice, as a square bordered box rather than Material's pill. `FilterChip` is
 * the component this replaces — a selected one is a *filled container*, and this palette's container
 * is the surface, so a selected chip on a surface has no edge at all and the choice becomes
 * invisible. The Mac's `BlueprintChip` is the same shape.
 *
 * docs/09 "모든 상태는 색 + 텍스트": what says "this one" is three things and not one — the accent,
 * a border on [app.recly.windows.ui.theme.BlueprintColors.selectedLine] (heavier than the hairline
 * *even in high contrast*, where the hairline is itself 2dp), and [SELECTION_MARK] in front of the
 * label, which is the only one of the three a monochrome reader gets. The Mac writes it the same way.
 */
@Composable
fun BlueprintChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Monospace for a chip whose label is data — a provider id, a language tag, a secret name. */
    monospace: Boolean = false,
    // RadioButton for an exclusive choice (language, theme, secret); Checkbox for a multi-select
    // row (sources, tracks) so the announced role matches what a click does.
    role: Role = Role.RadioButton,
) {
    val palette = blueprint
    val ink = when {
        !enabled -> palette.grid
        selected -> palette.accent
        else -> palette.textMuted
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = MinTouch)
            .border(
                width = if (selected) palette.selectedLine else palette.line,
                color = ink,
                shape = RoundedCornerShape(Radius.node),
            )
            // docs/09 "접근성": the border is the only thing that says this one is chosen, and a
            // border is not something a screen reader can read. `selectable` puts the same fact in
            // the semantics — one node, in place of the plain click, so it is announced as a choice
            // rather than as a button whose state nobody mentioned.
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = Space.s, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "$SELECTION_MARK $label" else label,
            style = if (monospace) mono.small else MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The glyph a chosen chip or node wears. Not an icon: it sits in the label's own line of text, at
 * the label's own size, and grows with it (docs/09 "유동 타이포").
 */
const val SELECTION_MARK: String = "✓"
