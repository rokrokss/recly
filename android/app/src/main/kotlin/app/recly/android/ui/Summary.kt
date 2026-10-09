package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.recly.android.R
import app.recly.android.core.CoreMessages
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import kotlinx.coroutines.delay
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.Summary
import recly.core.chatgpt.SummaryState
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.storage.StorageKind

/** The detail's two views once a summary is in play (docs/09 "Summary view"). */
internal enum class DetailView { TRANSCRIPT, SUMMARY }

/**
 * Why the More menu's Summarize cannot run now, or null when it can — in the order the reasons are checked
 * (docs/09 "Detail header and More menu"). [busyReason] is what the menu already says while a transcription
 * of the recording is queued or running. A take still being written has no More menu at all.
 */
@StringRes
internal fun summarizeReason(
    hasTranscript: Boolean,
    transcribing: Boolean,
    @StringRes busyReason: Int,
    connection: ChatGptConnection,
    summary: SummaryState,
): Int? = when {
    !hasTranscript -> R.string.detail_no_transcript
    transcribing -> busyReason
    connection !is ChatGptConnection.SignedIn -> CoreMessages.resourceOf(CoreMessage.CHATGPT_SIGN_IN_REQUIRED)
    summary is SummaryState.Running -> R.string.summary_running
    else -> null
}

/** "Summarize again" once there is a summary to replace. */
internal fun hasSummary(summary: SummaryState): Boolean = when (summary) {
    SummaryState.None -> false
    is SummaryState.Ready -> true
    is SummaryState.Running -> summary.previous != null
    is SummaryState.Failed -> summary.previous != null
}

/** The summary the recording keeps: what Edit summary opens, and what Summarize again would replace. */
internal fun savedSummary(summary: SummaryState): Summary? = when (summary) {
    SummaryState.None -> null
    is SummaryState.Ready -> summary.summary
    is SummaryState.Running -> summary.previous
    is SummaryState.Failed -> summary.previous
}

/** Edit summary is offered once there is a summary; it waits while a new one is being written. */
@StringRes
internal fun editSummaryReason(summary: SummaryState): Int? = if (summary is SummaryState.Running) R.string.summary_running else null

/** "Summarize again" asks first when the summary it would replace is one the user edited. */
internal fun asksBeforeReplacing(summary: SummaryState): Boolean = savedSummary(summary)?.editedAt != null

/**
 * The summary editor's note: Drive and iCloud carry a saved summary to the other devices; a local folder, or a
 * recording not uploaded yet, keeps it here — the transcript editor's own rule (docs/09 "Editing and speakers").
 */
@StringRes
internal fun summaryEditNote(storage: StorageKind?): Int = when (storage) {
    StorageKind.DRIVE, StorageKind.ICLOUD -> R.string.summary_edit_note_shared
    StorageKind.FOLDER, null -> R.string.summary_edit_note_local
}

/** docs/09 "Summary view": the summary editor's working copy. */
internal data class SummaryDraft(val original: String, val text: String) {
    /** Whether Save writes anything: the core keeps the summary as it is for blank or unchanged text. */
    val changed: Boolean get() = text.trim().let { it.isNotEmpty() && it != original.trim() }

    companion object {
        fun of(summary: Summary) = SummaryDraft(summary.text, summary.text)
    }
}

/** Transcript | Summary, once there is a summary or the user has just asked for one — never while editing. */
internal fun showsSummaryChips(summary: SummaryState, asked: Boolean, editing: Boolean): Boolean =
    !editing && (asked || summary !is SummaryState.None)

/** The reasons whose own sentence is the whole notice: each says what to do. */
private val SPOKEN = setOf(
    CoreMessage.CHATGPT_SIGN_IN_REQUIRED,
    CoreMessage.CHATGPT_USAGE_LIMIT,
    CoreMessage.CHATGPT_PLAN_REQUIRED,
    CoreMessage.PROVIDER_REGION_RESTRICTED,
)

/** [reason]'s key when its sentence is shown on its own, or null when the failure line is generic. */
internal fun spokenReason(reason: String): CoreMessage? = CoreMessageRef.parse(reason)?.message?.takeIf { it in SPOKEN }

/** What goes under a generic failure line: the code's diagnostic, never translated — or the text as it came. */
internal fun reasonDetail(reason: String): String? = CoreMessageRef.parse(reason).let { if (it == null) reason else it.detail ?: it.arg }

