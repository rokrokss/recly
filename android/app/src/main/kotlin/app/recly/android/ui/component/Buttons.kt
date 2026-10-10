package app.recly.android.ui.component

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import app.recly.android.R
import app.recly.android.ui.theme.ButtonMinWidth
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.ProcessingPhase
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.doneBadgeMs
import app.recly.android.ui.theme.mono
import app.recly.android.ui.theme.processingHoldMs
import app.recly.android.ui.theme.processingPhase
import kotlinx.coroutines.delay

/** docs/09: square, bordered, never a pill. Four weights is all the app needs. */
enum class ButtonTone { PRIMARY, ACCENT, QUIET, DANGER }

@Composable
fun BlueprintButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.ACCENT,
    enabled: Boolean = true,
    leading: String? = null,
    /** Monospace for a button whose label is data — a template variable, a field name. */
    monospace: Boolean = false,
    /** [ButtonMinWidth] for a worded button; [MinTouch] for data and for Close, Delete and Cancel. */
    minWidth: Dp = if (monospace) MinTouch else ButtonMinWidth,
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
        // The dash is in the input border too: the grid's colour left a disabled button stray grey text.
        !enabled -> palette.inputBorder
        tone == ButtonTone.PRIMARY || tone == ButtonTone.ACCENT -> palette.accent
        tone == ButtonTone.DANGER -> palette.danger
        // docs/09 "Accessibility": a control's edge is 3:1 against the page, light and dark — the
        // input border's level, not the grid's (UX decisions of 2026-10-08).
        else -> palette.inputBorder
    }
    val fill = if (enabled && tone == ButtonTone.PRIMARY) palette.accent else Color.Transparent

    Row(
        modifier = modifier
            // docs/09 "Accessibility": the label is small, the button is not — in both directions. A height
            // alone left the two-letter template variables (`MM`, `dd`) a target some 30dp wide.
            .defaultMinSize(minWidth = minWidth, minHeight = MinTouch)
            .background(fill, RoundedCornerShape(Radius.node))
            // A disabled button is dashed, as on Windows: a shape is the cue that survives high contrast,
            // which promotes the muted ink and the grid to the body colour.
            .then(
                if (enabled) {
                    Modifier.border(palette.line, edge, RoundedCornerShape(Radius.node))
                } else {
                    Modifier.dashedBorder(palette.line, edge, Radius.node)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Text(
                it,
                style = if (it == "+") MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                color = ink,
                modifier = if (it == "+") Modifier.clearAndSetSemantics { } else Modifier,
            )
        }
        Text(
            label,
            style = if (monospace) mono.small else MaterialTheme.typography.labelLarge,
            color = ink,
            maxLines = 3,
        )
    }
}

/**
 * The same square outline as `Modifier.border`, dashed — Windows' own. Compose has no dashed border, so
 * it is one stroked round-rect inset by half its width: a stroke is centred on its path.
 */
internal fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))),
    )
}

private val DASH: Dp = 3.dp

/** What marks a chosen thing — a chip, the open list's line — as the other shells' chips and menus do. */
const val SELECTION_MARK: String = "✓"

/**
 * docs/09 "Shape": a choice, as a square bordered box rather than Material's pill. `FilterChip` is
 * what this replaces — a selected one is a *filled container*, and this palette's container is the
 * surface, so a selected chip on a surface had no edge at all and the choice was invisible. The
 * Windows app's `BlueprintChip` and the Mac's are the same shape.
 *
 * Selected is the accent, on `BlueprintColors.selectedLine` — one step heavier than the hairline,
 * so a chosen chip stays heavier than an unchosen one.
 */
