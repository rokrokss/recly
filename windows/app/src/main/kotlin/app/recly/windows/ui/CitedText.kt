package app.recly.windows.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * docs/09 "AI view": an answer — plain text with its own line breaks, selectable like the transcript, in the
 * direction its own words take — whose `[00:12:34]` times each play the recording from there.
 *
 * The text is one selectable [Text], each time a span of it in monospace and the accent, with no underline — a
 * dotted one is a web page. Selection and a click on part of a text want the same press, so a time's target is not
 * the span but an invisible box laid over it, at least [MinTouch] each way and centred on its words — one for each
 * line a time is on: the press on a time is the time's, a drag anywhere else selects, and a copy carries the time as
 * written. The boxes take no room, so the lines keep their height; one reaches a little over the lines next to it,
 * and where two reach over each other the press plays the time whose words are nearest ([citationHit], Apple's
 * rule, 2026-10-10). A time the player cannot reach now — nothing to play, or past the recording's end
 * ([seekableDurationSec]), as a transcript time button past it is off — is muted and only text. Each box is also a
 * screen reader's `Play from …` button.
 */
@Composable
internal fun CitedText(
    text: String,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
    strings: Strings,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
) {
    val palette = blueprint
    val runs = remember(text) { citationRuns(text) }
    // Where each run starts in the text, which is where the layout finds its words.
    val starts = remember(runs) { runs.runningFold(0) { at, run -> at + run.text.length } }
    var pressed by remember(text) { mutableStateOf<Int?>(null) }
    val playable = runs.map { run -> canPlay && run.atSec != null && run.atSec < seekableDurationSec }
    val annotated = buildAnnotatedString {
        runs.forEachIndexed { index, run ->
            if (run.atSec == null) {
                append(run.text)
            } else {
                val color = if (playable[index]) palette.accent else palette.textMuted
                withStyle(SpanStyle(fontFamily = mono.body.fontFamily, color = if (pressed == index) color.copy(alpha = PRESSED_ALPHA) else color)) {
                    append(run.text)
                }
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val reach = with(LocalDensity.current) { MinTouch.toPx() }
    Box {
        SelectionContainer {
            Text(
                annotated,
                // Across the width, so the words' own direction also decides which edge they start from.
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = palette.text,
                onTextLayout = { layout = it },
            )
        }
        val lines = layout ?: return@Box
        val targets = remember(lines, playable) {
            runs.indices.filter { playable[it] }.flatMap { index -> citationTargets(lines, index, starts[index], starts[index + 1]) }
        }
        targets.forEach { target ->
            val area = target.area(reach)
            val label = strings[Str.SUMMARY_PLAY_FROM, runs[target.run].time!!]
            val play = { hit: CitationTarget? -> hit?.let { runs[it.run].atSec }?.let(onPlay) }
            Box(
                Modifier
                    // Over the words and taking no room: the lines keep their height.
                    .layout { measurable, _ ->
                        val box = measurable.measure(Constraints.fixed(area.width.roundToInt(), area.height.roundToInt()))
                        layout(0, 0) { box.place(area.left.roundToInt(), area.top.roundToInt()) }
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(targets) {
                        detectTapGestures(
                            onPress = { at ->
                                pressed = citationHit(at + area.topLeft, targets, reach)?.run
                                tryAwaitRelease()
                                pressed = null
                            },
                            onTap = { at -> play(citationHit(at + area.topLeft, targets, reach)) },
                        )
                    }
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                        onClick { play(target); true }
                    },
            )
        }
    }
}

/**
 * docs/09 "AI view": one time's words on one line of the text — [run] is its place among the text's runs — and
 * around them the box a press plays it from.
 */
internal data class CitationTarget(val run: Int, val glyphs: Rect) {
    /** At least [reach] each way, centred on the words and their line: it reaches over the lines next to it, where [citationHit] decides. */
    fun area(reach: Float): Rect {
        val width = maxOf(glyphs.width, reach)
        val height = maxOf(glyphs.height, reach)
        return Rect(glyphs.center.x - width / 2, glyphs.center.y - height / 2, glyphs.center.x + width / 2, glyphs.center.y + height / 2)
    }

    fun distance(point: Offset): Float =
        hypot(maxOf(glyphs.left - point.x, 0f, point.x - glyphs.right), maxOf(glyphs.top - point.y, 0f, point.y - glyphs.bottom))
}

/**
 * What a press at [point] plays: of the targets whose area holds it, the one whose words are nearest — so a press on
 * a time's own words plays that time even where the next line's target reaches over it (Apple's `CitationTarget.hit`).
 */
internal fun citationHit(point: Offset, targets: List<CitationTarget>, reach: Float): CitationTarget? =
    targets.filter { it.area(reach).contains(point) }.minByOrNull { it.distance(point) }

/**
 * The box around the characters [start] to [end] on each line they are on — one, unless a time wraps: across, the
 * characters' own; up and down, the line's own top and bottom. A character's box is its own face's ascent and descent,
 * and the time's monospace face is not the line's sans one: centred on it, a target reached 20 above the line and 23
 * below on Windows (2026-10-27, Consolas in Segoe UI). The line is the same box whatever the two faces are.
 */
private fun citationTargets(layout: TextLayoutResult, run: Int, start: Int, end: Int): List<CitationTarget> =
    (start until end).groupBy(layout::getLineForOffset).map { (line, offsets) ->
        val characters = offsets.map(layout::getBoundingBox)
        CitationTarget(
            run,
            Rect(characters.minOf { it.left }, layout.getLineTop(line), characters.maxOf { it.right }, layout.getLineBottom(line)),
        )
    }

/** Pressed, a time fades as a text link does. */
private const val PRESSED_ALPHA = 0.6f