/** The one button under a failed summary. */
internal enum class SummaryRecovery { MANAGE_USAGE, RETRY, NONE }

internal fun summaryRecovery(reason: String): SummaryRecovery = when (CoreMessageRef.parse(reason)?.message) {
    CoreMessage.CHATGPT_USAGE_LIMIT -> SummaryRecovery.MANAGE_USAGE
    // The sentence says where to sign in; there is nothing to press here.
    CoreMessage.CHATGPT_SIGN_IN_REQUIRED -> SummaryRecovery.NONE
    else -> SummaryRecovery.RETRY
}

/**
 * docs/09 "Summary view": the summary of the open recording, or where it is. The text is ChatGPT's plain text
 * with its own line breaks; [models] names the model it was written with.
 */
@Composable
internal fun SummaryPane(state: SummaryState, models: List<ChatGptModel>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val context = LocalContext.current
    when (state) {
        // None only for the moment between the tap and the run starting.
        SummaryState.None, is SummaryState.Running -> Column(modifier) {
            LoadingText(stringResource(R.string.summary_running), MaterialTheme.typography.bodySmall, palette.textMuted,
                Modifier.padding(horizontal = Space.m, vertical = Space.s).testTag("summary-running"))
            HairLine()
            (state as? SummaryState.Running)?.previous?.let { SummaryText(it, Modifier.weight(1f)) }
        }

        is SummaryState.Ready -> Column(modifier) {
            SummaryText(state.summary, Modifier.weight(1f))
            HairLine()
            SummaryFooter(state.summary, models)
        }

        is SummaryState.Failed -> {
            val spoken = spokenReason(state.reason)
            val text = if (spoken != null) coreMessage(spoken).text() else stringResource(R.string.summary_failed)
            val detail = if (spoken != null) null else reasonDetail(state.reason)
            val button: (@Composable () -> Unit)? = when (summaryRecovery(state.reason)) {
                SummaryRecovery.MANAGE_USAGE -> {
                    { BlueprintButton(stringResource(R.string.chatgpt_manage_usage), { context.openUrl(CHATGPT_USAGE_URL) }, tone = ButtonTone.PRIMARY) }
                }
                SummaryRecovery.RETRY -> {
                    { BlueprintButton(stringResource(R.string.action_retry), onRetry, tone = ButtonTone.QUIET, modifier = Modifier.testTag("summary-retry")) }
                }
                SummaryRecovery.NONE -> null
            }
            val previous = state.previous
            if (previous == null) {
                Notice(text, detail = detail, button = button, modifier = modifier.fillMaxSize())
            } else {
                Column(modifier) {
                    Notice(text, detail = detail, button = button, modifier = Modifier.fillMaxWidth())
                    HairLine()
                    SummaryText(previous, Modifier.weight(1f))
                }
            }
        }
    }
}

/** docs/09 "Summary view": the whole summary as one plain text field, as the transcript editor's fields are. */
@Composable
internal fun SummaryEditor(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(text, onText, modifier.padding(Space.m).testTag("summary-editor"))
}

/** The text as ChatGPT wrote it, selectable, in the transcript's body type. */
@Composable
private fun SummaryText(summary: Summary, modifier: Modifier) {
    SelectionContainer(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(summary.text, Modifier.padding(Space.m).testTag("summary-text"), style = MaterialTheme.typography.bodyMedium, color = blueprint.text)
    }
}

/** "ChatGPT · <model>" — "· Edited" once the user changed it — and Copy all, which says `✓ Copied` for a moment as the transcript's does. */
@Composable
private fun SummaryFooter(summary: Summary, models: List<ChatGptModel>) {
    val palette = blueprint
    val clipboard = LocalClipboardManager.current
    var copied by remember(summary) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    Row(
        Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = models.firstOrNull { it.id == summary.model }?.label ?: summary.model
        val written = stringResource(R.string.summary_model, label)
        Text(if (summary.editedAt == null) written else "$written · ${stringResource(R.string.summary_edited)}", Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        BlueprintButton(
            stringResource(if (copied) R.string.transcript_copied else R.string.transcript_copy),
            { clipboard.setText(AnnotatedString(summary.text)); copied = true },
            tone = ButtonTone.QUIET,
            leading = if (copied) stringResource(R.string.action_done) else null,
            modifier = Modifier.testTag("summary-copy"),
        )
    }
}

private const val COPIED_MS = 3_000L
