package app.recly.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.theme.Space
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptEdits

/**
 * docs/08 "Editing": the editor's working copy. Speaker changes are applied by the core's own rules as they
 * are made, so the badges show what Save will write; the words are kept as typed and compared at Save.
 */
internal data class EditDraft(
    val original: Transcript,
    val transcript: Transcript,
    val texts: List<String>,
    private val speakerEdits: List<TranscriptEdit> = emptyList(),
) {
    val changed: Boolean
        get() = transcript.copy(editedAt = original.editedAt) != original ||
            texts.indices.any { texts[it].trim() != original.segments[it].text.trim() }

    /** One speaker change, applied now; one the transcript does not fit is ignored rather than shown. */
    fun apply(edit: TranscriptEdit): EditDraft = runCatching {
        copy(transcript = TranscriptEdits.apply(transcript, edit, DRAFT_STAMP), speakerEdits = speakerEdits + edit)
    }.getOrDefault(this)

    fun type(segment: Int, text: String): EditDraft = copy(texts = texts.toMutableList().also { it[segment] = text })

    /** Everything Save writes, in one batch: the speaker changes in the order made, then the words. */
    fun edit(): TranscriptEdit = TranscriptEdit.Batch(
        speakerEdits + texts.indices.filter { texts[it].trim() != original.segments[it].text.trim() }
            .map { TranscriptEdit.SetText(it, texts[it]) },
    )

    companion object {
        fun of(transcript: Transcript) = EditDraft(transcript, transcript, transcript.segments.map { it.text })

        /** The draft's own stamp — never saved: the core stamps the edit it writes. */
        private const val DRAFT_STAMP = "draft"
    }
}

/**
 * docs/09 "Editing and speakers": the transcript as one plain field per segment — finer than the reading
 * groups — each with its time (a seek; the player stays usable) and its speaker badge, whose menu changes
 * the draft. A transcript nobody was identified in offers Add speaker on every line instead.
 */
@Composable
internal fun TranscriptEditor(
    draft: EditDraft,
    onDraft: (EditDraft) -> Unit,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    /** The recording's length, which picks the format of every time button ([clock]). */
    scaleSec: Long? = null,
) {
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val transcript = draft.transcript
    renaming?.let { id ->
        SpeakerNameDialog(transcript.speakers.firstOrNull { it.id == id }?.name, onSave = { name ->
            renaming = null
            onDraft(draft.apply(TranscriptEdit.RenameSpeaker(id, name)))
        }, onCancel = { renaming = null })
    }
    LazyColumn(modifier.fillMaxSize().testTag("transcript-editor"), contentPadding = PaddingValues(vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        itemsIndexed(transcript.segments) { index, segment ->
            Column(Modifier.padding(horizontal = Space.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    val stamp = clock(segment.start.toLong(), scaleSec)
                    val seekLabel = stringResource(R.string.transcript_seek, hms(segment.start.toLong()))
                    BlueprintButton(stamp, { onSeek(segment.start) }, enabled = canSeek && segment.start < seekableDurationSec,
                        modifier = Modifier.semantics { contentDescription = seekLabel }, tone = ButtonTone.QUIET, monospace = true)
                    if (segment.speaker.isEmpty()) {
                        BlueprintButton(stringResource(R.string.speaker_add), { onDraft(draft.apply(TranscriptEdit.SetSpeaker(index, null))) },
                            tone = ButtonTone.QUIET, modifier = Modifier.testTag("edit-add-speaker-$index"))
                    } else {
                        androidx.compose.foundation.layout.Box {
                            SpeakerBadge(speakerLabel(transcript, segment.speaker), named = transcript.speakers.any { it.id == segment.speaker && it.name != null },
                                onClick = { menuFor = index }, modifier = Modifier.testTag("edit-speaker-$index"))
                            if (menuFor == index) SpeakerMenu(transcript, segment.speaker,
                                onRename = { renaming = it },
                                onChange = { id -> onDraft(draft.apply(TranscriptEdit.SetSpeaker(index, id))) },
                                onDismiss = { menuFor = null })
                        }
                    }
                }
                OutlinedTextField(draft.texts[index], { onDraft(draft.type(index, it)) }, modifier = Modifier.fillMaxWidth().testTag("edit-text-$index"))
            }
        }
    }
}
