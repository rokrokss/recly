package app.recly.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.message
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import recly.core.chatgpt.CorrectionRequest
import recly.core.chatgpt.CorrectionResult

/**
 * docs/09 "AI view" — "Correct transcript": where the dialog's request has got to. Nothing is kept once it closes; the
 * request's words are the detail's ([RecordingsWindow]), and what a correction saved is the reader's ([CorrectionMarks]).
 */
sealed interface CorrectionState {
    data object None : CorrectionState

    data object Running : CorrectionState

    /** ChatGPT changed nothing, or nothing it changed was still as it had been sent: a muted line. */
    data object NothingToCorrect : CorrectionState

    /** Nothing was sent or saved for [reason] — More's own reason for it — said in the warning ink. */
    data class Refused(val reason: Str) : CorrectionState

    /** The request failed: the notes' notice, with the core's sentence for [reason] when it gave one. */
    data class Failed(val reason: String?) : CorrectionState
}

/**
 * [result] as the dialog says it — a correction that saved closes the dialog and is said by the reader instead. [busy] is
 * what More says about the job in the way; a null [result] is a call that threw.
 */
internal fun correctionState(result: CorrectionResult?, busy: Str): CorrectionState = when (result) {
    is CorrectionResult.Corrected -> CorrectionState.None
    CorrectionResult.NothingToCorrect -> CorrectionState.NothingToCorrect
    CorrectionResult.Busy -> CorrectionState.Refused(busy)
    CorrectionResult.NoTranscript -> CorrectionState.Refused(Str.DETAIL_NO_TRANSCRIPT)
    is CorrectionResult.Failed -> CorrectionState.Failed(result.reason)
    null -> CorrectionState.Failed(null)
}

/** The notice of a failed correction: the core's sentence, or `Could not correct the transcript` with its detail. */
internal fun correctionFailure(reason: String?): ChatGptFailure =
    reason?.let { chatGptFailure(it, Str.CORRECT_FAILED) } ?: ChatGptFailure(Str.CORRECT_FAILED.message(), null, ChatGptRecovery.RETRY)

/**
 * docs/09 "After a correction": what the last correction of the open recording changed — its [segments] tinted in the
 * reader, and the line under the header with `Undo`, which brings back [previousVersionId]. [createdAt] is the
 * transcript it was made on: a transcript transcribed again is another one, and the marks go. Kept until it is undone,
 * the transcript is edited or corrected again, a version is restored, or the detail leaves the recording.
 */
data class CorrectionMarks(
    val segments: List<Int>,
    val names: Int,
    val previousVersionId: String?,
    val createdAt: String?,
    /** Undo is bringing the version back. */
    val undoing: Boolean = false,
    /** Why Undo did not, said in the line's place for a moment. */
    val refused: Str? = null,
)

/** The line's words: the lines changed, or the speakers named when no line was. */
internal fun correctedLine(marks: CorrectionMarks, strings: Strings): String =
    if (marks.segments.isNotEmpty()) strings[Str.CORRECTED_LINES, marks.segments.size] else strings[Str.CORRECTED_NAMES, marks.names]

/**
 * docs/09 "AI view" — "Correct transcript": the request in the user's words, in a modal dialog over the detail, as Ask is
 * (the Mac's too). Its one way out is the close mark, or Escape, which cancels a request still running: nothing is saved.
 * A correction that saved closes it ([ShellModel.correct]).
 */
@Composable
internal fun CorrectDialog(
    model: ShellModel,
    detail: RecordingDetail,
    /** The request as typed, which stays while the detail is on the recording. */
    request: String,
    onRequest: (String) -> Unit,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
) {
    // Gone with the window, or with another recording picked under it: closed — and cancelled — as the close mark does.
    DisposableEffect(detail.recordingId) { onDispose { model.closeCorrect() } }
    BlueprintDialog(
        title = strings[Str.CORRECT_TITLE],
        onDismissRequest = model::closeCorrect,
        actions = null,
        height = CORRECT_OPENING_HEIGHT,
        theme = theme,
        fitContent = true,
        maxHeight = CORRECT_HEIGHT,
        closeLabel = strings[Str.CLOSE],
    ) {
        CorrectPanel(
            state = model.correction,
            request = request,
            onRequest = onRequest,
            onCorrect = model::correct,
            onManageUsage = { model.chatGpt?.openUsage() },
            strings = strings,
        )
    }
}

/**
 * The dialog's body: one plain-text field of at least six lines, the note under it, `Correct`, and what the request came
 * to. While it runs the field can be read and not changed. Ctrl+Enter corrects — ⌘Return where the PC is a Mac — and
 * Enter is a new line.
 */
@Composable
internal fun CorrectPanel(
    state: CorrectionState,
    request: String,
    onRequest: (String) -> Unit,
    onCorrect: (String) -> Unit,
    onManageUsage: () -> Unit,
    strings: Strings,
) {
    val running = state == CorrectionState.Running
    val corrects = request.isNotBlank() && !running
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            BlueprintTextField(
                request,
                // The field takes no more than the core sends (docs/08 "Correcting").
                { onRequest(it.take(CorrectionRequest.MAX)) },
                label = strings[Str.CORRECT_PLACEHOLDER],
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if ((event.key != Key.Enter && event.key != Key.NumPadEnter) || !(event.isCtrlPressed || event.isMetaPressed)) {
                        return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown && corrects) onCorrect(request)
                    true
                },
                hint = strings[Str.CORRECT_NOTE],
                singleLine = false,
                minLines = REQUEST_MIN_LINES,
                maxLines = REQUEST_MAX_LINES,
                monospace = false,
                placeholder = strings[Str.CORRECT_PLACEHOLDER],
                labelShown = false,
                readOnly = running,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BlueprintButton(strings[Str.CORRECT], { onCorrect(request) }, tone = ButtonTone.PRIMARY, enabled = corrects)
            }
        }
        when (state) {
            CorrectionState.None -> Unit
            CorrectionState.Running -> LoadingText(strings[Str.CORRECT_RUNNING], MaterialTheme.typography.bodyMedium, blueprint.textMuted)
            CorrectionState.NothingToCorrect -> Text(strings[Str.CORRECT_NOTHING], style = MaterialTheme.typography.bodyMedium, color = blueprint.textMuted)
            is CorrectionState.Refused -> Text(strings[state.reason], style = MaterialTheme.typography.bodyMedium, color = blueprint.warningInk)
            // Retry sends the request as it is now.
            is CorrectionState.Failed -> ChatGptFailureNotice(correctionFailure(state.reason), onManageUsage, { onCorrect(request) }, strings)
        }
    }
}

/** At least six lines (docs/09 "AI view"), and room for a pasted transcript before the field scrolls. */
private const val REQUEST_MIN_LINES = 6
private const val REQUEST_MAX_LINES = 14

/** The tallest the dialog grows: the field at its tallest, the note, Correct and a failure's notice. */
private val CORRECT_HEIGHT = 620.dp

/** Where the window opens before it takes the panel's own height. */
private val CORRECT_OPENING_HEIGHT = 360.dp
