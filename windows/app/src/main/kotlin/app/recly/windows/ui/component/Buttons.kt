package app.recly.windows.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.theme.ButtonMinWidth
import app.recly.windows.ui.theme.LocalReduceMotion
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Motion
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
import kotlin.math.roundToInt

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
    /** Monospace for a button whose label is data — a secret name, a step id (docs/09 "Typography"). */
    monospace: Boolean = false,
    /**
     * Showing its own work (`…`, `✓`, [ProcessingButton]): it cannot be pressed meanwhile, but it is not disabled — it
     * keeps its ink and its edge, because that state is what the user is meant to read (docs/09 "Disabled, focus and
     * structure").
     */
    busy: Boolean = false,
    /** [ButtonMinWidth] for a worded button; [MinTouch] for data and for Close, Delete and Cancel. */
    minWidth: Dp = if (monospace) MinTouch else ButtonMinWidth,
    /**
     * docs/09 "Component measurements" — Time and speaker button (2026-10-28): drawn at the status badge's size, label 12
     * and no minimum, inside a target that is still [MinTouch] each way ([CompactControl]). [minWidth] does not apply.
     */
    compact: Boolean = false,
) {
    val palette = blueprint
    val ink = when {
        // docs/09 "Disabled, focus and structure": the disabled token, which stays lighter than live ink in high contrast too.
        !enabled -> palette.disabled
        tone == ButtonTone.PRIMARY -> palette.onAccent
        tone == ButtonTone.ACCENT -> palette.accent
        tone == ButtonTone.DANGER -> palette.danger
        else -> palette.textMuted
    }
    val edge = when {
        !enabled -> palette.disabled
        tone == ButtonTone.PRIMARY || tone == ButtonTone.ACCENT -> palette.accent
        tone == ButtonTone.DANGER -> palette.danger
        // A quiet button's edge is the input border, 3:1 against the page in light and dark (2026-10-08) —
        // the grid is a decoration and too faint to say "this is a control" in the dark palette.
        else -> palette.inputBorder
    }
    val fill = if (enabled && tone == ButtonTone.PRIMARY) palette.accent else Color.Transparent
    val shape = RoundedCornerShape(Radius.node)
    val face = Modifier
        .background(fill, shape)
        // docs/09 "High-contrast mode" promotes `grid` and `textMuted` to the body colour, which left a
        // disabled button drawn in exactly the ink and the border weight of a live QUIET one.
        // A dash is the cue that survives that, because it is a shape rather than a colour.
        .then(
            if (enabled) {
                Modifier.border(palette.line, edge, shape)
            } else {
                Modifier.dashedBorder(palette.line, edge, Radius.node)
            },
        )
    // On the accent fill an accent ring would vanish, so a filled button's ring is the ink on it.
    val ring = if (fill == Color.Transparent) null else palette.onAccent
    // The sans label beside a compact one is the status badge's 12 (labelMedium), not a button's 14 (labelLarge).
    val sans = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    val content: @Composable RowScope.() -> Unit = {
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
            // Data — a time, a name of a key — reads left to right in every language (docs/09 "Typography"), and at the
            // 14 every button label is, sans or mono; a compact one's is the status badge's 12.
            style = when {
                !monospace -> sans
                compact -> mono.small.copy(textDirection = TextDirection.Ltr)
                else -> mono.bodySmall.copy(textDirection = TextDirection.Ltr)
            },
            color = ink,
            maxLines = 3,
            // A label that will not fit is cut with a mark that says so — a Korean button label is
            // half again as long as the English one, and a 640dp window is where that shows.
            overflow = TextOverflow.Ellipsis,
            modifier = if (monospace) Modifier.onLabelBaseline(sans) else Modifier,
        )
    }

    if (compact) {
        CompactControl(onClick, modifier, face, shape, enabled = enabled && !busy, ring = ring, content = content)
        return
    }
    Row(
        modifier = modifier
            // docs/09 "Accessibility": the label is small, the button is not — either way.
            .defaultMinSize(minWidth = minWidth, minHeight = MinTouch)
            .then(face)
            .focusRing(shape, color = ring)
            .pressCursor(enabled && !busy)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            // docs/09 "Component measurements": the minimum touch size decides the height, so the vertical padding is the small one.
            .padding(horizontal = Space.s, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * docs/09 "Component measurements" — Time and speaker button (2026-10-28): a control drawn at the status badge's size —
 * its [face] (fill and edge) around [content], with the badge's 8 × 4 inset and no minimum — inside a target of at least
 * [MinTouch] each way, centred on it. The target takes the click and the room in the row, so a row is no shorter than
 * it was; the box drawn in it wears the hover, the press and the focus ring, so they stay on what is seen. [modifier] is
 * the target's: what a reader hears is said there.
 */
@Composable
internal fun CompactControl(
    onClick: () -> Unit,
    modifier: Modifier,
    face: Modifier,
    shape: Shape,
    enabled: Boolean = true,
    ring: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            .pressCursor(enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            face
                .focusRing(interaction, shape, ring)
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = Space.s, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A 14 label in monospace — a time, a speed, a speaker's id — set on the baseline a button's sans label has in the
 * same box, so the controls in one row share one baseline whatever the two faces are (2026-10-27). Centred by its own
 * ascent and descent instead, Consolas sat a pixel and a half above Segoe UI on Windows, where SF Mono and SF agree:
 * the box is a sans label's — one line of it, and each further line of the label — with the label's first baseline on
 * the sans one, so it centres as a sans label does. Ink that reaches past the sans face's ascent or descent hangs over.
 * A compact control's 12 is set on [sansStyle] at 12 the same way.
 */
@Composable
internal fun Modifier.onLabelBaseline(sansStyle: TextStyle = MaterialTheme.typography.labelLarge): Modifier {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val sans = remember(measurer, sansStyle) { measurer.measure("A", sansStyle) }
    return layout { measurable, constraints ->
        val text = measurable.measure(constraints)
        val height = sans.size.height + text[LastBaseline] - text[FirstBaseline]
        layout(text.width, height) { text.place(0, sans.firstBaseline.roundToInt() - text[FirstBaseline]) }
    }
}

/**
 * docs/09 "Disabled, focus and structure" (2026-10-28): the pointing hand over anything that can be pressed now — a
 * button, a mark such as a field's × or the highlight square, a chip, a menu and its items, a link, a row that opens,
 * expands or toggles — and the arrow over one that cannot: disabled, or showing its own work. Never over plain text or a
 * field. Placed with the control's own click, on the same box.
 */
fun Modifier.pressCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

/**
 * The same square outline as `Modifier.border`, dashed — the edge of anything disabled (docs/09 "Disabled, focus and structure"). Compose has no dashed border modifier, so
 * it is one stroked round-rect inset by half its own width — a stroke is centred on its path, and
 * without the inset half of it would fall outside the button.
 */
internal fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
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
 * docs/09 trend 2: the rare high-risk action — sign-in, a save, an upload — shows that it happened.
 * What happened is the caller's to say: [state] comes from the operation itself
 * ([app.recly.windows.ui.ShellModel.action]), and the
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

    val button: @Composable (ProcessingPhase) -> Unit = { shown ->
        when (shown) {
            ProcessingPhase.IDLE -> BlueprintButton(
                label = label,
                onClick = {
                    startedAt = System.nanoTime()
                    phase = ProcessingPhase.PROCESSING
                    onClick()
                },
                tone = tone,
                enabled = enabled,
            )

            ProcessingPhase.PROCESSING -> BlueprintButton(
                label = strings[Str.ACTION_PROCESSING],
                onClick = {},
                tone = tone,
                busy = true,
            )

            ProcessingPhase.DONE -> BlueprintButton(
                label = label,
                onClick = {},
                tone = tone,
                busy = true,
                leading = strings[Str.ACTION_DONE],
            )
        }
    }
    // docs/09 "Motion": the label fades between its three states in a badge's time, as RecKit's does; with reduce
    // motion it simply changes.
    if (LocalReduceMotion.current) {
        Box(modifier, propagateMinConstraints = true) { button(phase) }
    } else {
        Crossfade(
            targetState = phase,
            modifier = modifier,
            animationSpec = tween(durationMillis = Motion.BADGE_FADE_MS, easing = Motion.Standard),
            content = button,
        )
    }
}

/**
 * docs/09 "Shape": a choice, as a square bordered box rather than Material's pill. `FilterChip` is
 * the component this replaces — a selected one is a *filled container*, and this palette's container
 * is the surface, so a selected chip on a surface has no edge at all and the choice becomes
 * invisible. The Mac's `BlueprintChip` is the same shape.
 *
 * docs/09 "Every state is color + text": what says "this one" is three things and not one — the accent,
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
        !enabled -> palette.disabled
        selected -> palette.accent
        else -> palette.textMuted
    }
    // docs/09 "Component measurements": an unchosen chip's edge is the input border.
    val edge = if (enabled && !selected) palette.inputBorder else ink
    val shape = RoundedCornerShape(Radius.node)
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            // Disabled, the edge is dashed as a disabled button's is: the muted ink alone is an unchosen chip's.
            .then(
                if (enabled) {
                    Modifier.border(
                        width = if (selected) palette.selectedLine else palette.line,
                        color = edge,
                        shape = shape,
                    )
                } else {
                    Modifier.dashedBorder(palette.line, edge, Radius.node)
                },
            )
            .focusRing(shape)
            // docs/09 "Accessibility": the border is the only thing that says this one is chosen, and a
            // border is not something a screen reader can read. `selectable` puts the same fact in
            // the semantics — one node, in place of the plain click, so it is announced as a choice
            // rather than as a button whose state nobody mentioned.
            .pressCursor(enabled)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // docs/09 "Component measurements": label 14, sans or mono.
        val style = if (monospace) mono.bodySmall else MaterialTheme.typography.labelLarge
        // The chip is as wide as it is with its mark either way, so choosing one moves nothing beside it: the marked
        // label is laid out unseen under the one drawn (the phone's chip, `android/.../component/Buttons.kt`).
        Box(contentAlignment = Alignment.Center) {
            Text(
                "$SELECTION_MARK $label",
                style = style,
                color = Color.Transparent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {},
            )
            // One line of text, so the mark sits on the label's own baseline; a reader hears the label.
            Text(
                if (selected) "$SELECTION_MARK $label" else label,
                style = style,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            )
        }
    }
}

/**
 * The glyph a chosen chip or node wears. Not an icon: it sits in the label's own line of text, at
 * the label's own size, and grows with it (docs/09 "Fluid typography").
 */
const val SELECTION_MARK: String = "✓"
