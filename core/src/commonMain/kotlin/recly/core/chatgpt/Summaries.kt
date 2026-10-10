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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import recly.core.chatgpt.ChatGptAccount.Companion.API
import recly.core.chatgpt.ChatGptAccount.Companion.failure
import recly.core.chatgpt.ChatGptAccount.Companion.string
import recly.core.db.RecDatabase
import recly.core.message.CoreMessage
import recly.core.model.Highlight
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.Logger
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.recording.RecordingRepository
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.providerJson

/**
 * Meeting notes ChatGPT wrote from one recording's transcript (docs/08 "Summaries"). [createdAt] and [editedAt]
 * are ISO UTC; [editedAt] is set once the user has changed the text.
 */
@Serializable
data class Summary(
    val recordingId: String,
    val text: String,
    val model: String,
    val createdAt: String,
    val editedAt: String? = null,
    /** [SummaryFormat.wire] of the format it was written in; absent for [SummaryFormat.AUTO]. */
    val format: String? = null,
) {
    val summaryFormat: SummaryFormat get() = SummaryFormat.of(format)
}

/** docs/08 "Summaries": the shape a summary takes. [CUSTOM] follows [SummaryPreferences.customFormat]. */
enum class SummaryFormat(val wire: String) {
    AUTO("auto"),
    ONE_ON_ONE("one_on_one"),
    LECTURE("lecture"),
    INTERVIEW("interview"),
    CUSTOM("custom"),
    ;

    companion object {
        fun of(wire: String?): SummaryFormat = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
}

/**
 * docs/08 "Summaries": what this device asks every summary for — Settings → ChatGPT. [format] is the one Summarize
 * uses; [customFormat] is My format's instructions; [aboutMe] tells ChatGPT who the notes are for. Local to this
 * device, like the model, and kept while signed out.
 */
data class SummaryPreferences(
    val format: SummaryFormat = SummaryFormat.AUTO,
    val customFormat: String = "",
    val aboutMe: String = "",
) {
    /** The formats on offer: My format only once it has words. */
    val formats: List<SummaryFormat> get() = SummaryFormat.entries.filter { it != SummaryFormat.CUSTOM || customFormat.isNotBlank() }

    /** [format], or Auto when it is My format without words. */
    val effectiveFormat: SummaryFormat get() = if (format in formats) format else SummaryFormat.AUTO

    companion object {
        const val CUSTOM_MAX: Int = 1000
        const val ABOUT_MAX: Int = 300
    }
}

/** docs/08 "Ask": the questions on offer besides the user's own. */
enum class AskPreset { FOLLOW_UP_EMAIL, ACTION_ITEMS, OPEN_QUESTIONS, TRANSLATE, MY_SPEAKING }

/** One answer about one recording; kept in this process only, never written or uploaded (docs/08 "Ask"). */
data class AskAnswer(
    val recordingId: String,
    /** The preset asked, or null for [question]. */
    val preset: AskPreset?,
    val question: String?,
    val text: String,
    val model: String,
)

sealed class AskState {
    data object None : AskState()

    data class Running(val preset: AskPreset?, val question: String?) : AskState()

    data class Ready(val answer: AskAnswer) : AskState()

    /** [reason] is a [CoreMessage] wire code. */
    data class Failed(val reason: String, val preset: AskPreset?, val question: String?) : AskState()
}

/** A `[HH:MM:SS]` or `[MM:SS]` in a summary or an answer: [length] characters from [offset], pointing at [atSec]. */
data class SummaryCitation(val offset: Int, val length: Int, val atSec: Double)

/**
 * docs/09 "Summary view": the one rule every shell reads citations by, so the same text has the same taps
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

/** What the detail's summary view shows. */
sealed class SummaryState {
    data object None : SummaryState()

    /** [previous] stays on screen under the status line until the new summary replaces it. */
    data class Running(val previous: Summary?) : SummaryState()

    data class Ready(val summary: Summary) : SummaryState()

