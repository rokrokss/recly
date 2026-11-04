@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.SheetScrim
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.dotGrid
import recly.core.chatgpt.CorrectionRequest
import recly.core.chatgpt.CorrectionResult
import recly.core.transcribe.EditResult
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument

/** What the field takes: no more than [CorrectionRequest.MAX] characters, never half of a character. */
internal fun correctionInput(text: String): String {
    if (text.length <= CorrectionRequest.MAX) return text
    val cut = text.take(CorrectionRequest.MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** Correct is pressable with words in the field and nothing running. */
internal fun canCorrect(running: Boolean, request: String): Boolean = !running && request.isNotBlank()

/**
 * docs/09 "After a correction": what the last correction of the open recording changed — the lines the reader tints,
 * the speakers it named, the version `Undo` brings back — and the transcript it was made on ([createdAt]): a transcript
 * made again since is another one, whose lines these are not.
 */
internal data class CorrectionMarks(val segments: List<Int>, val names: Int, val previousVersionId: String?, val createdAt: String?)

/** What the Correct sheet says under its button: working, a muted word, or a failure. */
internal sealed interface CorrectOutcome {
    data object Running : CorrectOutcome

    /** Nothing was saved, and nothing failed: `Nothing to correct`, or the menu's own reason. */
    data class Said(@param:StringRes val text: Int) : CorrectOutcome

    /** A `CoreMessage` code — or null for a call that threw, which has only the failure line. */
    data class Failed(val reason: String?) : CorrectOutcome
}

/**
 * [result] as the sheet says it, or null for `Corrected` — the sheet closes and the reader marks the lines. A null
 * [result] is a call that threw. [busyReason] is what the More menu says about the job in the way.
 */
internal fun correctOutcome(result: CorrectionResult?, @StringRes busyReason: Int): CorrectOutcome? = when (result) {
    is CorrectionResult.Corrected -> null
    CorrectionResult.NothingToCorrect -> CorrectOutcome.Said(R.string.correct_nothing)
    CorrectionResult.NoTranscript -> CorrectOutcome.Said(R.string.detail_no_transcript)
    CorrectionResult.Busy -> CorrectOutcome.Said(busyReason)
    is CorrectionResult.Failed -> CorrectOutcome.Failed(result.reason)
    null -> CorrectOutcome.Failed(null)
}

/** The line under the header after a correction: the lines it changed, or — when it only named speakers — how many. */
internal fun correctedLine(marks: CorrectionMarks): Pair<Int, Int> =
    if (marks.segments.isNotEmpty()) R.string.corrected_lines to marks.segments.size else R.string.corrected_names to marks.names

/** Where the words of [segments] are in the reader's groups — the core's blocks, which the groups are drawn from. */
internal fun correctionTints(transcript: Transcript, segments: List<Int>): List<FindMatch> =
    if (segments.isEmpty()) emptyList()
    else TranscriptDocument(transcript).segmentRanges(segments).map { FindMatch(it.block, it.offset, it.length) }

/** Why a restore — from Version history, or `Undo` after a correction — did not happen; [failed] is said as a failure. */
internal data class RestoreRefusal(@param:StringRes val text: Int, val failed: Boolean)

/** [result] of a restore as a refusal, or null when it was saved. A null [result] is a call that threw. */
internal fun restoreRefusal(result: EditResult?, @StringRes busyReason: Int): RestoreRefusal? = when (result) {
    is EditResult.Edited -> null
    EditResult.Busy -> RestoreRefusal(busyReason, failed = false)
    EditResult.NoTranscript, is EditResult.Invalid, null -> RestoreRefusal(R.string.version_restore_failed, failed = true)
}

/**
 * docs/09 "AI view" — Correct transcript: what Ask opens, a full-height sheet over the detail, titled with a ✕. One
 * field takes the room there is ([request], kept by the page while it stays on the recording), the line saying what a
 * correction does under it, and Correct at its end. While it runs the field is read-only and `Correcting…` is said under
 * the button; the ✕ still closes the sheet, which cancels it ([onClose]) — so only the ✕ and Back close it, never a drag.
 * `Nothing to correct` and failures stay in the sheet under the button.
 */
@Composable
internal fun CorrectPanel(
    request: String,
    onRequest: (String) -> Unit,
    outcome: CorrectOutcome?,
    onCorrect: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = blueprint
    val running = outcome == CorrectOutcome.Running
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = palette.background,
        tonalElevation = 0.dp,
        scrimColor = SheetScrim,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxSize().dotGrid(palette).navigationBarsPadding().imePadding().testTag("correct-panel")) {
            Row(Modifier.fillMaxWidth().padding(start = Space.m, end = Space.xs, top = Space.xs, bottom = Space.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.correct_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = palette.text)
                GlyphButton(Glyph.CLOSE, stringResource(R.string.action_close), onClose, Modifier.testTag("correct-close"))
            }
            HairLine()
            val placeholder = stringResource(R.string.correct_placeholder)
            // The keyboard's Return adds a line: a request is often a paragraph and a transcript pasted under it.
            BlueprintField(
                request,
                { onRequest(correctionInput(it)) },
                modifier = Modifier.weight(1f).padding(start = Space.m, end = Space.m, top = Space.m),
                placeholder = placeholder,
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                fieldModifier = Modifier.fillMaxHeight().semantics { contentDescription = placeholder }.testTag("correct-field"),
                minLines = FIELD_LINES,
                maxLines = Int.MAX_VALUE,
                readOnly = running,
                verticalAlignment = Alignment.Top,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, top = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.correct_footnote), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted)
                BlueprintButton(stringResource(R.string.correct_correct), onCorrect, tone = ButtonTone.PRIMARY,
                    enabled = canCorrect(running, request), modifier = Modifier.testTag("correct-send"))
            }
            Box(Modifier.fillMaxWidth().padding(bottom = Space.m)) {
                when (outcome) {
                    null -> Unit
                    CorrectOutcome.Running -> LoadingText(stringResource(R.string.correct_running), MaterialTheme.typography.bodySmall,
                        palette.textMuted, Modifier.padding(start = Space.m, end = Space.m, top = Space.m).testTag("correct-running"))
                    is CorrectOutcome.Said -> Text(stringResource(outcome.text),
                        Modifier.padding(start = Space.m, end = Space.m, top = Space.m).testTag("correct-said"),
                        style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                    // The notes' notice: the reason's own sentence, or the failure line with its detail, and the one
                    // button that helps — Manage usage for the usage limit.
                    is CorrectOutcome.Failed -> outcome.reason?.let {
                        ChatGptFailure(it, R.string.correct_failed, onCorrect, Modifier.fillMaxWidth(), retryTag = "correct-retry")
                    } ?: Notice(stringResource(R.string.correct_failed), modifier = Modifier.fillMaxWidth(),
                        button = { BlueprintButton(stringResource(R.string.action_retry), onCorrect, tone = ButtonTone.QUIET,
                            modifier = Modifier.testTag("correct-retry")) })
                }
            }
        }
    }
}

/** The field is at least six lines tall, and takes whatever room the sheet has beyond them. */
private const val FIELD_LINES = 6
