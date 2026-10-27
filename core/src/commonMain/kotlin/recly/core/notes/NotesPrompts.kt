package recly.core.notes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import recly.core.chatgpt.ChatGptFailure
import recly.core.message.CoreMessage
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.providerJson

/**
 * docs/08 "Organizing": what the user's ChatGPT plan is asked. The instructions are fixed English, like a
 * summary's; the transcript lines are `[HH:MM:SS] S1: text` with placeholders for numbers and addresses
 * ([Placeholders]); every answer is one JSON object, read leniently as a fill's is.
 */
internal object NotesPrompts {
    /** The heading of the items already shown, the previous part's end and this part, in a provisional request. */
    const val SO_FAR = "NOTES SO FAR:"
    const val PREVIOUS = "PREVIOUS PART (its end, for context only):"
    const val THIS_PART = "THIS PART:"
    const val TRANSCRIPT = "TRANSCRIPT:"
    const val CONVERSATIONS = "CONVERSATIONS:"

    private const val LINES = "Each transcript line starts with [HH:MM:SS], its time on the recording, then a speaker label such as S1 when there is one " +
        "— a label only tells voices apart and is not a name — and what was said.\n"

    private const val JSON_ONLY = "Answer with one JSON object and nothing else: no Markdown, no code fence, no other text.\n"

    private fun item(language: String) = """Each item is an object with:
"kind": "decision" for something the people settled, "todo" for something someone said they or another person will do, or "question" for a question raised and not answered;
"text": the item in one short sentence, in $language;
"owner": for a todo, the speaker label of the person who will do it when the transcript makes that clear, else null; null for the other kinds;
"due": for a todo, the deadline as it was said, in $language, or null when none was said;
"at": the time of the line the evidence is on, HH:MM:SS, copied from that line;
"quote": a contiguous stretch of that line copied character for character, about 5 to 30 words. Never translate, correct, shorten inside or paraphrase it.
"""

    private const val EVIDENCE = "No evidence, no item: do not infer what was not said. Small talk, generic remarks and hypotheticals are not decisions or to-dos. " +
        "Prefer a few clearly supported items to many weak ones.\n" +
        "Text in square brackets such as [phone#1] stands for something left out; copy it as it is.\n"

    /** While recording: the new items of one part, never the ones already shown. */
    fun provisional(language: String): String = buildString {
        append("You keep running notes of a conversation while it is being recorded, one part of the recording at a time: its decisions, to-dos and open questions.\n")
        append(LINES)
        append("The message holds $SO_FAR (what the user already sees), $PREVIOUS and $THIS_PART.\n")
        append(JSON_ONLY)
        append("""{"items": [...]}""").append('\n')
        append(item(language))
        append("Report only new items whose evidence is in THIS PART. Never repeat or reword an item of NOTES SO FAR. ")
        append("When this part changes one of them — a decision reversed, a question answered, a deadline moved — add a new item that says so.\n")
        append(EVIDENCE)
        append("""When there is nothing new, answer {"items": []}.""")
    }

    /** After Stop: one conversation's summary and items. */
    fun conversation(language: String): String = buildString {
        append("You write the notes of one conversation from its transcript: a short summary, and its decisions, to-dos and open questions.\n")
        append(LINES)
        append(JSON_ONLY)
        append("""{"summary": "...", "items": [...]}""").append('\n')
        append("\"summary\": one or two sentences in $language on what the conversation was about and where it ended. Plain text.\n")
        append(item(language))
        append("A decision is reported as it stands at the end: one changed later is reported as changed. A question is reported only when it is still unanswered at the end.\n")
        append(EVIDENCE)
        append("""When there is nothing to note, answer with the summary and "items": [].""")
    }

    /** After Stop, for a long stretch without a long silence: where its conversations change. */
    fun boundaries(): String = buildString {
        append("The transcript below is one stretch of a long recording with no long silence in it. It may hold more than one conversation — a meeting, then a call; one group of people, then another.\n")
        append(LINES)
        append("Find where a new conversation begins: a change of the people talking, of place or of occasion — not a change of subject within the same conversation.\n")
        append(JSON_ONLY)
        append("""{"boundaries": ["HH:MM:SS", ...]} — the time of the first line of each new conversation, copied from that line, in order; {"boundaries": []} when it is all one conversation.""")
    }

    /** After Stop, for a recording of more than one conversation: its overview, from their summaries. */
    fun overview(language: String): String = buildString {
        append("Below are the summaries of the conversations of one recording, in order, each with its time span.\n")
        append("Write an overview of the whole recording in two or three short lines, in $language: what happened over the recording. Plain text, no Markdown, no list markers.\n")
        append(JSON_ONLY)
        append("""{"overview": "..."}""")
    }