    /** [reason] is a [CoreMessage] wire code; [previous] is still the recording's summary. */
    data class Failed(val reason: String, val previous: Summary?) : SummaryState()
}

/**
 * docs/08 "Summaries": a summary is something the user asks for, one recording at a time — never a
 * step of the processing plan (ADR-001). Only the transcript's text goes to the user's own ChatGPT plan
 * (docs/15 §10); the result is kept beside the recording's parts on this device and is not uploaded.
 */
class Summaries internal constructor(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val account: ChatGptAccount,
    private val recordings: RecordingRepository,
    private val transcript: suspend (String) -> Transcript?,
    private val consents: TransferConsents,
    /** A summary was made or edited here: it is to go up to the recording's folder (docs/08 "Summaries"). */
    private val published: suspend (String) -> Unit = {},
    /** The recording's folder copy, for a recording opened here before a pull brought it ([SummaryFile]). */
    private val fetch: suspend (String) -> String? = { null },
) {
    /** The runs in progress and the failures of this process; a saved summary is read from its file. */
    private val runs = MutableStateFlow<Map<String, SummaryState>>(emptyMap())
    private val saved = MutableStateFlow(0L)
    private val started = mutableMapOf<String, Deferred<SummaryState>>()
    private val startLock = Mutex()

    /** A summary goes on when the screen that asked for it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val prefs = MutableStateFlow<SummaryPreferences?>(null)
    private val asks = MutableStateFlow<Map<String, AskState>>(emptyMap())
    private val askRuns = mutableMapOf<String, Deferred<AskState>>()

    fun observe(recordingId: String): Flow<SummaryState> =
        combine(runs, saved, account.observe()) { all, _, connection -> all[recordingId]?.takeUnless { stale(it, connection) } }
            .map { it ?: current(recordingId) }
            .distinctUntilChanged()

    @Throws(Throwable::class)
    suspend fun state(recordingId: String): SummaryState =
        runs.value[recordingId]?.takeUnless { stale(it, account.observe().value) } ?: current(recordingId)

    /** "Sign in to ChatGPT" is said until the user has; after that the recording shows what it has. */
    private fun stale(state: SummaryState, connection: ChatGptConnection): Boolean =
        state is SummaryState.Failed && connection is ChatGptConnection.SignedIn &&
            state.reason.startsWith(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.name)

    /**
     * Summarizes [recordingId] — or joins the summary of it already running — and answers how it ended.
     * On iPhone a destination the user has not allowed answers [CoreMessage.TRANSFER_CONSENT_REQUIRED]
     * before anything is sent (docs/15 "iPhone providers").
     */
    @Throws(Throwable::class)
    suspend fun summarize(recordingId: String): SummaryState = summarize(recordingId, null)

    /**
     * Summarize as [format] — More → Summarize as — or, null, as Settings says ([SummaryPreferences.effectiveFormat]).
     * A format that is not on offer is Auto.
     */
    @Throws(Throwable::class)
    suspend fun summarize(recordingId: String, format: SummaryFormat?): SummaryState {
        // Asked before the run starts, so the screen goes straight to the permission dialog.
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) {
            return SummaryState.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code(), saved(recordingId))
        }
        val preferences = preferences()
        val chosen = format?.takeIf { it in preferences.formats } ?: preferences.effectiveFormat
        val run = startLock.withLock {
            started[recordingId]?.takeIf { it.isActive } ?: scope.async { run(recordingId, chosen, preferences) }.also { started[recordingId] = it }
        }
        return run.await()
    }

    private suspend fun run(recordingId: String, format: SummaryFormat, preferences: SummaryPreferences): SummaryState {
        val previous = saved(recordingId)
        runs.update { it + (recordingId to SummaryState.Running(previous)) }
        val outcome = try {
            SummaryState.Ready(save(create(recordingId, format, preferences)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptFailure) {
            SummaryState.Failed(e.reason, previous)
        } catch (e: Exception) {
            SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName), previous)
        }
        if (outcome is SummaryState.Failed) {
            deps.logger.log(Logger.Level.WARN, "summary.failed", mapOf("recordingId" to recordingId, "reason" to outcome.reason.substringBefore('|')))
            runs.update { it + (recordingId to outcome) }
        } else {
            deps.logger.log(Logger.Level.INFO, "summary.done", mapOf("recordingId" to recordingId))
            runs.update { it - recordingId }
            saved.value++
        }
        startLock.withLock { started.remove(recordingId) }
        return outcome
    }

    private suspend fun create(recordingId: String, format: SummaryFormat, preferences: SummaryPreferences): Summary {
        val source = source(recordingId)
        val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        deps.logger.log(Logger.Level.INFO, "summary.start", mapOf("recordingId" to recordingId, "model" to model, "format" to format.wire))
        val instructions = instructions(source.language, format, preferences, source.hasMe, source.marked.isNotEmpty())
        val text = complete(model, instructions, source.input())
        return Summary(recordingId, text, model, deps.clock.now().isoUtc(), format = format.wire.takeIf { format != SummaryFormat.AUTO })
    }

    /** What a request about one recording is made from (docs/15 §10): its transcript lines and the moments the user marked. */
    private class Source(val transcript: String, val language: String, val hasMe: Boolean, val marked: List<String>) {
        fun input(): String = if (marked.isEmpty()) transcript else transcript + "\n" + MARKED_HEADING + "\n" + marked.joinToString("\n") { "- $it" } + "\n"
    }

    private suspend fun source(recordingId: String): Source {
        val transcript = transcript(recordingId)
        val text = transcript?.let(TranscriptNormalizer::text)?.takeIf { it.isNotBlank() }
            ?: throw ChatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        val highlights = recordings.get(recordingId)?.meta?.highlights.orEmpty()
        return Source(
            text,
            SummaryLanguage.of(transcript.language, deps.locale),
            transcript.speakers.any { it.me == true },
            marked(transcript, highlights),
        )
    }

    /** Each highlight as its time and the words said then — `[00:12:34] the words` (docs/08 "Summaries"). */
    private fun marked(transcript: Transcript, highlights: List<Highlight>): List<String> = highlights.map { highlight ->
        val words = TranscriptNormalizer.spokenAt(transcript, highlight.atSec)
        "[${TranscriptNormalizer.clock(highlight.atSec)}]" + (words?.let { " $it" } ?: "")
    }

    /** One request to the plan's Responses API; its text, trimmed and never empty. */
    private suspend fun complete(model: String, instructions: String, input: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            // The transcript's lines and the marked moments, nothing else (docs/15 §10): no title, no audio.
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
                    timeoutSec = 600,
                ),
            )
        }
        if (result.status !in 200..299) throw failure(result)
        val text = ResponseStream.text(result.body.decodeToString()).trim()
        if (text.isEmpty()) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "empty answer"))
        return text
    }

    // --- Preferences (Settings → ChatGPT) ---

    /** What Settings shows; read from this device's store the first time. */
    fun observePreferences(): Flow<SummaryPreferences> = flow {
        emit(preferences())
        emitAll(prefs.filterNotNull())
    }.distinctUntilChanged()

    @Throws(Throwable::class)
    suspend fun preferences(): SummaryPreferences = prefs.value ?: withContext(deps.io) {
        SummaryPreferences(
            format = SummaryFormat.of(db.recQueries.kvGet(PREF_FORMAT).executeAsOneOrNull()),
            customFormat = db.recQueries.kvGet(PREF_CUSTOM).executeAsOneOrNull().orEmpty(),
            aboutMe = db.recQueries.kvGet(PREF_ABOUT).executeAsOneOrNull().orEmpty(),
        )
    }.also { loaded -> prefs.compareAndSet(null, loaded) }

    /** Kept trimmed and cut to [SummaryPreferences.CUSTOM_MAX] and [SummaryPreferences.ABOUT_MAX] characters; answers what was kept. */
    @Throws(Throwable::class)
    suspend fun setPreferences(preferences: SummaryPreferences): SummaryPreferences {
        val kept = SummaryPreferences(
            format = preferences.format,
            customFormat = preferences.customFormat.trim().take(SummaryPreferences.CUSTOM_MAX),
            aboutMe = preferences.aboutMe.trim().take(SummaryPreferences.ABOUT_MAX),
        )
        withContext(deps.io) {
            db.recQueries.transaction {
                db.recQueries.kvSet(PREF_FORMAT, kept.format.wire)
                if (kept.customFormat.isEmpty()) db.recQueries.kvDelete(PREF_CUSTOM) else db.recQueries.kvSet(PREF_CUSTOM, kept.customFormat)
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
                AskPreset.TRANSLATE -> SummaryLanguage.translatable(transcript.language, deps.locale)
                AskPreset.MY_SPEAKING -> transcript.speakers.any { it.me == true }
                else -> true
            }
        }
    }

    /**
     * Asks ChatGPT [preset], or the user's own [question], about [recordingId]'s transcript, and answers how it ended.
     * One at a time per recording: while one runs, asking again answers the running one. The answer replaces the last.
     */
    @Throws(Throwable::class)
    suspend fun ask(recordingId: String, preset: AskPreset?, question: String?): AskState {
        val asked = question?.trim()?.takeIf { it.isNotEmpty() }?.take(ASK_MAX)
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

    /** Closes the answer: the next look at the recording starts with nothing asked. A running question still finishes. */
    fun clearAsk(recordingId: String) {
        asks.update { all -> all[recordingId]?.takeUnless { it is AskState.Running }?.let { all - recordingId } ?: all }
    }

    private suspend fun runAsk(recordingId: String, preset: AskPreset?, question: String?): AskState {
        asks.update { it + (recordingId to AskState.Running(preset, question)) }
        val outcome = try {
            val source = source(recordingId)
            val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
            deps.logger.log(Logger.Level.INFO, "summary.ask.start", mapOf("recordingId" to recordingId, "model" to model, "preset" to (preset?.name ?: "own")))
            val preferences = preferences()
            val instructions = askInstructions(source.language, SummaryLanguage.app(deps.locale), preset, preferences, source.hasMe, source.marked.isNotEmpty())
            val input = source.input() + "\n" + QUESTION_HEADING + "\n" + (question ?: presetQuestion(preset!!)) + "\n"
            AskState.Ready(AskAnswer(recordingId, preset, question, complete(model, instructions, input), model))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatGptFailure) {
            AskState.Failed(e.reason, preset, question)
        } catch (e: Exception) {
            AskState.Failed(CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName), preset, question)
        }
        deps.logger.log(
            if (outcome is AskState.Failed) Logger.Level.WARN else Logger.Level.INFO,
            if (outcome is AskState.Failed) "summary.ask.failed" else "summary.ask.done",
            buildMap {
                put("recordingId", recordingId)
                if (outcome is AskState.Failed) put("reason", outcome.reason.substringBefore('|'))
            },
        )
        asks.update { it + (recordingId to outcome) }
        startLock.withLock { askRuns.remove(recordingId) }
        return outcome
    }

    /**
     * docs/08 "Summaries": the user's own words over ChatGPT's — kept here at once and carried to the
     * recording's folder, so the other devices show the edit. Answers what the recording now has: the summary
     * as it was when the text is unchanged or empty, the current state while a summary of it is running.
     */
    @Throws(Throwable::class)
    suspend fun edit(recordingId: String, text: String): SummaryState {
        if (startLock.withLock { started[recordingId]?.isActive == true }) return state(recordingId)
        val current = saved(recordingId) ?: return SummaryState.None
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == current.text) return SummaryState.Ready(current)
        val edited = save(current.copy(text = trimmed, editedAt = deps.clock.now().isoUtc()))
        runs.update { it - recordingId }
        deps.logger.log(Logger.Level.INFO, "summary.edited", mapOf("recordingId" to recordingId))
        return SummaryState.Ready(edited)
    }

    /** A pull wrote a newer summary from a folder: whoever shows one reads it again. */
    internal fun changed() {
        saved.value++
    }

    /** Kept here, then [published] for the folder. */
    internal suspend fun save(summary: Summary): Summary {
        recordings.summaryWrite {
            recordings.saveSummary(summary.recordingId, providerJson.encodeToString(Summary.serializer(), summary))
        }
        saved.value++
        published(summary.recordingId)
        return summary
    }

    /** Recordings whose folder was already asked for a summary this run, found or not. */
    private val fetched = mutableSetOf<String>()

    private suspend fun current(recordingId: String): SummaryState {
        saved(recordingId)?.let { return SummaryState.Ready(it) }
        // Another device's summary of a recording that no pull has brought yet: its folder is asked once.
        if (!startLock.withLock { fetched.add(recordingId) }) return SummaryState.None
        val json = try {
            fetch(recordingId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return SummaryState.None
        val summary = SummaryFile.decode(json)?.takeIf { it.recordingId == recordingId } ?: return SummaryState.None
        recordings.summaryWrite {
            if (saved(recordingId) == null) recordings.saveSummary(recordingId, json)
        }
        return saved(recordingId)?.let { SummaryState.Ready(it) } ?: SummaryState.Ready(summary)
    }

    private suspend fun saved(recordingId: String): Summary? =
        recordings.summary(recordingId)?.let(SummaryFile::decode)?.takeIf { it.recordingId == recordingId }

    internal companion object {
        const val PREF_FORMAT = "summary/prefs/format"
        const val PREF_CUSTOM = "summary/prefs/custom"
        const val PREF_ABOUT = "summary/prefs/about"

        /** The longest question the user can ask in their own words. */
        const val ASK_MAX = 500

        /** The heading the marked moments follow the transcript under (docs/15 §10). */
        const val MARKED_HEADING = "Moments the user marked as important:"
        const val QUESTION_HEADING = "Question:"

        /**
         * Plain text with "- " lists, because no shell renders Markdown (docs/09 "Summary view"), all of it in
         * [language] — the headings too ([SummaryLanguage]) — shaped by [format], every point with the time it
         * comes from so a tap can play it (docs/08 "Summaries").
         */
        fun instructions(
            language: String,
            format: SummaryFormat = SummaryFormat.AUTO,
            preferences: SummaryPreferences = SummaryPreferences(),
            hasMe: Boolean = false,
            marked: Boolean = false,
        ): String = buildString {
            append("You write notes from a recording's transcript.\n")
            append(common(language))
            append(shape(format, preferences.customFormat))
            append("After each key point, decision, action item and any other list item, add the time it was said as [HH:MM:SS], copied from the transcript line it comes from.\n")
            append("For an action item whose owner the transcript does not name, write the word for \"unassigned\" in $language instead of guessing.\n")
            append(context(language, preferences, hasMe, marked))
            append("Call people what the transcript calls them. Do not add anything the transcript does not say.")
        }

        /** An answer to one question about the transcript (docs/08 "Ask"). [appLanguage] is the language Translate writes in. */
        fun askInstructions(
            language: String,
            appLanguage: String,
            preset: AskPreset?,
            preferences: SummaryPreferences = SummaryPreferences(),
            hasMe: Boolean = false,
            marked: Boolean = false,
        ): String = buildString {
            append("You answer a question about a recording's transcript. The question follows the transcript.\n")
            append(common(if (preset == AskPreset.TRANSLATE) appLanguage else if (preset == null) "the language of the question" else language))
            append("Where it helps, add the time something was said as [HH:MM:SS], copied from the transcript line.\n")
            append(context(language, preferences, hasMe, marked))
            append("Use only what the transcript says, and say so when it does not answer the question.")
        }

        private fun common(language: String) = """Write everything in $language, including any headings, even where the transcript mixes in other languages.
Use plain text. No Markdown: no #, no **, no tables. Start list items with "- ".
"""

        private fun context(language: String, preferences: SummaryPreferences, hasMe: Boolean, marked: Boolean) = buildString {
            if (hasMe) append("\"${TranscriptNormalizer.ME}\" in the transcript is the person who made the recording; refer to them with the word for \"me\" in $language.\n")
            if (marked) append("After the transcript, the user lists moments they marked as important, each with its time and the words said then. Make sure the notes cover what was said around each of them.\n")
            preferences.aboutMe.trim().takeIf { it.isNotEmpty() }?.let {
                append("About the person who made the recording, to judge what matters to them: ").append(it).append('\n')
            }
        }

        private fun shape(format: SummaryFormat, custom: String): String = when (format) {
            SummaryFormat.AUTO -> """Write these sections in this order, each a heading on its own line followed by its content, and leave out a section that would be empty:
Summary: three to five sentences.
Key points: a list.
Decisions: a list.
Action items: a list, each "owner: task", with the due date when the transcript gives one.
"""
            SummaryFormat.ONE_ON_ONE -> """This is a one-on-one conversation. Write these sections in this order, each a heading on its own line followed by its content, and leave out a section that would be empty:
Summary: two or three sentences.
Updates: what each person reported, a list grouped by person.
Feedback: feedback given or asked for, a list.
Decisions: a list.
Action items: a list, each "owner: task", with the due date when the transcript gives one.
"""
            SummaryFormat.LECTURE -> """This is a lecture or a talk. Write these sections in this order, each a heading on its own line followed by its content, and leave out a section that would be empty:
Summary: three to five sentences.
Key ideas: a list, each with one sentence explaining it.
Examples: the examples given, a list.
Questions: the questions asked and their answers, a list.
To review: terms and topics worth going over again, a list.
"""
            SummaryFormat.INTERVIEW -> """This is an interview. Write these sections in this order, each a heading on its own line followed by its content, and leave out a section that would be empty:
Summary: three to five sentences.
Questions and answers: a list, each question asked followed by the answer in one or two sentences.
Notable quotes: short quotes worth keeping, in the speaker's exact words, a list.
Follow-ups: a list.
"""
            SummaryFormat.CUSTOM -> "Follow the user's own instructions for what to write and how to lay it out:\n" + custom.trim() + "\n"
        }

        /** What each preset asks, in English like the rest of the instructions; the answer comes in the summary's language. */
        fun presetQuestion(preset: AskPreset): String = when (preset) {
            AskPreset.FOLLOW_UP_EMAIL -> "Draft a short follow-up email to the other participants: a subject line first, then thanks, what was decided, who does what by when, and the open questions. Put a placeholder such as [name] where the transcript does not give a name."
            AskPreset.ACTION_ITEMS -> "List only the action items, each \"owner: task\", with the due date when the transcript gives one."
            AskPreset.OPEN_QUESTIONS -> "List the questions and issues that were raised but not settled."
            AskPreset.TRANSLATE -> "Translate the whole transcript, line by line, keeping each line's [HH:MM:SS] time and speaker name."
            AskPreset.MY_SPEAKING -> "Give the person labelled \"${TranscriptNormalizer.ME}\" feedback on how they spoke: clarity, pace, filler words, and how much they talked compared with the others, with examples. Three to six points."
        }
    }
}

/**
 * The Responses API's server-sent events, read whole: [recly.core.platform.Transport] hands back the body
 * once the stream has ended. Text comes from `response.output_text.delta`; a stream that
 * ends without `response.completed` is a failure, not a short summary.
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
 * docs/08 "Summaries": the language a summary is written in, named for the model. The transcript's own
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

/**
 * docs/08 "Summaries", docs/03 "Drive layout": a summary in the recording's folder — `{base}.summary.json`, the
 * same JSON as the copy kept here — and the folder's `summaryAt`, the version the folder holds: when it was last
 * edited, or made. Drive and iCloud carry it to the user's other devices; a local folder does not.
 */
internal object SummaryFile {
    const val STAMP: String = "summaryAt"
    const val MIME: String = "application/json"

    fun name(base: String): String = "$base.summary.json"

    fun decode(json: String): Summary? = runCatching { providerJson.decodeFromString(Summary.serializer(), json) }.getOrNull()

    fun version(summary: Summary): String = summary.editedAt ?: summary.createdAt
}
