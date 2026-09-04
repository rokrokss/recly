package recly.core.transcribe

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.Path
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.platform.CoreDeps
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.SecureStore

/** Providers answer with more fields than we read, and add new ones without warning. */
internal val providerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * One STT provider (docs/08 "Provider"). Everything provider-specific lives behind these two
 * calls: [TranscribeRunner] knows about remuxing, polling and Drive, and nothing about vendors.
 *
 * v1 only uses APIs that either poll or answer synchronously — there is no server to receive a
 * callback (docs/08 "원칙").
 */
interface SttProvider {
    val name: String

    /**
     * True when [submit] waits on one long request for the transcript itself, which a phone's
     * background budget may not cover (docs/08 "폴링 · 상태"); the editors warn on that.
     */
    val synchronous: Boolean get() = false

    /**
     * How long a submission may stay unfinished before the runner drops the ref and re-submits
     * (docs/08 "폴링 · 상태"). Two hours covers a provider that answers in minutes; one that
     * documents a longer turnaround says so here, because giving up early pays for the same audio
     * twice.
     */
    val resultTimeout: Duration get() = 2.hours

    /**
     * What this provider refuses outright, checked before the upload (docs/08 "길이·크기 한도").
     */
    val limits: SttLimits get() = SttLimits()

    /** Uploads [file] and starts the job. */
    suspend fun submit(ctx: SttContext, file: Path): Submitted

    suspend fun poll(ctx: SttContext, ref: String): PollResult
}

/**
 * A provider's published ceilings. Only the ones a Recly recording — 32 kbps AAC — can plausibly
 * reach are declared; every other limit is left to be learned from the provider's own 4xx, which
 * is what an undeclared `null` means.
 */
data class SttLimits(val maxBytes: Long? = null, val maxDurationSec: Double? = null)

/**
 * What a submission answered with. An asynchronous API hands back a reference to poll; a
 * synchronous one — `clova` with `completion: "sync"` — has already done the work by the time the
 * response arrives, and there is nothing left to poll (docs/08 provider table).
 */
sealed interface Submitted {
    data class Polling(val ref: String) : Submitted

    data class Finished(val result: SttResult) : Submitted
}

/** What a provider needs beyond the audio: the step's own fields and the key it is called with. */
class SttContext(
    val step: Step.Transcribe,
    val apiKey: String,
    /**
     * The speaker count to ask for, or null to let the provider decide. `context.participants`
     * beats the workflow's `speakers` when the recording carries one (docs/08).
     */
    val speakersExpected: Int?,
    /**
     * How long the audio being submitted is, when the recording knows: the parts that make up the
     * joined file, or what the recording says it lasted. A provider that answers with a transcript
     * and no timings — OpenAI's plain `json`, which bills in tokens — has nothing else to put on
     * the one segment it produces.
     */
    val audioDurationSec: Double? = null,
    val deps: CoreDeps,
    /**
     * Provider-owned scratch that the runner persists in `step_run.state_json` alongside its own
     * state and hands back on the next pass. RTZR keeps its access token here so a poll every 30
     * seconds does not buy a new one each time (docs/08 provider table). Opaque to the runner.
     */
    var providerState: JsonObject? = null,
)

sealed interface PollResult {
    /** Still queued or running; the runner parks the job and comes back. */
    data object Pending : PollResult

    data class Done(val result: SttResult) : PollResult

    /**
     * The provider says this submission is finished and useless — its own `error`/`failed` state.
     * Reported as data rather than thrown because the ref is now worthless: only the runner knows
     * that it has to be dropped so the retry submits the audio again (docs/08 "폴링 · 상태").
     * A transport-level problem is *not* this: those are thrown, and keep the ref to poll again.
     */
    data class Failed(val reason: String) : PollResult
}

/** A finished transcription, still on the concatenated file's time axis and in provider labels. */
data class SttResult(
    val segments: List<SttSegment>,
    val language: String?,
    val durationSec: Double?,
    val model: String?,
)

data class SttSegment(
    val start: Double,
    val end: Double,
    /** The provider's own label (`A`, `1`, …), or null when it did not diarize. */
    val speaker: String?,
    val text: String,
    val words: List<SttWord>? = null,
)

data class SttWord(val start: Double, val end: Double, val text: String)

