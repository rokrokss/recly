@file:OptIn(ExperimentalTime::class)

package recly.core.chatgpt

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import recly.core.chatgpt.ChatGptAccount.Companion.API
import recly.core.chatgpt.ChatGptAccount.Companion.failure
import recly.core.chatgpt.ChatGptAccount.Companion.string
import recly.core.db.RecDatabase
import recly.core.message.CoreMessage
import recly.core.platform.CoreDeps
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.Logger
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.recording.RecordingRepository
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdits
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.providerJson

/**
 * docs/08 "AI requests": what this device tells ChatGPT about the user — Settings → Notes. [aboutMe] tells it
 * who the notes and answers are for. Local to this device, like the model, and kept while signed out.
 */
data class SummaryPreferences(
    val aboutMe: String = "",
) {
    companion object {
        const val ABOUT_MAX: Int = 300
    }
}

/** docs/08 "Ask": the questions on offer besides the user's own. */
enum class AskPreset { ACTION_ITEMS, OPEN_QUESTIONS, TRANSLATE, MY_SPEAKING }

/** One answer about one recording; kept in this process only, never written or uploaded (docs/08 "Ask"). */
data class AskAnswer(
    val recordingId: String,
    /** The preset asked, or null for [question]. */
    val preset: AskPreset?,
    val question: String?,
    val text: String,
    val model: String,
    /** The plan's name for [model] (`GPT-5.4`), so the footer reads the same signed out. */
    val modelName: String? = null,
    /**
     * What a question of the user's own changed in the transcript and the summary, saved as it came; null when nothing
     * did, or once it was undone. [text] is empty when the request was only a change.
     */
    val changes: AskChanges? = null,
)

sealed class AskState {
    data object None : AskState()

    data class Running(val preset: AskPreset?, val question: String?) : AskState()

    data class Ready(val answer: AskAnswer) : AskState()

    /** [reason] is a [CoreMessage] wire code. */
    data class Failed(val reason: String, val preset: AskPreset?, val question: String?) : AskState()
}

/** A `[HH:MM:SS]` or `[MM:SS]` in an answer: [length] characters from [offset], pointing at [atSec]. */
data class SummaryCitation(val offset: Int, val length: Int, val atSec: Double)

/**
 * docs/09 "AI view": the one rule every shell reads citations by, so the same text has the same taps
 * everywhere. Minutes and seconds above 59 are not times and are left as text.
 */
object SummaryCitations {
    private val pattern = Regex("""\[(\d{1,3}):(\d{2})(?::(\d{2}))?]""")

    fun parse(text: String): List<SummaryCitation> = pattern.findAll(text).mapNotNull { match ->
        val (a, b, c) = match.destructured
        val seconds = if (c.isEmpty()) {
            if (b.toInt() > 59) return@mapNotNull null
            a.toInt() * 60.0 + b.toInt()
        } else {
            if (b.toInt() > 59 || c.toInt() > 59) return@mapNotNull null
            a.toInt() * 3600.0 + b.toInt() * 60 + c.toInt()
        }
        SummaryCitation(match.range.first, match.value.length, seconds)
    }.toList()
}

/**
 * docs/08 "AI requests": the requests to the user's own ChatGPT plan — Ask about this recording, on request, and
 * the one request, language and preferences the notes (docs/08 "Organizing") and the fills (docs/08 "Titles and
 * speaker names") are made with. Only text goes (docs/15 §10).
 */