    /** The notes so far, the previous part's end and this part's lines. */
    fun provisionalInput(shown: List<StoredNote>, previous: String, part: String): String = buildString {
        append(SO_FAR).append('\n')
        if (shown.isEmpty()) append("(none yet)\n")
        shown.forEach { note ->
            append("- ").append(note.kind.wire()).append(": ").append(note.text)
            note.due?.let { append(" (due: ").append(it).append(')') }
            append(" [").append(TranscriptNormalizer.clock(note.evidence.atSec)).append("]\n")
        }
        append('\n').append(PREVIOUS).append('\n')
        append(previous.ifBlank { "(none)" }).append("\n\n")
        append(THIS_PART).append('\n').append(part).append('\n')
    }

    fun transcriptInput(lines: String): String = "$TRANSCRIPT\n$lines\n"

    fun overviewInput(conversations: List<NoteConversation>): String = buildString {
        append(CONVERSATIONS).append('\n')
        conversations.forEach {
            append("- ").append(TranscriptNormalizer.clock(it.startSec)).append('–').append(TranscriptNormalizer.clock(it.endSec))
                .append(": ").append(it.summary).append('\n')
        }
    }

    fun NoteKind.wire(): String = when (this) {
        NoteKind.DECISION -> "decision"
        NoteKind.TODO -> "todo"
        NoteKind.QUESTION -> "question"
    }
}

/** An item as an answer gave it, its placeholders put back; checked before it is kept. */
@kotlinx.serialization.Serializable
internal data class Extracted(
    val kind: NoteKind,
    val text: String,
    val owner: String?,
    val due: String?,
    val atSec: Double,
    val quote: String,
)

/** docs/08 "Organizing": the answers, read leniently — the object inside any fence or prose — and only for what was asked. */
internal object NotesAnswer {
    const val TEXT_MAX = 500
    const val DUE_MAX = 100
    const val SUMMARY_MAX = 600

    private val OWNER = Regex("""S\d{1,3}""")
    private val CLOCK = Regex("""(\d{1,3}):(\d{2})(?::(\d{2}))?(?:\.\d+)?""")

    /** The JSON object in [text]; an answer with none is a [ChatGptFailure], which a pass may retry. */
    fun parse(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return (if (start in 0 until end) runCatching { providerJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() else null)
            ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable answer"))
    }

    /**
     * The well-formed items of [json]: a known kind, a text, a time and a quote; [restore] puts the placeholders
     * back. An owner that is not a speaker label is dropped, and owners only go with to-dos. Items that come
     * without what they need are counted, not kept.
     */
    fun items(json: JsonObject, restore: (String) -> String): List<Extracted> =
        (json["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val kind = when (string(item["kind"])?.trim()?.lowercase()) {
                "decision" -> NoteKind.DECISION
                "todo", "to-do", "commitment", "action" -> NoteKind.TODO
                "question" -> NoteKind.QUESTION
                else -> return@mapNotNull null
            }
            val text = string(item["text"])?.let(restore)?.trim()?.lineSequence()?.joinToString(" ")?.takeIf { it.isNotEmpty() }?.take(TEXT_MAX)
                ?: return@mapNotNull null
            val atSec = seconds(item["at"] ?: item["atSec"]) ?: return@mapNotNull null
            val quote = string(item["quote"])?.let(restore)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val owner = string(item["owner"])?.trim()?.takeIf { kind == NoteKind.TODO && OWNER.matches(it) }
            val due = string(item["due"])?.let(restore)?.trim()?.takeIf { it.isNotEmpty() && kind == NoteKind.TODO }?.take(DUE_MAX)
            Extracted(kind, text, owner, due, atSec, quote)
        }

    /** Every item the answer tried to give, well-formed or not — to say how many were dropped. */
    fun offered(json: JsonObject): Int = (json["items"] as? JsonArray)?.size ?: 0

    fun summary(json: JsonObject, restore: (String) -> String): String? =
        string(json["summary"])?.let(restore)?.trim()?.takeIf { it.isNotEmpty() }?.take(SUMMARY_MAX)

    fun overview(json: JsonObject, restore: (String) -> String): String? =
        string(json["overview"])?.let(restore)?.trim()?.takeIf { it.isNotEmpty() }?.take(SUMMARY_MAX)

    fun boundaries(json: JsonObject): List<Double> = (json["boundaries"] as? JsonArray).orEmpty().mapNotNull(::seconds)

    /** `HH:MM:SS`, `[HH:MM:SS]` or `MM:SS` — minutes and seconds up to 59 — or a number of seconds. */
    fun seconds(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return primitive.doubleOrNull?.takeIf { it >= 0 && it.isFinite() }
        val match = CLOCK.find(primitive.content) ?: return null
        val (a, b, c) = match.destructured
        if (b.toInt() > 59 || (c.isNotEmpty() && c.toInt() > 59)) return null
        return if (c.isEmpty()) a.toInt() * 60.0 + b.toInt() else a.toInt() * 3600.0 + b.toInt() * 60 + c.toInt()
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