/**
 * The providers this build can run (docs/08 provider table). A name that is in the spec but not
 * here is a valid definition this device happens not to be able to execute.
 */
object SttProviders {
    fun create(name: String): SttProvider? = when (name) {
        AssemblyAiProvider.NAME -> AssemblyAiProvider()
        AzureProvider.NAME -> AzureProvider()
        ClovaProvider.NAME -> ClovaProvider()
        DagloProvider.NAME -> DagloProvider()
        DeepgramProvider.NAME -> DeepgramProvider()
        ElevenLabsProvider.NAME -> ElevenLabsProvider()
        GladiaProvider.NAME -> GladiaProvider()
        RevProvider.NAME -> RevProvider()
        RtzrProvider.NAME -> RtzrProvider()
        SpeechmaticsProvider.NAME -> SpeechmaticsProvider()
        // Four vendors behind one API: the profile is the only thing that differs.
        OpenAiCompatProvider.OPENAI_NAME -> OpenAiCompatProvider.openai()
        OpenAiCompatProvider.GROQ_NAME -> OpenAiCompatProvider.groq()
        OpenAiCompatProvider.TOGETHER_NAME -> OpenAiCompatProvider.together()
        OpenAiCompatProvider.MISTRAL_NAME -> OpenAiCompatProvider.mistral()
        else -> null
    }

    /** Whether [name] answers on one long request — what the editors ask before warning a phone. */
    fun synchronous(name: String): Boolean = create(name)?.synchronous == true
}

/** Every provider answers in JSON; a body that will not parse is a provider fault, not a shape. */
internal fun HttpResult.jsonBody(): JsonObject? =
    body.decodeToString().takeIf { it.isNotBlank() }
        ?.let { runCatching { providerJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

/**
 * The provider a step names, or the failure that says this build cannot run that definition —
 * the first thing both docs/08 runners do.
 */
internal fun <P> resolveProvider(providers: (String) -> P?, name: String): P =
    providers(name)
        ?: throw StepFailure(
            retryable = false,
            reason = CoreMessage.PROVIDER_ERROR.code(detail = "provider '$name' is not available in this build"),
        )

/**
 * The key [secretRef] names, trimmed — a pasted key often carries a trailing newline. A ref this
 * device holds no value for is terminal: retrying cannot conjure it (docs/05 "시크릿").
 */
internal suspend fun apiKey(deps: CoreDeps, secretRef: String): String =
    deps.secureStore.get(SecureStore.SECRETS, secretRef)?.decodeToString()?.trim()
        ?: throw StepFailure(retryable = false, reason = CoreMessage.MISSING_SECRET.code(secretRef))

/**
 * The docs/08 error table, as [CoreMessage] keys: the shell owns the sentence and the provider's
 * own words ride along as the detail it shows verbatim underneath (docs/07 §5).
 */
internal object Reasons {

    /** A dropped connection or a timeout is the same kind of "try later" as a 503. */
    suspend fun send(deps: CoreDeps, what: String, plan: HttpPlan): HttpResult =
        try {
            deps.transport.execute(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "$what ${e.message ?: e::class.simpleName}"),
            )
        }

    /**
     * @param badRequest what a 4xx that is not about auth or quota means for this call — a rejected
     * upload is [CoreMessage.UNSUPPORTED_AUDIO], anything else is the caller's own non-retryable
     * reason.
     */
    fun failure(what: String, result: HttpResult, badRequest: CoreMessage): StepFailure {
        val status = result.status
        val excerpt = result.body.decodeToString().take(BODY_EXCERPT)
        return when {
            status == 401 || status == 403 -> StepFailure(
                retryable = false,
                reason = CoreMessage.AUTH_REJECTED.code(detail = "$what HTTP $status"),
            )

            status == 429 || status == 402 -> StepFailure(
                retryable = true,
                reason = CoreMessage.QUOTA.code(detail = "$what HTTP $status"),
                retryAfterSec = result.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            )

            status >= 500 || status == 408 -> StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "$what HTTP $status"),
            )

            else -> StepFailure(retryable = false, reason = badRequest.code(detail = "$what HTTP $status $excerpt"))
        }
    }

    private const val BODY_EXCERPT = 200
}
