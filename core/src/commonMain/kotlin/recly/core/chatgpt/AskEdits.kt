package recly.core.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NoteSection
import recly.core.notes.NoteText
import recly.core.notes.NotesAnswer
import recly.core.notes.NotesPrompts
import recly.core.notes.RecordingNotes
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptEdits
import recly.core.transcribe.TranscriptNormalizer

/**
 * docs/08 "Ask" — "Changes": what one request changed in the recording's transcript and summary, saved as it came. Kept
 * with its answer in this process only, for its tint and its `Undo` (`Summaries.undoAsk`).
 */
data class AskChanges(
    /** The transcript's segments whose words or speaker changed, by index. */
    val segments: List<Int>,
    /** How many of the transcript's speakers were named or renamed. */
    val names: Int,
    /** The summary's items changed or added, by id. */
    val items: List<String>,
    /** How many of the summary's items were removed. */
    val removed: Int,
    /** The summary's overview was rewritten. */
    val overview: Boolean,
) {
    /** The transcript's places that changed: its lines, and its speakers' names. */
    val transcriptCount: Int get() = segments.size + names

    /** The summary's places that changed: its items, those removed, and its overview. */
    val summaryCount: Int get() = items.size + removed + (if (overview) 1 else 0)
}

/** docs/08 "Ask": what the composer takes. */
object AskRequest {
    /** The longest request: room for another transcript of a two-hour conversation pasted under it. */
    const val MAX: Int = 200_000
}

/**
 * docs/08 "Ask": the summary as a request shows it — its items numbered from 1 in the order sent, and the sections an
 * item may be added to: the built-in groups, then the user's own the notes were made with. Final notes only.
 */
internal class AskSummary(val notes: RecordingNotes, fileSections: List<NoteSection>) {
    val items: List<NoteItem> = notes.points + notes.decisions + notes.todos + notes.questions + notes.sections.flatMap { it.items }

    val sections: List<NoteSection> = NoteSection.DEFAULTS + fileSections.filter { !it.builtIn }

    /** The kind and section an item added to section [id] has, or null for a section that is not here. */
    fun place(id: String): Pair<NoteKind, String?>? {
        val section = sections.firstOrNull { it.id == id } ?: return null
        return section.kind to section.id.takeIf { section.kind == NoteKind.SECTION }
    }
}

/**
 * docs/08 "Ask": what a request's changes were, as saved — and what undoes them: the transcript's version before them,
 * while the transcript is still [transcriptAfter], and the notes as they were, while they are still as the change left them.
 */
internal class AskApplied(
    val changes: AskChanges,
    val transcriptVersion: String?,
    val transcriptAfter: Transcript?,
    val notes: recly.core.notes.NotesAskChange?,
)

/** docs/08 "Ask": an item a request adds to the summary, with the transcript line it comes from as its evidence. */
internal data class AskNote(
    val kind: NoteKind,
    val section: String?,
    val text: String,
    val atSec: Double,
    val quote: String,
    val owner: String?,
    val due: String?,
)

/**
 * docs/08 "Ask": what ChatGPT answered one request with — words for the user, and the changes asked for: transcript words
 * and speakers by segment index and names by speaker id, as the sent transcript has them; the summary's overview, its
 * items' new words by id (empty removes one) and items added.
 */
