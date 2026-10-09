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

/** Meeting notes ChatGPT wrote from one recording's transcript (docs/08 "Summaries"). [createdAt] is ISO UTC. */
@Serializable
data class Summary(
    val recordingId: String,
    val text: String,
    val model: String,
    val createdAt: String,
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
            SummaryState.Ready(write(recordingId, create(recordingId)))
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
        val text = transcript(recordingId)?.let(TranscriptNormalizer::text)?.takeIf { it.isNotBlank() }
            ?: throw ChatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        deps.logger.log(Logger.Level.INFO, "summary.start", mapOf("recordingId" to recordingId, "model" to model))
        val body = buildJsonObject {
            put("model", model)
            put("instructions", INSTRUCTIONS)
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

    private suspend fun write(recordingId: String, summary: Summary): Summary {
        recordings.saveSummary(recordingId, providerJson.encodeToString(Summary.serializer(), summary))
        return summary
    }

    private suspend fun current(recordingId: String): SummaryState =
        saved(recordingId)?.let { SummaryState.Ready(it) } ?: SummaryState.None

    private suspend fun saved(recordingId: String): Summary? = recordings.summary(recordingId)?.let {
        runCatching { providerJson.decodeFromString(Summary.serializer(), it) }.getOrNull()
            ?.takeIf { summary -> summary.recordingId == recordingId }
    }

    internal companion object {
        /**
         * Plain text with "- " lists, because no shell renders Markdown (docs/09 "Summary view"), in the
         * transcript's own language — the headings too.
         */
        const val INSTRUCTIONS = """You write meeting notes from a recording's transcript.
Write in the language most of the transcript is in, including the section headings.
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
