package recly.core.notes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * docs/08 "Organizing": what a note is. A spoken commitment is a [TODO] with an owner; a [POINT] — a key point — is
 * made after Stop only; a [SECTION] item belongs to one of the user's own sections ([NoteItem.section]).
 */
@Serializable
enum class NoteKind {
    @SerialName("decision")
    DECISION,

    @SerialName("todo")
    TODO,

    @SerialName("question")
    QUESTION,

    @SerialName("point")
    POINT,

    @SerialName("section")
    SECTION,
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
 * One note as a shell shows it (docs/08 "Organizing"). [text] is what the user sees: their own words once they
 * edited it. [owner] is already resolved — the name the user gave the speaker it belongs to, or null when that
 * speaker has none, or while the notes are provisional; never a label like `S1`. [due] is the deadline as it was
 * said. [atSec] and [quote] are its evidence: where on the recording, and the words, verbatim. [section] is the id
 * of the user's section a [NoteKind.SECTION] item is in.
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
    val section: String? = null,
)

/** One of the user's own sections in a recording's notes, under the name it had when its items were made. */
data class NoteSectionItems(val id: String, val name: String, val items: List<NoteItem>)

/**
 * A recording's notes (`Notes.observe`): the [overview] once the final pass has written one, and the items by
 * kind, each list newest first (latest [NoteItem.atSec] first) — [points] (key points) after Stop only, and the
 * user's [sections] in the order they were made with. [organizing] is true while a part or the final pass is being
 * worked on in this process. [failure] is the `CoreMessage` wire code of the last Organize again of this recording
 * that failed here, until the next one starts. [model] is the plan's name for the model the notes were made with.
 */
data class RecordingNotes(
    val recordingId: String,
    val stage: NotesStage,
    val overview: String?,
    val decisions: List<NoteItem>,
    val todos: List<NoteItem>,
    val questions: List<NoteItem>,
    val organizing: Boolean,
    val points: List<NoteItem> = emptyList(),
    val sections: List<NoteSectionItems> = emptyList(),
    val failure: String? = null,
    val model: String? = null,
) {
    val isEmpty: Boolean
        get() = overview == null && points.isEmpty() && decisions.isEmpty() && todos.isEmpty() && questions.isEmpty() &&
            sections.all { it.items.isEmpty() }
}

/**
 * docs/08 "Organizing": what this device asks every recording's notes for — Settings → Notes. [instructions] are
 * the user's own words on how to write them; [sections] are up to [SECTIONS_MAX] sections of their own, each listed
 * with evidence like the built-in groups. Kept on this device, like About you.
 */
data class NotesPreferences(
    val instructions: String = "",
    val sections: List<NoteSection> = emptyList(),
) {
    companion object {
        const val INSTRUCTIONS_MAX: Int = 1000
        const val SECTIONS_MAX: Int = 3
        const val SECTION_NAME_MAX: Int = 40
        const val DESCRIPTION_MAX: Int = 200
    }
}

/** One of the user's own sections: its [name] heads it, its [description] tells ChatGPT what goes in it. An empty [id] is a new one. */
data class NoteSection(val id: String, val name: String, val description: String)

/** The user's words for one item, from Edit notes (`Notes.edit`); empty deletes it. */
data class NoteEdit(val itemId: String, val text: String)

/**
 * The words the notes' text — Copy all, Share → Notes, the Markdown transcript — puts over each group, in the app's
 * language: the shells set them from their resources, as they set the locale (docs/07 rule 3).
 */
data class NotesHeadings(
    val notes: String = "Notes",
    val keyPoints: String = "Key points",
    val decisions: String = "Decisions",
    val todos: String = "To-dos",
    val questions: String = "Questions",
)

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
 * `{base}.notes.v1.json` beside the recording's parts (`spec/notes.schema.json`); once final, it also goes to the
 * recording's Drive or iCloud folder as `{base}.notes.json` ([NotesFolder]). [sections] are the user's sections the
 * items were made with; [overviewEditedAt] is set once the user changed the overview, which then stays theirs.
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
    /** The plan's name for [model] (`GPT-5.4`), so the footer reads the same signed out. */
    val modelName: String? = null,
    val sections: List<StoredSection> = emptyList(),
    val overviewEditedAt: String? = null,
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
 * part's own for a provisional one. [conversation] is the index of a final note's conversation. [text] is what the
 * model wrote, which matching goes by; [userText] is the user's edit of it, which is what is shown ([shown]).
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
    val section: String? = null,
    val userText: String? = null,
    val editedAt: String? = null,
    val createdAt: String,
) {
    val shown: String get() = userText ?: text
}

@Serializable
internal data class StoredSection(val id: String, val name: String, val description: String = "")

@Serializable
internal data class NoteEvidence(val atSec: Double, val quote: String)

@Serializable
internal data class NoteTombstone(
    val kind: NoteKind,
    val text: String,
    val atSec: Double,
    val deletedAt: String,
    val section: String? = null,
)

@Serializable
internal data class NoteConversation(val startSec: Double, val endSec: Double, val summary: String? = null)