@Composable
fun BlueprintChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Monospace for a chip whose label is data — a provider name, a language tag, a secret. */
    monospace: Boolean = false,
    // RadioButton for an exclusive choice (a provider, a language, a secret); Checkbox for a
    // multi-select row (sources, tracks), so the announced role matches what a tap does.
    role: Role = Role.RadioButton,
) {
    val palette = blueprint
    // A disabled chip is the disabled button: muted words in a dashed input border, which stay readable where the
    // grid's colour vanished.
    val ink = when {
        selected && enabled -> palette.accent
        else -> palette.textMuted
    }
    Row(
        modifier = modifier
            // docs/09 "Accessibility": the label is small, the target is not — in both directions. A height
            // alone left a two- or three-letter chip a target barely half that wide; docs/09 "Shape"
            // wants a chip square anyway, so the box grows to the target rather than hiding behind
            // an invisible one.
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            .then(
                if (enabled) {
                    Modifier.border(
                        width = if (selected) palette.selectedLine else palette.line,
                        color = ink,
                        shape = RoundedCornerShape(Radius.node),
                    )
                } else {
                    Modifier.dashedBorder(palette.line, palette.inputBorder, Radius.node)
                },
            )
            // docs/09 "Accessibility": the border is the only thing that says this one is chosen, and a
            // border is not something a screen reader can read. `selectable` puts the same fact in
            // the semantics — one node, in place of the plain click, so it is announced as a choice
            // rather than as a button whose state nobody mentioned.
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.xs),
        // A short label sits in the middle of the target it grew to, not against its left edge.
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = if (monospace) mono.small else MaterialTheme.typography.labelLarge
        // UX decisions of 2026-10-08: a chosen chip says so with `✓` as well as its border. The chip is
        // as wide as it is with the mark either way, so choosing one does not move its neighbours.
        Box(contentAlignment = Alignment.Center) {
            Text("$SELECTION_MARK $label", style = style, color = Color.Transparent, maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {})
            // One line of text, so the mark sits on the label's own baseline; a reader hears the label.
            Text(if (selected) "$SELECTION_MARK $label" else label, style = style, color = ink, maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { contentDescription = label })
        }
    }
}

/** Where the caller's operation actually is — what the screen knows, not what the button draws. */
enum class ProcessingState { IDLE, PROCESSING, DONE, FAILED }

/**
 * docs/09 trend 2: the rare high-risk action — sign-in, a save, an upload — shows that it happened.
 * What happened is the caller's to say: [state] comes from the operation itself, and the button only
 * owns the *window* around it — "…" for at least
 * [app.recly.android.ui.theme.Motion.PROCESSING_MIN_MS] however fast the result was, a check that
 * fills out the 800ms on success, and nothing at all on failure, which the screen reports.
 *
 * Reduce motion changes nothing here: docs/09 "Motion" turns off the *transitions*, and the label is
 * the state, not a transition. There is no fade between the three labels to switch off either — the
 * button swaps text, which is already instant.
 */
@Composable
fun ProcessingButton(
    label: String,
    state: ProcessingState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.ACCENT,
    enabled: Boolean = true,
) {
    // Armed by this button's own tap: several buttons on a screen can share one operation state,
    // and only the one that was pressed owns a window.
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var phase by remember { mutableStateOf(ProcessingPhase.IDLE) }

    LaunchedEffect(state, startedAt) {
        val start = startedAt ?: return@LaunchedEffect
        when (state) {
            // The window stays open for as long as the work does.
            ProcessingState.PROCESSING -> phase = ProcessingPhase.PROCESSING

            // The tap has not reached the caller's state yet — or never will, because the operation
            // was refused. Either way the processing look is held out and then dropped.
            ProcessingState.IDLE -> {
                delay(processingHoldMs(workMs = 0))
                phase = ProcessingPhase.IDLE
                startedAt = null
            }

            ProcessingState.DONE, ProcessingState.FAILED -> {
                val workMs = SystemClock.elapsedRealtime() - start
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
                startedAt = SystemClock.elapsedRealtime()
                phase = ProcessingPhase.PROCESSING
                onClick()
            },
            modifier = modifier,
            tone = tone,
            enabled = enabled,
        )

        ProcessingPhase.PROCESSING -> BlueprintButton(
            label = stringResource(R.string.action_processing),
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
            leading = stringResource(R.string.action_done),
        )
    }
}
