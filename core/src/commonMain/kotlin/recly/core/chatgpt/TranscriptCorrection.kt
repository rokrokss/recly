package recly.core.chatgpt

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import recly.core.message.CoreMessage
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptEdits
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.providerJson

/** What `ReclyCore.correctTranscript` did (docs/08 "Correcting"). Concrete classes, so Swift reads them as they are. */
sealed interface CorrectionResult {
    /**
     * Saved, as an edit is: [segments] are the indices of the segments whose words or speaker changed, [names] how many
     * speakers were named. [previousVersionId] is the version it replaced, which `restoreTranscript` brings back
     * (docs/08 "Versions"); null when none was kept.
     */
    data class Corrected(val segments: List<Int>, val names: Int, val previousVersionId: String?) : CorrectionResult

    /** ChatGPT answered with nothing to change, or nothing it named was still as it had been sent. Nothing was saved. */
    data object NothingToCorrect : CorrectionResult

    /** There is no transcript, here or in the recording's folder, or nothing was said in it. */
    data object NoTranscript : CorrectionResult

    /**
     * A job of the recording has not settled, it is being transcribed here, it was transcribed again while ChatGPT
     * answered, or it is already being corrected — the rule of an edit. Nothing was saved.
     */
    data object Busy : CorrectionResult

    /**
     * [reason] is a [CoreMessage] wire code: `CHATGPT_SIGN_IN_REQUIRED`, `CHATGPT_USAGE_LIMIT`, `PROVIDER_ERROR`, … and, on
     * iPhone before anything is sent, `TRANSFER_CONSENT_REQUIRED`.
     */
    data class Failed(val reason: String) : CorrectionResult
}

/** docs/08 "Correcting": the request a shell's field takes. */
object CorrectionRequest {
    /** The longest request: room for another transcript of a two-hour conversation pasted under it. */
    const val MAX: Int = 200_000
}

/**
 * docs/08 "Correcting": what ChatGPT asked to change in the transcript it was sent — words by segment index, speakers by
 * segment index, and names by speaker id. Indices and ids are the sent transcript's.
 */
