package recly.core.notes

import recly.core.model.recJson
import recly.core.transcribe.TranscriptNormalizer

/**
 * docs/08 "Organizing" — "Copy and share": a recording's notes as text, in the order the detail shows them — the
 * overview, then Key points, Decisions, To-dos, Questions and the user's sections, each item with its owner, due
 * date and `[HH:MM:SS]`. The headings are the app's ([NotesHeadings]).
 */
internal object NotesText {
    fun plain(notes: RecordingNotes, headings: NotesHeadings): String? {
        val groups = groups(notes, headings)
        val overview = notes.overview?.trim()?.takeIf { it.isNotEmpty() }
        if (overview == null && groups.isEmpty()) return null
        return buildString {
            overview?.let { append(it).append('\n') }
            groups.forEach { (heading, items) ->
                if (isNotEmpty()) append('\n')
                append(heading).append('\n')
                items.forEach { append("- ").append(line(it)).append('\n') }
            }
        }
    }

    fun markdown(notes: RecordingNotes, headings: NotesHeadings): String? {
        val groups = groups(notes, headings)
        val overview = notes.overview?.trim()?.takeIf { it.isNotEmpty() }
        if (overview == null && groups.isEmpty()) return null
        return buildString {
            append("## ").append(headings.notes).append("\n\n")
            overview?.let { append(it.lines().joinToString("  \n")).append("\n\n") }
            groups.forEach { (heading, items) ->
                append("### ").append(heading).append("\n\n")
                items.forEach { append("- ").append(line(it)).append('\n') }
                append('\n')
            }
        }.trimEnd() + "\n"
    }

    private fun groups(notes: RecordingNotes, headings: NotesHeadings): List<Pair<String, List<NoteItem>>> =
        (
            listOf(
                headings.keyPoints to notes.points,
                headings.decisions to notes.decisions,
                headings.todos to notes.todos,
                headings.questions to notes.questions,
            ) + notes.sections.map { it.name to it.items }
            ).filter { it.second.isNotEmpty() }

    private fun line(item: NoteItem): String = buildString {
        append(item.text.trim())
        item.owner?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
        item.due?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
        append(" [").append(TranscriptNormalizer.clock(item.atSec)).append(']')
    }
}

/**
 * docs/08 "Organizing" — "Storage", docs/03 "Drive layout": final notes in the recording's folder — `{base}.notes.json`,
 * the same JSON as the copy kept here — and the folder's `notesAt`, the version the folder holds. Drive and iCloud
 * carry them to the user's other devices; a local folder does not.
 */
internal object NotesFolder {
    const val STAMP: String = "notesAt"
    const val MIME: String = "application/json"

    fun name(base: String): String = "$base.notes.json"

    fun decode(json: String): NotesFile? = runCatching { recJson.decodeFromString(NotesFile.serializer(), json) }.getOrNull()

    fun version(file: NotesFile): String = file.updatedAt
}
