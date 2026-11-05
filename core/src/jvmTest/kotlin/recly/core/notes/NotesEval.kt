package recly.core.notes

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import recly.core.chatgpt.ChatGptFailure
import recly.core.chatgpt.SummaryLanguage
import recly.core.model.recJson
import recly.core.transcribe.Transcript

/**
 * scripts/notes-eval: the notes bench's requests, made by the app's own code. Not a test: `make test` compiles it and
 * never runs it. `./gradlew :core:notesEvalRender --args="<cases> <out> [<answers>]"`, which
 * `scripts/notes-eval/notes_eval.py render` runs.
 *
 * Each folder under <cases> holds a transcript in Recly's format (`transcript.json`) and its ground truth
 * (`truth.json`, of which only `appLocale` is read here). For each, <out>/<case>/ gets `instructions.txt` and
 * `input.txt` — what [Notes]' final pass sends for a recording of one conversation, with Settings → Notes as they
 * come and no memo — and `request.json`: the language the notes are asked in, the placeholders the input hides and
 * the transcript's hash. With <answers>, `<answers>/<case>.txt` is read as [Notes] reads an answer, and what it
 * would keep goes to <out>/<case>/app-items-<hash>.json, for the scorer to check its own reading against.
 */
fun main(args: Array<String>) {
    require(args.size in 2..3) { "usage: <cases> <out> [<answers>]" }
    val cases = File(args[0])
    val out = File(args[1])
    val answers = args.getOrNull(2)?.let(::File)
    val folders = cases.listFiles().orEmpty().filter { File(it, "transcript.json").isFile }.sortedBy { it.name }
    require(folders.isNotEmpty()) { "no case under $cases" }
    for (folder in folders) {
        NotesEval.render(folder, File(out, folder.name), answers?.let { File(it, "${folder.name}.txt") }?.takeIf { it.isFile })
        println("notes-eval: rendered ${folder.name}")
    }
}

internal object NotesEval {
    private val pretty = Json { prettyPrint = true }

    /** The hidden values in a request; [Placeholders] makes them, this only finds them again. */
    private val PLACEHOLDER = Regex("""\[(?:email|phone|number)#\d+]""")

    fun render(folder: File, out: File, answer: File?) {
        val bytes = File(folder, "transcript.json").readBytes()
        val transcript = recJson.decodeFromString(Transcript.serializer(), bytes.decodeToString())
        val appLocale = recJson.parseToJsonElement(File(folder, "truth.json").readText()).jsonObject.getValue("appLocale").jsonPrimitive.content

        // Notes.finalPass for a recording of one conversation: one block, no boundaries request, no overview request.
        val segments = transcript.segments.filter { it.text.isNotBlank() }.sortedBy { it.start }
        val spans = ConversationSplit.blocks(segments)
        check(spans.size == 1 && spans.single().let { it.endSec - it.startSec } <= ConversationSplit.LONG_SEC) {
            "${folder.name}: a case must be one conversation, asked about in one request"
        }
        val lines = ConversationSplit.assign(segments, spans).single()
        // Notes.conversation: below this nothing is asked.
        check(NoteText.words(lines.joinToString(" ") { it.text }) >= Notes.MIN_WORDS) { "${folder.name}: too few words to be asked about" }
        val ask = NotesAsk()
        val language = SummaryLanguage.of(transcript.language, appLocale)
        val placeholders = Placeholders()
        val instructions = NotesPrompts.conversation(language, ask)
        val input = placeholders.hide(NotesPrompts.transcriptInput(NoteLines.render(NoteLines.of(lines)), ask.memo))
        val hidden = PLACEHOLDER.findAll(input).map { it.value }.distinct().associateWith(placeholders::restore)
        check(hidden.size == placeholders.hidden) { "${folder.name}: ${placeholders.hidden} values hidden, ${hidden.size} found" }

        out.mkdirs()
        File(out, "instructions.txt").writeText(instructions)
        File(out, "input.txt").writeText(input)
        val request = buildJsonObject {
            put("case", folder.name)
            put("transcriptLanguage", transcript.language)
            put("appLocale", appLocale)
            put("language", language)
            put("transcriptSha256", sha256(bytes))
            putJsonObject("placeholders") { hidden.forEach { (token, value) -> put(token, value) } }
        }
        File(out, "request.json").writeText(pretty.encodeToString(JsonObject.serializer(), request) + "\n")
        if (answer != null) File(out, "app-items-${sha256(answer.readBytes()).take(12)}.json").writeText(read(answer, transcript, ask, placeholders))
    }

    /** Notes.conversation from the answer on: what the final pass would keep of [answer], and what it would drop. */
    private fun read(answer: File, transcript: Transcript, ask: NotesAsk, placeholders: Placeholders): String {
        val bytes = answer.readBytes()
        val json = try {
            NotesAnswer.parse(bytes.decodeToString())
        } catch (e: ChatGptFailure) {
            null
        }
        val speakers = transcript.speakers.map { it.id }.toSet()
        val result = buildJsonObject {
            put("answer", answer.name)
            put("answerSha256", sha256(bytes))
            put("readable", json != null)
            if (json != null) {
                put("summary", NotesAnswer.summary(json, placeholders::restore))
                put("offered", NotesAnswer.offered(json))
                putJsonArray("items") {
                    NotesAnswer.items(json, placeholders::restore, ask, points = true).forEach { item ->
                        addJsonObject {
                            put("kind", with(NotesPrompts) { item.kind.wire() })
                            put("text", item.text)
                            put("owner", item.owner?.takeIf { it in speakers })
                            put("due", item.due)
                            put("atSec", item.atSec)
                            put("quote", item.quote)
                            put("kept", QuoteCheck.passes(item.quote, item.atSec, transcript.segments))
                        }
                    }
                }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), result) + "\n"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