internal data class CorrectionProposal(
    val texts: Map<Int, String>,
    val speakers: Map<Int, String>,
    val names: Map<String, String>,
) {
    val empty: Boolean get() = texts.isEmpty() && speakers.isEmpty() && names.isEmpty()

    companion object {
        /** The longest words a segment is given: anything longer is not a correction of a segment. */
        const val TEXT_MAX = 5000

        /**
         * The JSON object in [answer], read leniently — a code fence or words around it are skipped — keeping only what
         * fits [sent]: a segment that is there and was said, words on one line that differ from its own, a speaker it
         * has, and names for its speakers as `Fill in speaker names` takes them. An answer with no object in it is a
         * [ChatGptFailure].
         */
        fun parse(answer: String, sent: Transcript): CorrectionProposal {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            val json = (if (start in 0 until end) runCatching { providerJson.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject }.getOrNull() else null)
                ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable answer"))
            val ids = sent.speakers.map { it.id }.toSet()
            val texts = mutableMapOf<Int, String>()
            val speakers = mutableMapOf<Int, String>()
            for (line in (json["lines"] as? JsonArray).orEmpty()) {
                val item = line as? JsonObject ?: continue
                val index = ((item["n"] as? JsonPrimitive)?.intOrNull ?: continue) - 1
                val segment = sent.segments.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: continue
                string(item["text"])?.let { words -> words.replace(Regex("\\s*\\n\\s*"), " ").trim() }
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
            return CorrectionProposal(texts, speakers, given.filter { it.second.lowercase() !in shared }.toMap())
        }

        private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** docs/08 "Correcting": a proposal made of the transcript as it was sent, applied to the transcript as it is now. */
internal object CorrectionEdits {
    /**
     * The edits that make [proposal] of [current] — a segment, a speaker or a name the user changed while ChatGPT
     * answered keeps the user's change. Null when the transcript was replaced meanwhile (transcribed again): the
     * proposal is about another transcript.
     */
    fun of(sent: Transcript, current: Transcript, proposal: CorrectionProposal): List<TranscriptEdit>? {
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
 * docs/08 "Correcting": Correct transcript… — the user's request, with whatever they paste to help (names, terms,
 * background, another transcript of the same conversation), and the transcript go to the user's ChatGPT plan, and
 * what it answers is saved as an edit of the transcript, which a version kept before it undoes. On request only.
 */
internal class TranscriptCorrection(
    private val deps: CoreDeps,
    private val summaries: Summaries,
    private val account: ChatGptAccount,
    private val consents: TransferConsents,
    /** The recording's transcript as its detail shows it: this device's copy, or its folder's. */
    private val transcript: suspend (recordingId: String) -> Transcript?,
    /** A job of the recording has not settled, or it is being transcribed here: an edit of it would be refused. */
    private val busy: suspend (recordingId: String) -> Boolean,
    /** The user's memo of the recording, empty when there is none (docs/08 "Memo"). */
    private val memo: suspend (recordingId: String) -> String,
    /** Saves [proposal], made of [sent], over the transcript as it is now, as `ReclyCore.editTranscript` saves an edit. */
    private val apply: suspend (recordingId: String, sent: Transcript, proposal: CorrectionProposal) -> CorrectionResult,
) {
    private val lock = Mutex()
    private val running = mutableSetOf<String>()

    suspend fun correct(recordingId: String, request: String): CorrectionResult {
        val asked = request.trim().take(REQUEST_MAX)
        if (asked.isEmpty()) return CorrectionResult.NothingToCorrect
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) {
            return CorrectionResult.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code())
        }
        if (!lock.withLock { running.add(recordingId) }) return CorrectionResult.Busy
        try {
            return attempt(recordingId, asked)
        } finally {
            lock.withLock { running.remove(recordingId) }
        }
    }

    private suspend fun attempt(recordingId: String, request: String): CorrectionResult {
        if (busy(recordingId)) return CorrectionResult.Busy
        val sent = transcript(recordingId)?.takeIf { t -> t.segments.any { it.text.isNotBlank() } } ?: return CorrectionResult.NoTranscript
        val outcome = try {
            val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
            deps.logger.log(
                Logger.Level.INFO, "summary.correct.start",
                mapOf("recordingId" to recordingId, "model" to model, "segments" to sent.segments.size, "chars" to request.length),
            )
            val answer = summaries.complete(model, instructions(sent), input(sent, memo(recordingId), request), timeoutSec = TIMEOUT_SEC)
            val proposal = CorrectionProposal.parse(answer, sent)
            if (proposal.empty) CorrectionResult.NothingToCorrect else apply(recordingId, sent, proposal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptFailure) {
            CorrectionResult.Failed(e.reason)
        } catch (e: Exception) {
            CorrectionResult.Failed(CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName))
        }
        when (outcome) {
            is CorrectionResult.Failed -> deps.logger.log(
                Logger.Level.WARN, "summary.correct.failed",
                mapOf("recordingId" to recordingId, "reason" to outcome.reason.substringBefore(CoreMessage.DETAIL).substringBefore(CoreMessage.SEPARATOR)),
            )
            is CorrectionResult.Corrected -> deps.logger.log(
                Logger.Level.INFO, "summary.correct.done",
                mapOf("recordingId" to recordingId, "segments" to outcome.segments.size, "names" to outcome.names),
            )
            else -> deps.logger.log(
                Logger.Level.INFO, "summary.correct.done",
                mapOf("recordingId" to recordingId, "segments" to 0, "names" to 0, "outcome" to outcome::class.simpleName.orEmpty()),
            )
        }
        return outcome
    }

    internal companion object {
        const val REQUEST_MAX = CorrectionRequest.MAX

        /** A long transcript, and a reasoning model comparing it line by line with another, can take many minutes. */
        const val TIMEOUT_SEC = 1800

        const val TRANSCRIPT_HEADING = "TRANSCRIPT — one line per segment: #number [HH:MM:SS] speaker id: text"
        const val SPEAKERS_HEADING = "SPEAKERS"
        const val MEMO_HEADING = "MEMO — the user's own memo about this recording; names and terms in it are spelled as the user spells them."
        const val REQUEST_HEADING = "REQUEST — what the user asks, with anything they give to help"

        fun instructions(transcript: Transcript): String = buildString {
            val speakers = transcript.speakers.isNotEmpty()
            append("You correct a transcript that speech recognition made of a recording. The transcript comes first, one line per segment")
            append(if (speakers) ", then its speakers" else "")
            append("; then the user's request, with anything they give to help — names, terms, background, or another transcript of the ")
            append("same conversation, perhaps made where the sound was clearer.\n")
            append("Fix what the request asks for, and what its material shows the recognition got wrong: misheard words, names, terms and numbers")
            append(if (speakers) ", and who said a line" else "").append(". ")
            append("Keep everything else exactly as it is: the wording, the language each line is in, filler words and the style of punctuation. ")
            append("Never summarize, translate, shorten or reword a line, and never add what was not said in it. ")
            append("Each segment keeps its own words: do not move words from one segment to another.\n")
            append("Answer with one JSON object and nothing else: no Markdown, no code fence, no other text.\n")
            append("\"lines\": the segments that change, and only those — each an object with \"n\", the segment's number, and ")
            append("\"text\", the segment's whole corrected text")
            if (speakers) append(", or \"speaker\", one of the speaker ids listed, when another speaker said it, or both")
            append(".\n")
            if (speakers) {
                append("\"names\": an object from speaker id to that speaker's name, only for a speaker the request or its material says who ")
                append("they are; leave it out otherwise.\n")
                if (transcript.speakers.any { it.me == true }) {
                    append("The speaker marked \"${TranscriptNormalizer.ME}\" is the person who made the recording.\n")
                }
            }
            append("With nothing to change, answer {\"lines\": []}.")
        }

        fun input(transcript: Transcript, memo: String, request: String): String = buildString {
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
            memo.trim().takeIf { it.isNotEmpty() }?.let { append('\n').append(MEMO_HEADING).append('\n').append(it).append('\n') }
            append('\n').append(REQUEST_HEADING).append('\n').append(request.trim()).append('\n')
        }

        private fun clock(seconds: Double): String {
            val total = seconds.coerceAtLeast(0.0).toLong()
            fun two(value: Long) = value.toString().padStart(2, '0')
            return "${two(total / 3600)}:${two(total % 3600 / 60)}:${two(total % 60)}"
        }
    }
}