class Summaries internal constructor(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val account: ChatGptAccount,
    private val recordings: RecordingRepository,
    private val transcript: suspend (String) -> Transcript?,
    private val consents: TransferConsents,
    /** The user's memo of the recording as a request carries it, empty when there is none (docs/08 "Memo"). */
    private val memo: suspend (String) -> String = { "" },
    /** The recording's final summary as a request shows it, or null while it has none (docs/08 "Ask"). */
    private val summary: suspend (String) -> AskSummary? = { null },
    /** Saves a request's changes, made of the transcript [Transcript] as sent; null when none was saved. */
    private val apply: suspend (String, Transcript, AskSummary?, AskProposal) -> AskApplied? = { _, _, _, _ -> null },
    /** Undoes a request's saved changes; false when any of them could not be. */
    private val undo: suspend (String, AskApplied) -> Boolean = { _, _ -> false },
) {
    private val startLock = Mutex()

    /** An answer goes on when the screen that asked for it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val prefs = MutableStateFlow<SummaryPreferences?>(null)

    /** The app's language, which a shell changes while it runs (docs/08 "AI requests" — the language rule). */
    private val locale = MutableStateFlow(deps.locale)

    /** The app's language changed: later notes and questions follow it, and so does which Ask presets are on offer. */
    fun setLocale(tag: String) {
        if (tag.isNotBlank()) locale.value = tag
    }
    private val asks = MutableStateFlow<Map<String, AskState>>(emptyMap())
    private val askRuns = mutableMapOf<String, Deferred<AskState>>()

    /** The changes on screen, by recording, with what undoes them — this process only, as their answers are. */
    private val applied = MutableStateFlow<Map<String, AskApplied>>(emptyMap())

    /** What a request about one recording is made from (docs/15 §10): its transcript lines. */
    private class Source(val transcript: String, val language: String, val hasMe: Boolean)

    private suspend fun source(recordingId: String): Source {
        val transcript = transcript(recordingId)
        val text = transcript?.let(TranscriptNormalizer::text)?.takeIf { it.isNotBlank() }
            ?: throw ChatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        return Source(
            text,
            SummaryLanguage.of(transcript.language, locale.value),
            transcript.speakers.any { it.me == true },
        )
    }

    /**
     * One request to the plan's Responses API; its text, trimmed and never empty — every request of docs/08 "AI
     * requests": the same endpoint, `store: false`, the same error mapping.
     */
    internal suspend fun complete(model: String, instructions: String, input: String, timeoutSec: Int = 600, effort: String? = null): String {
        val body = buildJsonObject {
            put("model", model)
            effort?.let { putJsonObject("reasoning") { put("effort", it) } }
            put("instructions", instructions)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            // The transcript's lines, nothing else (docs/15 §10): no title, no audio.
                            put("text", input)
                        }
                    }
                }
            }
            // docs/15 §10: nothing is kept at OpenAI for later turns; the plan's API streams its answer.
            put("store", false)
            put("stream", true)
        }
        val result = account.authorized { token ->
            deps.transport.execute(
                HttpPlan(
                    "POST", "$API/responses",
                    headers = mapOf("Authorization" to "Bearer $token", "Accept" to "text/event-stream"),
                    body = HttpBody.Text(body.toString(), "application/json"),
                    followRedirects = false,
                    // A long meeting with a reasoning model can think for minutes before its first word.
                    timeoutSec = timeoutSec,
                ),
            )
        }
        if (result.status !in 200..299) throw failure(result)
        val text = ResponseStream.text(result.body.decodeToString()).trim()
        if (text.isEmpty()) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "empty answer"))
        return text
    }

    /** The language notes on a transcript in [transcriptLanguage] are written in, named for the model ([SummaryLanguage]). */
    internal fun language(transcriptLanguage: String): String = SummaryLanguage.of(transcriptLanguage, locale.value)

    // --- Titles and speaker names (docs/08 "Titles and speaker names") ---

    /**
     * One request for what a fill asks: a title when [title], and a name for each of [speakers] — the transcript's
     * lines, as a question sends them, and the recording's memo. [trigger] is what asked, for the log. Throws
     * [ChatGptFailure]; what ChatGPT could not tell is absent from the answer.
     */
    internal suspend fun fill(recordingId: String, transcript: Transcript, title: Boolean, speakers: List<String>, trigger: String): FillAnswer {
        val lines = TranscriptNormalizer.text(transcript).takeIf { it.isNotBlank() }
            ?: throw ChatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        val memo = recly.core.notes.NotesPrompts.memoBlock(memo(recordingId))
        val text = if (memo.isEmpty()) lines else lines + "\n" + memo
        // The first of the models measured for this that the plan offers, at the effort measured (docs/08 "Titles and speaker
        // names"); otherwise the one the notes use, at its own default effort.
        val offered = account.offered()
        val chosen = FILL_MODELS.firstOrNull { (id, _) -> id in offered }
        val model = chosen?.first ?: account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        val instructions = fillInstructions(
            SummaryLanguage.of(transcript.language, locale.value), title, speakers, transcript.speakers.any { it.me == true }, memo = memo.isNotEmpty(),
        )
        deps.logger.log(Logger.Level.INFO, "summary.fill.model", mapOf("model" to model, "effort" to (chosen?.second ?: "default"), "trigger" to trigger))
        // A transcript waits for this before it goes up: a model that has not answered in two minutes is not waited for.
        val answer = try {
            complete(model, instructions, text, timeoutSec = FILL_TIMEOUT_SEC, effort = chosen?.second)
        } catch (e: ChatGptFailure) {
            // The plan's API documents no `reasoning` field either way: refused, the request goes again without it.
            if (chosen == null || !e.reason.startsWith(CoreMessage.PROVIDER_ERROR.code(detail = "400"))) throw e
            deps.logger.log(Logger.Level.WARN, "summary.fill.effort.refused", mapOf("model" to model, "trigger" to trigger))
            complete(model, instructions, text, timeoutSec = FILL_TIMEOUT_SEC)
        }
        return FillAnswer.parse(answer, title, speakers)
    }

    // --- Preferences (About you in Settings → Notes; the fills' boxes in the title sheet) ---

    /** What Settings shows; read from this device's store the first time. */
    fun observePreferences(): Flow<SummaryPreferences> = flow {
        emit(preferences())
        emitAll(prefs.filterNotNull())
    }.distinctUntilChanged()

    @Throws(Throwable::class)
    suspend fun preferences(): SummaryPreferences = prefs.value ?: withContext(deps.io) {
        SummaryPreferences(
            aboutMe = db.recQueries.kvGet(PREF_ABOUT).executeAsOneOrNull().orEmpty(),
        )
    }.also { loaded -> prefs.compareAndSet(null, loaded) }

    /** Kept trimmed and cut to [SummaryPreferences.ABOUT_MAX] characters; answers what was kept. */
    @Throws(Throwable::class)
    suspend fun setPreferences(preferences: SummaryPreferences): SummaryPreferences {
        val kept = SummaryPreferences(
            aboutMe = preferences.aboutMe.trim().take(SummaryPreferences.ABOUT_MAX),
        )
        withContext(deps.io) {
            db.recQueries.transaction {
                if (kept.aboutMe.isEmpty()) db.recQueries.kvDelete(PREF_ABOUT) else db.recQueries.kvSet(PREF_ABOUT, kept.aboutMe)
            }
        }
        prefs.value = kept
        return kept
    }

    // --- Ask (docs/08 "Ask") ---

    fun observeAsk(recordingId: String): Flow<AskState> = asks.map { it[recordingId] ?: AskState.None }.distinctUntilChanged()

    /** The presets that make sense for [recordingId]: Translate when the app speaks another language, My speaking when the transcript knows who the user is. */
    @Throws(Throwable::class)
    suspend fun askPresets(recordingId: String): List<AskPreset> {
        val transcript = transcript(recordingId) ?: return emptyList()
        return AskPreset.entries.filter { preset ->
            when (preset) {
                AskPreset.TRANSLATE -> SummaryLanguage.translatable(transcript.language, locale.value)
                AskPreset.MY_SPEAKING -> transcript.speakers.any { it.me == true }
                else -> true
            }
        }
    }

    /**
     * Asks ChatGPT [preset], or the user's own [question], about [recordingId]'s transcript, and answers how it ended.
     * A question of the user's own may ask for a change too: what it changes in the transcript or the summary is saved
     * at once, and the answer says what ([AskAnswer.changes]; docs/08 "Ask"). One at a time per recording: while one
     * runs, asking again answers the running one. The answer replaces the last.
     */
    @Throws(Throwable::class)
    suspend fun ask(recordingId: String, preset: AskPreset?, question: String?): AskState {
        val asked = question?.trim()?.takeIf { it.isNotEmpty() }?.take(AskRequest.MAX)
        if (preset == null && asked == null) return asks.value[recordingId] ?: AskState.None
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) {
            return AskState.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code(), preset, asked)
        }
        val run = startLock.withLock {
            askRuns[recordingId]?.takeIf { it.isActive }
                ?: scope.async { runAsk(recordingId, if (asked != null) null else preset, asked) }.also { askRuns[recordingId] = it }
        }
        return run.await()
    }

    /**
     * Closes the answer: the next look at the recording starts with nothing asked. A running question still finishes.
     * Its changes stay; only version history undoes them from then on.
     */
    fun clearAsk(recordingId: String) {
        asks.update { all -> all[recordingId]?.takeUnless { it is AskState.Running }?.let { all - recordingId } ?: all }
        if (asks.value[recordingId] !is AskState.Running) applied.update { it - recordingId }
    }

    /**
     * docs/08 "Ask" — `Undo`: brings the transcript and the summary back to how they were before the answer's changes —
     * each one still as the change left it — and drops the changes from the answer. False when there was nothing to undo,
     * or when any of it was changed again since (the rest is undone).
     */
    @Throws(Throwable::class)
    suspend fun undoAsk(recordingId: String): Boolean {
        val change = applied.value[recordingId] ?: return false
        val undone = undo(recordingId, change)
        applied.update { it - recordingId }
        asks.update { all ->
            val ready = all[recordingId] as? AskState.Ready ?: return@update all
            if (ready.answer.text.isEmpty()) all - recordingId else all + (recordingId to AskState.Ready(ready.answer.copy(changes = null)))
        }
        deps.logger.log(Logger.Level.INFO, "summary.ask.undone", mapOf("recordingId" to recordingId, "complete" to undone))
        return undone
    }

    private suspend fun runAsk(recordingId: String, preset: AskPreset?, question: String?): AskState {
        asks.update { it + (recordingId to AskState.Running(preset, question)) }
        applied.update { it - recordingId }
        val outcome = try {
            if (question != null) {
                request(recordingId, question)
            } else {
                val source = source(recordingId)
                val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
                deps.logger.log(Logger.Level.INFO, "summary.ask.start", mapOf("recordingId" to recordingId, "model" to model, "preset" to preset!!.name))
                val instructions = askInstructions(source.language, SummaryLanguage.app(locale.value), preset, preferences(), source.hasMe)
                val input = source.transcript + "\n" + recly.core.notes.NotesPrompts.memoBlock(memo(recordingId)) +
                    QUESTION_HEADING + "\n" + presetQuestion(preset) + "\n"
                AskState.Ready(AskAnswer(recordingId, preset, null, complete(model, instructions, input), model, account.modelLabel(model)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptFailure) {
            AskState.Failed(e.reason, preset, question)
        } catch (e: Exception) {
            AskState.Failed(CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName), preset, question)
        }
        val changes = (outcome as? AskState.Ready)?.answer?.changes
        deps.logger.log(
            if (outcome is AskState.Failed) Logger.Level.WARN else Logger.Level.INFO,
            if (outcome is AskState.Failed) "summary.ask.failed" else "summary.ask.done",
            buildMap {
                put("recordingId", recordingId)
                if (outcome is AskState.Failed) put("reason", outcome.reason.substringBefore('|'))
                if (changes != null) {
                    put("transcript", changes.transcriptCount)
                    put("summary", changes.summaryCount)
                }
            },
        )
        asks.update { it + (recordingId to outcome) }
        startLock.withLock { askRuns.remove(recordingId) }
        return outcome
    }

    /**
     * A question of the user's own (docs/08 "Ask"): the transcript by segment, its speakers, the final summary by item,
     * the memo and the request go in one request, which ChatGPT answers, changes the transcript or the summary with, or
     * both. The changes are saved before the answer is shown, each one as the user's own edit.
     */
    private suspend fun request(recordingId: String, question: String): AskState {
        val transcript = transcript(recordingId)?.takeIf { t -> t.segments.any { it.text.isNotBlank() } }
            ?: throw ChatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        val summary = summary(recordingId)
        deps.logger.log(
            Logger.Level.INFO, "summary.ask.start",
            mapOf("recordingId" to recordingId, "model" to model, "preset" to "own", "summary" to (summary != null), "chars" to question.length),
        )
        val answer = complete(
            model, AskPrompts.instructions(transcript, summary, preferences()),
            AskPrompts.input(transcript, summary, memo(recordingId), question), timeoutSec = REQUEST_TIMEOUT_SEC,
        )
        val proposal = AskProposal.parse(answer, transcript, summary)
        val saved = if (proposal.changesTranscript || proposal.changesSummary) apply(recordingId, transcript, summary, proposal) else null
        if (saved != null) applied.update { it + (recordingId to saved) }
        return AskState.Ready(
            AskAnswer(recordingId, null, question, proposal.answer.orEmpty(), model, account.modelLabel(model), changes = saved?.changes),
        )
    }

    internal companion object {
        const val PREF_ABOUT = "summary/prefs/about"

        const val FILL_TIMEOUT_SEC = 120

        /**
         * docs/08 "Titles and speaker names": model and reasoning effort, best first, as measured with Codex CLI on 2026-10-15 —
         * twelve transcripts (Korean, English, Japanese, mixed; memos, calls, a lecture, names only talked about), twice
         * each. `gpt-6.1-sol` at `low` named all 56 speakers right and invented none, in 7.5 s (median); `gpt-5.6-terra` at
         * `medium` invented one, in 5.9 s; `gpt-6-luna` invented two or three at any effort, and `gpt-6-astra` took 50 s.
         * `gpt-6.1-terra` took `gpt-5.6-terra`'s place on 2026-10-29 (user decision, 5.6 outdated) at its own default
         * effort, `low`, without being measured.
         */
        val FILL_MODELS: List<Pair<String, String>> = listOf("gpt-6.1-sol" to "low", "gpt-6.1-terra" to "low")

        /** A long transcript, and a reasoning model comparing it line by line with another pasted, can take many minutes. */
        const val REQUEST_TIMEOUT_SEC = 1800

        const val QUESTION_HEADING = "Question:"

        /**
         * docs/08 "Titles and speaker names": a JSON object with a `title` when [title] and a `speakers` object for
         * [speakers] — names only as the transcript itself gives them, never guessed.
         */
        fun fillInstructions(language: String, title: Boolean, speakers: List<String>, hasMe: Boolean = false, memo: Boolean = false): String = buildString {
            append("You fill in details of a recording from its transcript. Each line starts with [HH:MM:SS] and who is speaking.\n")
            append("Answer with one JSON object and nothing else: no Markdown, no code fence, no other text.\n")
            if (title) {
                append("\"title\": a short title for the recording in $language — at most 20 characters in Korean, Japanese or Chinese, ")
                append("at most 40 in any other language — naming what it is about in a few words, not a sentence. ")
                append("No date or time, no quotation marks, no final period.\n")
            }
            if (speakers.isNotEmpty()) {
                append("\"speakers\": an object with one key for each of these speaker ids: ${speakers.joinToString(", ")}. ")
                append("Each value is that speaker's name as the transcript gives it — the name they give for themselves, or the name or form ")
                append("of address the others use when talking to them — or null when the transcript does not make it clear. ")
                append("A name said to someone belongs to the person spoken to, not to the one speaking, and someone who is only talked about is not one of the speakers. ")
                append("Leave out honorifics that only go with a name, such as 님, 씨, さん or Mr.; a form of address used on its own, such as 팀장님, stays as it is. ")
                append("Never guess from how someone speaks or what they talk about, and never give two speakers the same name.\n")
                // docs/08 "Memo": the people a memo lists are who may be there, not who is who.
                if (memo) append("A name in the user's memo may be one of the speakers', but give it only where the transcript itself shows which speaker it is.\n")
            }
            if (hasMe) append("\"${TranscriptNormalizer.ME}\" in the transcript is the person who made the recording; it is not one of the ids.\n")
        }

        /** An answer to one question about the transcript (docs/08 "Ask"). [appLanguage] is the language Translate writes in. */
        fun askInstructions(
            language: String,
            appLanguage: String,
            preset: AskPreset?,
            preferences: SummaryPreferences = SummaryPreferences(),
            hasMe: Boolean = false,
        ): String = buildString {
            append("You answer a question about a recording's transcript. The question follows the transcript.\n")
            append(common(if (preset == AskPreset.TRANSLATE) appLanguage else if (preset == null) "the language of the question" else language))
            append("Where it helps, add the time something was said as [HH:MM:SS], copied from the transcript line.\n")
            append(context(language, preferences, hasMe))
            append("Use only what the transcript says, and say so when it does not answer the question.")
        }

        private fun common(language: String) = """Write everything in $language, including any headings, even where the transcript mixes in other languages.
Use plain text. No Markdown: no #, no **, no tables. Start list items with "- ".
"""

        private fun context(language: String, preferences: SummaryPreferences, hasMe: Boolean) = buildString {
            if (hasMe) append("\"${TranscriptNormalizer.ME}\" in the transcript is the person who made the recording; refer to them with the word for \"me\" in $language.\n")
            preferences.aboutMe.trim().takeIf { it.isNotEmpty() }?.let {
                append("About the person who made the recording, to judge what matters to them: ").append(it).append('\n')
            }
        }

        /** What each preset asks, in English like the rest of the instructions; the answer comes in the notes' language. */
        fun presetQuestion(preset: AskPreset): String = when (preset) {
            AskPreset.ACTION_ITEMS -> "List only the action items, each \"owner: task\", with the due date when the transcript gives one."
            AskPreset.OPEN_QUESTIONS -> "List the questions and issues that were raised but not settled."
            AskPreset.TRANSLATE -> "Translate the whole transcript, line by line, keeping each line's [HH:MM:SS] time and speaker name."
            AskPreset.MY_SPEAKING -> "Give the person labelled \"${TranscriptNormalizer.ME}\" feedback on how they spoke: clarity, pace, filler words, and how much they talked compared with the others, with examples. Three to six points."
        }
    }
}

/** docs/08 "Titles and speaker names": what ChatGPT suggested — a title, and names by speaker id. */
internal data class FillAnswer(val title: String?, val speakers: Map<String, String>) {
    companion object {
        /** Cut to this, though the instructions ask for 20 or 40: the exported file names keep 100 characters of a title. */
        const val TITLE_MAX = 100

        /**
         * The JSON object in [text], read leniently — a code fence or words around it are skipped — keeping only what was
         * asked for: a title on one line, and the names of [speakers] that fit a speaker's name and that no other speaker
         * was given too. An answer with no object in it is a [ChatGptFailure].
         */
        fun parse(text: String, title: Boolean, speakers: List<String>): FillAnswer {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            val json = (if (start in 0 until end) runCatching { providerJson.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() else null)
                ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable answer"))
            val named = if (title) string(json["title"])?.lineSequence()?.map { it.trim().trim('"', '\'', '“', '”', '「', '」') }?.firstOrNull { it.isNotEmpty() }?.take(TITLE_MAX) else null
            val given = (json["speakers"] as? JsonObject)?.let { all ->
                speakers.mapNotNull { id ->
                    string(all[id])?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= TranscriptEdits.SPEAKER_NAME_MAX && '\n' !in it }
                        ?.takeIf { name -> !name.equals(TranscriptNormalizer.ME, ignoreCase = true) && speakers.none { it.equals(name, ignoreCase = true) } }
                        ?.let { id to it }
                }
            }.orEmpty()
            // Two speakers with one name would be one person to every reader: neither is named.
            val shared = given.groupBy { it.second.lowercase() }.filterValues { it.size > 1 }.keys
            return FillAnswer(named, given.filter { it.second.lowercase() !in shared }.toMap())
        }

        private fun string(element: kotlinx.serialization.json.JsonElement?): String? =
            (element as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * The Responses API's server-sent events, read whole: [recly.core.platform.Transport] hands back the body
 * once the stream has ended. Text comes from `response.output_text.delta`; a stream that
 * ends without `response.completed` is a failure, not a short answer.
 */
internal object ResponseStream {
    fun text(body: String): String {
        if (body.trimStart().startsWith("{")) return outputText(ChatGptAccount.json(httpBody(body)))
        val text = StringBuilder()
        for (block in body.replace("\r\n", "\n").split("\n\n")) {
            val data = block.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { providerJson.parseToJsonElement(data) as JsonObject }.getOrNull()
                ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable stream event"))
            when (event.string("type")) {
                "response.output_text.delta" -> text.append(event.string("delta").orEmpty())
                "response.completed" -> {
                    val response = event["response"] as? JsonObject
                    return text.toString().ifEmpty { response?.let(::outputText).orEmpty() }
                }
                "response.incomplete" -> {
                    val reason = ((event["response"] as? JsonObject)?.get("incomplete_details") as? JsonObject)?.string("reason")
                    throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "incomplete ${reason.orEmpty()}".trim()))
                }
                "response.failed", "error" -> {
                    val error = ((event["response"] as? JsonObject)?.get("error") ?: event["error"]) as? JsonObject
                    val code = error?.string("code") ?: event.string("code")
                    // A usage limit can arrive mid-stream as well as before it (docs "Wait for completed inference").
                    throw ChatGptFailure(
                        if (code == "subscription_sharing_usage_limit_exceeded") CoreMessage.CHATGPT_USAGE_LIMIT.code()
                        else CoreMessage.PROVIDER_ERROR.code(detail = code ?: "response failed"),
                    )
                }
            }
        }
        throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "stream ended early"))
    }

    /** A non-streamed response object: its message items' `output_text` parts, in order. */
    private fun outputText(response: JsonObject): String =
        (response["output"] as? JsonArray).orEmpty().flatMap { item ->
            ((item as? JsonObject)?.get("content") as? JsonArray).orEmpty()
        }.mapNotNull { part ->
            (part as? JsonObject)?.takeIf { it.string("type") == "output_text" }?.string("text")
        }.joinToString("")

    private fun httpBody(body: String) = recly.core.platform.HttpResult(200, emptyMap(), body.encodeToByteArray())
}

