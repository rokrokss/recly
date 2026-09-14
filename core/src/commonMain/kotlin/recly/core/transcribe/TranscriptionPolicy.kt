package recly.core.transcribe

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.platform.CoreDeps
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport

/** The Apple shell reads StoreKit, never the device language or a location permission (docs/15). */
interface AppStoreRegion {
    suspend fun countryCode(): String?
}

enum class OpenAiAvailability { ALLOWED, RESTRICTED, UNKNOWN }

/** A temporary lookup failure parks the step without spending its retry budget. */
class StorefrontUnavailableException : Exception()

/**
 * docs/15 "중국 본토 App Store": only the App Store shell opts in. Definitions remain portable;
 * every execution, including an old job snapshot, must independently pass this policy.
 */
class TranscriptionPolicy(private val region: AppStoreRegion? = null) {
    val enabled: Boolean get() = region != null
    private val state = MutableStateFlow(if (enabled) OpenAiAvailability.UNKNOWN else OpenAiAvailability.ALLOWED)
    private val mutex = Mutex()
    val availability: OpenAiAvailability get() = state.value
    fun observe() = state.asStateFlow()

    @Throws(Throwable::class)
    suspend fun refresh(): OpenAiAvailability = mutex.withLock {
        state.value = if (region == null) OpenAiAvailability.ALLOWED else {
            val country = try {
                withTimeoutOrNull(5_000) { region.countryCode() }?.uppercase()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when {
                country == "CHN" -> OpenAiAvailability.RESTRICTED
                country?.matches(Regex("[A-Z]{3}")) == true -> OpenAiAvailability.ALLOWED
                else -> OpenAiAvailability.UNKNOWN
            }
        }
        state.value
    }

    fun providerAvailable(provider: String): Boolean =
        !provider.equals("openai", ignoreCase = true) || availability == OpenAiAvailability.ALLOWED

    fun issue(step: Step, endpoint: String? = null): CoreMessage? {
        if (step !is Step.Transcribe || !enabled) return null
        val restricted = step.provider.equals("openai", ignoreCase = true) ||
            openAiEndpoint(step.invokeUrl) || openAiEndpoint(endpoint)
        if (!restricted) return null
        return when (availability) {
            OpenAiAvailability.ALLOWED -> null
            OpenAiAvailability.RESTRICTED -> CoreMessage.PROVIDER_REGION_RESTRICTED
            OpenAiAvailability.UNKNOWN -> CoreMessage.STOREFRONT_UNAVAILABLE
        }
    }

    @Throws(Throwable::class)
    suspend fun workflowIssue(workflows: List<Workflow>): CoreMessage? {
        refresh()
        return workflows.asSequence().flatMap { it.steps.asSequence() }.mapNotNull { issue(it) }.firstOrNull()
    }

    internal suspend fun requireAllowed(step: Step, endpoint: String? = null) {
        if (step !is Step.Transcribe || !enabled) return
        refresh()
        when (val issue = issue(step, endpoint)) {
            null -> Unit
            CoreMessage.STOREFRONT_UNAVAILABLE -> throw StorefrontUnavailableException()
            else -> throw StepFailure(retryable = false, reason = issue.code())
        }
    }

    internal fun guardedDeps(step: Step, deps: CoreDeps): CoreDeps {
        if (step !is Step.Transcribe || !enabled) return deps
        return deps.withTransport(object : Transport {
            override suspend fun execute(plan: HttpPlan): HttpResult {
                requireAllowed(step, plan.url)
                // In a restricted/unknown storefront an HTTP redirect must not bypass the
                // destination check. Providers must use their final endpoint in this case.
                val request = if (availability == OpenAiAvailability.ALLOWED) plan
                    else plan.copy(followRedirects = false)
                return deps.transport.execute(request)
            }
        })
    }

    private fun openAiEndpoint(endpoint: String?): Boolean {
        val host = endpoint?.let { runCatching { Url(it).host.lowercase().trimEnd('.') }.getOrNull() }
            ?: return false
        return host == "openai.com" || host.endsWith(".openai.com") ||
            host == "chatgpt.com" || host.endsWith(".chatgpt.com")
    }
}
