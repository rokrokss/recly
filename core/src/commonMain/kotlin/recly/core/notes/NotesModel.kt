package recly.core.notes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** docs/08 "Organizing": what a note is. A spoken commitment is a [TODO] with an owner. */
@Serializable
enum class NoteKind {
    @SerialName("decision")
    DECISION,

    @SerialName("todo")
    TODO,

    @SerialName("question")
    QUESTION,
}

/** How far a recording's notes have come. */
enum class NotesStage {
    /** Nothing yet: not organized, or not a part long enough so far. */
    NONE,

    /** Made while recording, part by part, from each part transcribed on its own; replaced by the final pass. */
    PROVISIONAL,

    /** Made after Stop from the recording's transcript, conversation by conversation. */
    FINAL,
}

/**
 * One note as a shell shows it (docs/08 "Organizing"). [owner] is already resolved — the name the user gave the
 * speaker it belongs to, or null when that speaker has none, or while the notes are provisional; never a label
 * like `S1`. [due] is the deadline as it was said. [atSec] and [quote] are its evidence: where on the recording,
 * and the words, verbatim.
 */
data class NoteItem(
    val id: String,
    val kind: NoteKind,
    val text: String,
    val owner: String?,
    val due: String?,
    val atSec: Double,
    val quote: String,
    val provisional: Boolean,
)

/**
 * A recording's notes (`Notes.observe`): the [overview] once the final pass has written one, and the items by
 * kind, each list newest first (latest [NoteItem.atSec] first). [organizing] is true while a part or the final
 * pass is being worked on in this process.
 */
data class RecordingNotes(
    val recordingId: String,
    val stage: NotesStage,
    val overview: String?,
    val decisions: List<NoteItem>,
    val todos: List<NoteItem>,
    val questions: List<NoteItem>,
    val organizing: Boolean,
) {
    val isEmpty: Boolean get() = overview == null && decisions.isEmpty() && todos.isEmpty() && questions.isEmpty()
}

/**
 * One line of the transcript around a note (`Notes.linesAround`): the final transcript's once there is one, the
 * parts' made while recording before that. [speakerName] is the name the user gave [speakerId], or null; [me]
 * marks the person who made the recording (docs/08 "Me and others"). A line made while recording has its part's
 * own label, which does not carry over to the next part.
 */
data class NoteLine(
    val atSec: Double,
    val endSec: Double,
    val speakerId: String?,
    val speakerName: String?,
    val me: Boolean,
    val text: String,
    val provisional: Boolean,
)

/**
 * `{base}.notes.v1.json` beside the recording's parts (`spec/notes.schema.json`). Kept on this device only — not
 * uploaded or synced yet.
 */
@Serializable
internal data class NotesFile(
    val schema: Int = SCHEMA,
    val recordingId: String,
    val stage: String = PROVISIONAL,
    val overview: String? = null,
    val items: List<StoredNote> = emptyList(),
    /** What the user deleted: never shown again, by [SameItem]. */
    val tombstones: List<NoteTombstone> = emptyList(),
    /** The final pass's conversations, in order, each with its summary. */
    val conversations: List<NoteConversation> = emptyList(),
    /** The parts the provisional pass is done with — notes or not — so none is asked twice. */
    val parts: List<Int> = emptyList(),
    val model: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val finalizedAt: String? = null,
) {
    val isFinal: Boolean get() = stage == FINAL

    companion object {
        const val SCHEMA = 1
        const val PROVISIONAL = "provisional"
        const val FINAL = "final"

        fun name(base: String): String = "$base.notes.v1.json"
    }
}

/**
 * [owner] is the speaker id of the transcript the note was made from: the recording's for a final note, the
 * part's own for a provisional one. [conversation] is the index of a final note's conversation.
 */
@Serializable
internal data class StoredNote(
    val id: String,
    val kind: NoteKind,
    val text: String,
    val owner: String? = null,
    val due: String? = null,
    val evidence: NoteEvidence,
    val provisional: Boolean,
    val conversation: Int? = null,
    val createdAt: String,
)

@Serializable
internal data class NoteEvidence(val atSec: Double, val quote: String)

@Serializable
internal data class NoteTombstone(val kind: NoteKind, val text: String, val atSec: Double, val deletedAt: String)

@Serializable
internal data class NoteConversation(val startSec: Double, val endSec: Double, val summary: String? = null)
