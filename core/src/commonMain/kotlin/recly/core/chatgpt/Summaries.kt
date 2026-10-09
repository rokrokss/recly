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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import recly.core.message.CoreMessage
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
)

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
    suspend fun summarize(recordingId: String): SummaryState {
        // Asked before the run starts, so the screen goes straight to the permission dialog.
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) {
            return SummaryState.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code(), saved(recordingId))
        }
        val run = startLock.withLock {
            started[recordingId]?.takeIf { it.isActive } ?: scope.async { run(recordingId) }.also { started[recordingId] = it }
        }
        return run.await()
    }

    private suspend fun run(recordingId: String): SummaryState {
        val previous = saved(recordingId)
        runs.update { it + (recordingId to SummaryState.Running(previous)) }
        val outcome = try {
            SummaryState.Ready(save(create(recordingId)))
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

    private suspend fun create(recordingId: String): Summary {
        val transcript = transcript(recordingId)
        val text = transcript?.let(TranscriptNormalizer::text)?.takeIf { it.isNotBlank() }
            ?: throw ChatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        deps.logger.log(Logger.Level.INFO, "summary.start", mapOf("recordingId" to recordingId, "model" to model))
        val body = buildJsonObject {
            put("model", model)
            put("instructions", instructions(SummaryLanguage.of(transcript.language, deps.locale)))
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            // The transcript's text and nothing else (docs/15 §10): no title, no time, no audio.
                            put("text", text)
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
        val summary = ResponseStream.text(result.body.decodeToString()).trim()
        if (summary.isEmpty()) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "empty summary"))
        return Summary(recordingId, summary, model, deps.clock.now().isoUtc())
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
        /**
         * Plain text with "- " lists, because no shell renders Markdown (docs/09 "Summary view"), all of it in
         * [language] — the headings too ([SummaryLanguage]).
         */
        fun instructions(language: String): String = """You write meeting notes from a recording's transcript.
Write everything in $language, including the section headings, even where the transcript mixes in other languages.
Use plain text. No Markdown: no #, no **, no tables. Start list items with "- ".
Write these sections in this order, each a heading on its own line followed by its content, and leave out a section that would be empty:
Summary: three to five sentences.
Key points: a list.
Decisions: a list.
Action items: a list, each "owner: task", with the due date when the transcript gives one.
Call people what the transcript calls them. Do not add anything the transcript does not say."""
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