/**
 * docs/08 "AI requests": the language notes are written in, named for the model. The transcript's own
 * language when the recording was transcribed in one; the app's language when it was not (`auto`, a mix such
 * as `ko-en`) — so every language Recly speaks gets notes in it, headings included, instead of the model's guess.
 */
internal object SummaryLanguage {
    fun of(transcript: String, appLocale: String): String {
        val app = appLocale.lowercase().replace('_', '-').let { tag -> REGIONAL.firstOrNull { tag.startsWith(it) } ?: primary(tag) }
        val code = when {
            transcript.contains('-') && transcript.lowercase() !in REGIONAL -> transcript.split('-').map(::primary)
                .let { mixed -> if (primary(app) in mixed) primary(app) else mixed.first() }
            primary(transcript) in NAMES || transcript.lowercase() in REGIONAL -> transcript.lowercase()
            else -> app
        }
        val known = code.takeIf { it in NAMES } ?: primary(code).takeIf { it in NAMES } ?: "en"
        return "${NAMES.getValue(known)} ($known)"
    }

    /** The app's own language, named the same way — what Ask → Translate writes in. */
    fun app(appLocale: String): String = of("auto", appLocale)

    /**
     * Ask → Translate is offered when the transcript is in one known language that is not the app's: a mix or an
     * automatic detection is already written up in the app's language.
     */
    fun translatable(transcript: String, appLocale: String): Boolean {
        val tag = transcript.lowercase().replace('_', '-')
        if (tag.contains('-') && tag !in REGIONAL) return false
        if (primary(tag) !in NAMES) return false
        return primary(tag) != primary(appLocale)
    }

    private fun primary(tag: String): String = tag.lowercase().replace('_', '-').substringBefore('-')

    /** Script and region pairs that name one language, not a mix. */
    private val REGIONAL = setOf("zh-cn", "zh-tw", "zh-hans", "zh-hant", "pt-br", "pt-pt")

    /** The 23 app languages (localization/languages.json) and the transcription languages (`Language`). */
    private val NAMES = mapOf(
        "en" to "English", "ko" to "Korean", "ja" to "Japanese", "zh" to "Chinese",
        "zh-cn" to "Simplified Chinese", "zh-hans" to "Simplified Chinese",
        "zh-tw" to "Traditional Chinese", "zh-hant" to "Traditional Chinese",
        "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese",
        "pt-br" to "Brazilian Portuguese", "pt-pt" to "European Portuguese", "ar" to "Arabic", "hi" to "Hindi",
        "ru" to "Russian", "it" to "Italian", "pl" to "Polish", "tr" to "Turkish", "fil" to "Filipino",
        "bn" to "Bengali", "ur" to "Urdu", "sw" to "Swahili", "vi" to "Vietnamese", "fa" to "Persian",
        "th" to "Thai", "id" to "Indonesian", "nl" to "Dutch", "uk" to "Ukrainian",
    )
}