internal data class AskProposal(
    val answer: String?,
    val texts: Map<Int, String> = emptyMap(),
    val speakers: Map<Int, String> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val overview: String? = null,
    val items: Map<String, String> = emptyMap(),
    val added: List<AskNote> = emptyList(),
) {
    val changesTranscript: Boolean get() = texts.isNotEmpty() || speakers.isNotEmpty() || names.isNotEmpty()
    val changesSummary: Boolean get() = overview != null || items.isNotEmpty() || added.isNotEmpty()

    companion object {
        /** The longest words a segment is given: anything longer is not a correction of a segment. */
        const val TEXT_MAX = 5000

        /** The words of a transcript line an added item keeps as its evidence. */
        const val QUOTE_MAX = 300

        private val KEYS = setOf("answer", "lines", "names", "overview", "items", "add")

        /**
         * The JSON object in [answer], read leniently — a code fence or words around it are skipped — keeping only what
         * fits [sent] and [summary]: a segment that is there and was said, words on one line that differ from its own, a
         * speaker it has, names as `Fill in speaker names` takes them; an item that is there, a section that is, a line an
         * added item comes from. An answer that is no such object is all words for the user.
         */
        fun parse(answer: String, sent: Transcript, summary: AskSummary?): AskProposal {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            val json = (if (start in 0 until end) runCatching { recly.core.transcribe.providerJson.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject }.getOrNull() else null)
                ?.takeIf { it.keys.any(KEYS::contains) }
                ?: return AskProposal(answer.trim().takeIf { it.isNotEmpty() })
            val ids = sent.speakers.map { it.id }.toSet()
            val texts = mutableMapOf<Int, String>()
            val speakers = mutableMapOf<Int, String>()
            for (line in (json["lines"] as? JsonArray).orEmpty()) {
                val item = line as? JsonObject ?: continue
                val index = (number(item["n"]) ?: continue) - 1
                val segment = sent.segments.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: continue
                string(item["text"])?.let(::oneLine)
                    ?.takeIf { it.length <= TEXT_MAX && it != segment.text.trim() }
                    ?.let { texts[index] = it }
                string(item["speaker"])?.trim()?.takeIf { it in ids && it != segment.speaker && segment.speaker.isNotEmpty() }
                    ?.let { speakers[index] = it }
            }
            val given = (json["names"] as? JsonObject)?.mapNotNull { (id, value) ->
                val name = string(value)?.trim() ?: return@mapNotNull null
                val speaker = sent.speakers.firstOrNull { it.id == id } ?: return@mapNotNull null
                name.takeIf {
                    it.isNotEmpty() && it.length <= TranscriptEdits.SPEAKER_NAME_MAX && '\n' !in it &&
                        !it.equals(TranscriptNormalizer.ME, ignoreCase = true) && ids.none { other -> other.equals(it, ignoreCase = true) } &&
                        it != speaker.name?.trim()
                }?.let { id to it }
            }.orEmpty()
            // Two speakers with one name would be one person to every reader: neither is named, as a fill does.
            val shared = given.groupBy { it.second.lowercase() }.filterValues { it.size > 1 }.keys
            val names = given.filter { it.second.lowercase() !in shared }.toMap()

            var overview: String? = null
            val items = mutableMapOf<String, String>()
            val added = mutableListOf<AskNote>()
            if (summary != null) {
                overview = string(json["overview"])?.trim()?.takeIf { it.isNotEmpty() && it != summary.notes.overview?.trim() }
                    ?.take(NotesAnswer.SUMMARY_MAX)
                for (element in (json["items"] as? JsonArray).orEmpty()) {
                    val item = element as? JsonObject ?: continue
                    val note = summary.items.getOrNull((number(item["n"]) ?: continue) - 1) ?: continue
                    val said = string(item["text"])?.let(::oneLine) ?: continue
                    // The item's time, owner and due date are shown beside it: copied into its words they are dropped, and
                    // words that were only those remove nothing (2026-11-04, 2026-11-05).
                    val text = withoutAbout(NoteText.withoutTimes(said), note.owner, note.due)
                        .takeIf { it.isNotEmpty() || said.isEmpty() }?.take(NotesAnswer.TEXT_MAX) ?: continue
                    if (text != note.text) items[note.id] = text
                }
                for (element in (json["add"] as? JsonArray).orEmpty()) {
                    val item = element as? JsonObject ?: continue
                    val (kind, section) = string(item["section"])?.trim()?.let(summary::place) ?: continue
                    val owner = string(item["owner"])?.trim()?.takeIf { kind == NoteKind.TODO && it in ids }
                    val due = string(item["due"])?.trim()?.takeIf { kind == NoteKind.TODO && it.isNotEmpty() }?.take(NotesAnswer.DUE_MAX)
                    val text = string(item["text"])?.let(::oneLine)?.let(NoteText::withoutTimes)?.let { withoutAbout(it, owner, due) }
                        ?.takeIf { it.isNotEmpty() }?.take(NotesAnswer.TEXT_MAX) ?: continue
                    // Every item comes from a line (2026-11-05): one with none would sit at 00:00:00 with nothing behind it.
                    val segment = number(item["line"])?.let { sent.segments.getOrNull(it - 1) }?.takeIf { it.text.isNotBlank() } ?: continue
                    added += AskNote(kind, section, text, segment.start.coerceAtLeast(0.0), segment.text.trim().take(QUOTE_MAX), owner, due)
                }
            }
            return AskProposal(
                answer = string(json["answer"])?.trim()?.takeIf { it.isNotEmpty() },
                texts = texts, speakers = speakers, names = names, overview = overview, items = items, added = added,
            )
        }

        private fun oneLine(text: String): String = text.replace(Regex("\\s*\\n\\s*"), " ").trim()

        /** [text] without the `(owner: …; due: …)` a summary line gives this item, copied after its words. */
        private fun withoutAbout(text: String, owner: String?, due: String?): String =
            AskPrompts.about(owner, due)?.let { text.removeSuffix(it).trimEnd() } ?: text

        private fun number(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

        private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** docs/08 "Ask": a proposal made of the transcript as it was sent, applied to the transcript as it is now. */
internal object AskEdits {
    /**
     * The edits that make [proposal]'s transcript changes of [current] — a segment, a speaker or a name the user changed
     * while ChatGPT answered keeps the user's change. Null when the transcript was replaced meanwhile (transcribed again):
     * the proposal is about another transcript.
     */
    fun transcript(sent: Transcript, current: Transcript, proposal: AskProposal): List<TranscriptEdit>? {
        if (current.createdAt != sent.createdAt || current.segments.size != sent.segments.size) return null
        val ids = current.speakers.map { it.id }.toSet()
        val speakers = proposal.speakers.entries.sortedBy { it.key }.filter { (index, id) ->
            id in ids && current.segments[index].speaker == sent.segments[index].speaker
        }.map { (index, id) -> TranscriptEdit.SetSpeaker(index, id) }
        val texts = proposal.texts.entries.sortedBy { it.key }.filter { (index, _) ->
            current.segments[index].text.trim() == sent.segments[index].text.trim()
        }.map { (index, text) -> TranscriptEdit.SetText(index, text) }
        val names = proposal.names.filter { (id, _) ->
            val now = current.speakers.firstOrNull { it.id == id } ?: return@filter false
            now.name?.trim().orEmpty() == sent.speakers.firstOrNull { it.id == id }?.name?.trim().orEmpty()
        }.map { (id, name) -> TranscriptEdit.RenameSpeaker(id, name) }
        return speakers + texts + names
    }
}

/**
 * docs/08 "Ask": the request a question of the user's own goes as — one that ChatGPT answers, or that changes the
 * transcript or the summary when it asks for a change, or both. The presets go as plain questions (`askInstructions`).
 */
internal object AskPrompts {
    const val TRANSCRIPT_HEADING = "TRANSCRIPT — one line per segment: #number [HH:MM:SS] speaker id: text"
    const val SPEAKERS_HEADING = "SPEAKERS"
    const val SUMMARY_HEADING = "SUMMARY — its overview, then its items under their sections, one line per item: @number text (owner: …; due: …) [HH:MM:SS], the item's owner, due date and time kept apart from its words"
    const val REQUEST_HEADING = "REQUEST — what the user asks or wants changed, with anything they give to help"

    /** The built-in groups as the request names them, whatever the user calls them. */
    private fun builtInName(kind: NoteKind): String = when (kind) {
        NoteKind.POINT -> "Key points"
        NoteKind.DECISION -> "Decisions"
        NoteKind.TODO -> "To-dos"
        NoteKind.QUESTION -> "Open questions"
        NoteKind.SECTION -> "Section"
    }

    /** The name the user gave a built-in group, which a request may call it by. */
    private fun customName(notes: RecordingNotes, kind: NoteKind): String? = when (kind) {
        NoteKind.POINT -> notes.names.points
        NoteKind.DECISION -> notes.names.decisions
        NoteKind.TODO -> notes.names.todos
        NoteKind.QUESTION -> notes.names.questions
        NoteKind.SECTION -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    fun instructions(transcript: Transcript, summary: AskSummary?, preferences: SummaryPreferences, memo: Boolean = false): String = buildString {
        val speakers = transcript.speakers.isNotEmpty()
        append("You work on one recording for the person who made it: you answer their question about it, or make the change they ask for")
        append(if (summary != null) " in its transcript or its summary" else " in its transcript").append(", or both.\n")
        append("The transcript comes first, one line per segment").append(if (speakers) ", then its speakers" else "")
        append(if (summary != null) ", then the summary made of it" else "").append(if (memo) ", then the user's memo" else "")
        append("; then the request. The request may carry material ")
        append("to help: names, terms, background, or another transcript of the same conversation, perhaps made where the sound was clearer.\n")
        // docs/08 "Ask" (2026-11-05): the memo's heading is the one every request gives it, written for organizing; here
        // only the request asks for a change.
        if (memo) {
            append("The memo is the user's own text about the recording, not part of what was said. Take it as material when the request ")
            append("says to use it; on its own it asks for nothing.\n")
        }
        append("Answer with one JSON object and nothing else: no Markdown, no code fence, no other text.\n")
        append("\"answer\": what you say to the person, in the language of the request. For a question, the answer: plain text, no Markdown ")
        append("(no #, no **, no tables), list items starting with \"- \", the time something was said added as [HH:MM:SS] where it helps; ")
        append("only what the transcript says, and say so when it does not answer the question. For a change, leave it out, or say in one ")
        append("short sentence what you could not change.\n")
        append("Change something only when the request asks for a change — to fix, correct, rename, add, remove or rewrite something. ")
        append("Never change anything to answer a question.\n")
        append("\"lines\": the transcript's segments that change, and only those — each an object with \"n\", the segment's number, and ")
        append("\"text\", the segment's whole corrected text")
        if (speakers) append(", or \"speaker\", one of the speaker ids listed, when another speaker said it, or both")
        append(". Fix what the request asks for and what its material shows the recognition got wrong: misheard words, names, terms and ")
        append("numbers. Keep everything else exactly as it is: the wording, the language each line is in, filler words and the style of ")
        append("punctuation. Never summarize, translate, shorten or reword a line, never add what was not said in it, and never move words ")
        append("from one segment to another.\n")
        if (speakers) {
            append("\"names\": an object from speaker id to that speaker's name, only for a speaker the request or its material says who they ")
            append("are. Never guess a name from how someone speaks or what they talk about.\n")
            // As the fills say it (docs/08 "Titles and speaker names"): the people a memo lists are who may be there.
            if (memo) append("A name in the memo may be one of the speakers', but give it only where the transcript itself shows which speaker it is: a role or anything else the memo says about someone is not enough.\n")
            if (transcript.speakers.any { it.me == true }) {
                append("The speaker marked \"${TranscriptNormalizer.ME}\" is the person who made the recording.\n")
            }
        }
        if (summary != null) {
            append("\"overview\": the summary's whole overview, rewritten, only when the request asks to change it.\n")
            append("\"items\": the summary's items that change, each {\"n\": the item's number, \"text\": its whole new text}, or ")
            append("\"text\": \"\" to remove it.\n")
            append("\"add\": items to add to the summary, each {\"section\": one of the section ids listed, \"text\": the item, \"line\": the ")
            append("number of the transcript segment it comes from}, and for a to-do \"owner\", the speaker id who does it, and \"due\", ")
            append("its due date as it was said.\n")
            append("Add only what the transcript says, each item with the line it comes from. For anything it does not say — something ")
            append("only in the request").append(if (memo) " or the memo" else "").append(" — add nothing, and say so in \"answer\".\n")
            append("Write the summary's words in the language the summary is in, in the style of its other items.\n")
            append("An item's text is its words alone, never a time, owner or due date: the (owner: …; due: …) and the [HH:MM:SS] that end ")
            append("a summary line are kept apart and shown beside it; an added item's time is its line's, and its owner and due date go in ")
            append("\"owner\" and \"due\".\n")
            append("A name or term corrected in the transcript is corrected wherever the summary has it too, and the other way round.\n")
        }
        preferences.aboutMe.trim().takeIf { it.isNotEmpty() }?.let {
            append("About the person who made the recording, to judge what matters to them: ").append(it).append('\n')
        }
        append("Leave out every key you have nothing for.")
    }

    fun input(transcript: Transcript, summary: AskSummary?, memo: String, request: String): String = buildString {
        append(TRANSCRIPT_HEADING).append('\n')
        transcript.segments.forEachIndexed { index, segment ->
            if (segment.text.isBlank()) return@forEachIndexed
            append('#').append(index + 1).append(" [").append(clock(segment.start)).append("] ")
            if (segment.speaker.isNotEmpty()) append(segment.speaker)
            append(": ").append(segment.text.trim()).append('\n')
        }
        if (transcript.speakers.isNotEmpty()) {
            append('\n').append(SPEAKERS_HEADING).append('\n')
            for (speaker in transcript.speakers) {
                append(speaker.id).append(": ")
                append(
                    when {
                        !speaker.name.isNullOrBlank() -> speaker.name.trim()
                        speaker.me == true -> "${TranscriptNormalizer.ME} — the person who made the recording"
                        else -> "no name"
                    },
                ).append('\n')
            }
        }
        if (summary != null) {
            append('\n').append(SUMMARY_HEADING).append('\n')
            append("Overview: ").append(summary.notes.overview?.trim()?.takeIf { it.isNotEmpty() } ?: "(none)").append('\n')
            var number = 0
            val numbered = summary.items.associateWith { ++number }
            for (section in summary.sections) {
                append("Section ").append(section.id).append(" — ")
                append(if (section.builtIn) builtInName(section.kind) else section.name)
                customName(summary.notes, section.kind)?.let { append(" (the user calls it \"").append(it).append("\")") }
                section.description.trim().takeIf { !section.builtIn && it.isNotEmpty() }?.let { append(": ").append(it) }
                append('\n')
                val items = summary.items.filter { if (section.builtIn) it.kind == section.kind else it.section == section.id }
                if (items.isEmpty()) append("(none)\n")
                for (item in items) {
                    append('@').append(numbered.getValue(item)).append(' ').append(item.text)
                    about(item.owner, item.due)?.let { append(' ').append(it) }
                    append(" [").append(clock(item.atSec)).append("]\n")
                }
            }
        }
        NotesPrompts.memoBlock(memo).takeIf { it.isNotEmpty() }?.let { append('\n').append(it.trimEnd()).append('\n') }
        append('\n').append(REQUEST_HEADING).append('\n').append(request.trim()).append('\n')
    }

    /** What a summary line says after an item's words: `(owner: …; due: …)`, or null when it has neither. */
    fun about(owner: String?, due: String?): String? =
        listOfNotNull(owner?.let { "owner: $it" }, due?.let { "due: $it" }).takeIf { it.isNotEmpty() }?.joinToString("; ", "(", ")")

    private fun clock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        fun two(value: Long) = value.toString().padStart(2, '0')
        return "${two(total / 3600)}:${two(total % 3600 / 60)}:${two(total % 60)}"
    }
}
