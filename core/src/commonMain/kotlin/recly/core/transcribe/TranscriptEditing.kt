package recly.core.transcribe

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord

/**
 * One change the transcript editor makes (docs/08 "Editing"). Concrete classes, so Swift builds them
 * as they are; [Batch] lets the editor save everything it holds in one write.
 */
sealed interface TranscriptEdit {
    /** The words of segment [segmentIndex]. Its word timings go with the old words: they no longer match. */
    data class SetText(val segmentIndex: Int, val text: String) : TranscriptEdit

    /**
     * Who says segment [segmentIndex]: one of the transcript's speakers, or with [speakerId] null a new
     * one, `S{n+1}` after the highest id there is. On a transcript nobody was identified in, the first
     * speaker given is given to every segment — the editor then splits the others off.
     */
    data class SetSpeaker(val segmentIndex: Int, val speakerId: String?) : TranscriptEdit

    /** What speaker [speakerId] is called; null or blank clears the name and the id shows again. */
    data class RenameSpeaker(val speakerId: String, val name: String?) : TranscriptEdit

    /** [edits] in order, all or nothing. */
    data class Batch(val edits: List<TranscriptEdit>) : TranscriptEdit
}

/** What `ReclyCore.editTranscript` did. */
sealed interface EditResult {
    /** Saved here; on its way to the recording's folder, now or with the next job pass. */
    data class Edited(val transcript: Transcript) : EditResult

    /** A transcription of this recording is queued or running and would write over the edit. Nothing was saved. */
    data object Busy : EditResult

    /** There is no transcript to edit, here or in the recording's folder. */
    data object NoTranscript : EditResult

    /** The edit names a segment or a speaker the transcript does not have. Nothing was saved. */
    data class Invalid(val reason: String) : EditResult
}

/** docs/08 "Editing": the rules of an edit, on the transcript alone. */
object TranscriptEdits {
    /** The longest name a speaker can be given. */
    const val NAME_MAX: Int = 100

    /**
     * [transcript] with [edit] applied and [editedAt] stamped — or the transcript as it was, unstamped,
     * when the edit changes nothing. Throws [IllegalArgumentException] for an edit that does not fit it.
     */
    fun apply(transcript: Transcript, edit: TranscriptEdit, editedAt: String): Transcript {
        val edited = applyOne(transcript, edit)
        if (edited == transcript) return transcript
        val used = edited.segments.map { it.speaker }.toSet()
        return edited.copy(
            // Who no longer says anything is no longer in the list.
            speakers = edited.speakers.filter { it.id in used },
            editedAt = editedAt,
        )
    }

    private fun applyOne(transcript: Transcript, edit: TranscriptEdit): Transcript = when (edit) {
        is TranscriptEdit.Batch -> edit.edits.fold(transcript, ::applyOne)
        is TranscriptEdit.SetText -> {
            val segment = segment(transcript, edit.segmentIndex)
            val text = edit.text.trim()
            if (text == segment.text.trim()) transcript
            else transcript.withSegment(edit.segmentIndex, segment.copy(text = text, words = null))
        }
        is TranscriptEdit.SetSpeaker -> setSpeaker(transcript, edit)
        is TranscriptEdit.RenameSpeaker -> {
            val name = edit.name?.trim()?.takeIf { it.isNotEmpty() }
            require(name == null || name.length <= NAME_MAX) { "a speaker name is at most $NAME_MAX characters" }
            require(transcript.speakers.any { it.id == edit.speakerId }) { "no speaker ${edit.speakerId}" }
            transcript.copy(speakers = transcript.speakers.map { if (it.id == edit.speakerId) it.copy(name = name) else it })
        }
    }

    private fun setSpeaker(transcript: Transcript, edit: TranscriptEdit.SetSpeaker): Transcript {
        val segment = segment(transcript, edit.segmentIndex)
        val unidentified = transcript.speakers.isEmpty()
        val id = edit.speakerId
        if (id != null) require(transcript.speakers.any { it.id == id }) { "no speaker $id" }
        val speaker = id ?: "S${(transcript.speakers.mapNotNull { it.id.removePrefix("S").toIntOrNull() }.maxOrNull() ?: 0) + 1}"
        val speakers = if (id == null) transcript.speakers + TranscriptSpeaker(speaker) else transcript.speakers
        if (unidentified) {
            // Nobody was identified: every segment had "" and the schema allows that only with no
            // speakers at all, so the first one named covers the whole transcript.
            return transcript.copy(
                speakers = speakers,
                segments = transcript.segments.map { it.copy(speaker = speaker) },
                speakerIdentification = if (transcript.schema == Transcript.LOCAL_SCHEMA) "identified" else transcript.speakerIdentification,
            )
        }
        if (segment.speaker == speaker) return transcript
        return transcript.copy(speakers = speakers).withSegment(edit.segmentIndex, segment.copy(speaker = speaker))
    }

    private fun segment(transcript: Transcript, index: Int): TranscriptSegment {
        require(index in transcript.segments.indices) { "no segment $index" }
        return transcript.segments[index]
    }

    private fun Transcript.withSegment(index: Int, segment: TranscriptSegment): Transcript =
        copy(segments = segments.toMutableList().also { it[index] = segment })
}

/**
 * The local half of an edit: `{base}.transcript.json` and `.txt` in the recording's directory, and the
 * `.md` a local folder keeps (docs/08 "Result files") — under the lock every other writer of them takes.
 * The folder's copies follow through the pending write (`RemoteRecordings.pushTranscripts`).
 */
internal class TranscriptWriter(private val deps: CoreDeps) {
    suspend fun writeLocal(record: RecordingRecord, transcript: Transcript, markdown: Boolean) = withContext(deps.io) {
        val base = MetaWriter.baseName(record.meta)
        resultFileMutex.withLock {
            deps.fileSystem.createDirectories(record.dir)
            atomic(record.dir / TranscribeRunner.jsonFileName(base), recJson.encodeToString(transcript))
            atomic(record.dir / TranscribeRunner.textFileName(base), TranscriptNormalizer.text(transcript))
            if (markdown) atomic(record.dir / TranscribeRunner.markdownFileName(base), TranscriptNormalizer.markdown(transcript, record.meta))
        }
    }

    private fun atomic(path: Path, text: String) {
        val temp = path.parent!! / "${path.name}.tmp"
        deps.fileSystem.write(temp) { writeUtf8(text) }
        deps.fileSystem.atomicMove(temp, path)
    }
}
