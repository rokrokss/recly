@file:OptIn(ExperimentalTime::class)

package recly.core.webhook

import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.job.StepContext
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.job.StepRunner
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.model.wire
import recly.core.platform.CoreDeps
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Logger
import recly.core.platform.SecureStore

/**
 * `webhook` (docs/04): one signed POST of the docs/04 payload, with the response table below
 * deciding retry versus give-up. Nothing here waits — a retryable answer becomes a [StepFailure]
 * and the executor's backoff owns the clock.
 *
 * `webhook-id` is the `step_run` id, so every retry of this step carries the same id and the
 * receiver can dedupe; only the timestamp and the signature are fresh.
 */
class WebhookRunner(private val deps: CoreDeps) : StepRunner {
    override val type: String = TYPE

    override suspend fun run(ctx: StepContext): StepOutcome {
        val step = ctx.step as? Step.Webhook
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.STEP_FAILED.code("$TYPE runner got a ${ctx.step::class.simpleName}"),
            )
        val now = deps.clock.now()
        val timestamp = now.epochSeconds
        val body = PayloadBuilder.build(
            meta = ctx.recording.meta,
            workflow = ctx.workflow,
            prior = ctx.prior,
            device = deps.device,
            stepRunId = ctx.stepRunId,
            now = now,
        ).encode().encodeToByteArray()

        val headers = buildMap {
            put("user-agent", "rec/${deps.appVersion} (${deps.device.platform.wire})")
            put("webhook-id", ctx.stepRunId)
            put("webhook-timestamp", timestamp.toString())
            signature(step, ctx.stepRunId, timestamp, body)?.let { put("webhook-signature", it) }
        }
        val result = deps.transport.execute(
            HttpPlan(
                method = "POST",
                url = step.url,
                headers = headers,
                body = HttpBody.Bytes(body, CONTENT_TYPE),
                followRedirects = false,
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        return outcome(step, result)
    }

    /** No `secretRef` means no header at all (docs/04). A `secretRef` this device has never been
     * given a key for is terminal: retrying cannot conjure the secret, only the user can. */
    private suspend fun signature(step: Step.Webhook, id: String, timestamp: Long, body: ByteArray): String? {
        val ref = step.secretRef ?: return null
        val stored = deps.secureStore.get(SecureStore.SECRETS, ref)
            ?: throw StepFailure(retryable = false, reason = CoreMessage.MISSING_SECRET.code(ref))
        val secret = try {
            Signer.secretBytes(stored.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw StepFailure(
                retryable = false,
                reason = CoreMessage.INVALID_SECRET.code(ref, detail = e.message),
            )
        }
        return Signer.sign(secret, id, timestamp, body)
    }

    /**
     * docs/04 "응답 처리". A 3xx lands in the terminal branch on purpose: the plan asked the
     * transport not to follow it, and a webhook URL that moved is a configuration change the user
     * has to make, not something a retry fixes.
     */
    private fun outcome(step: Step.Webhook, result: HttpResult): StepOutcome {
        val status = result.status
        if (status in 200..299) {
            deps.logger.log(Logger.Level.INFO, "webhook.ok", mapOf("stepId" to step.id, "status" to status))
            return StepOutcome.Done(StepOutput(buildJsonObject { put("status", status) }))
        }
        if (status in RETRYABLE || status >= 500) {
            throw StepFailure(
                retryable = true,
                reason = CoreMessage.WEBHOOK_HTTP.code(status.toString()),
                retryAfterSec = if (status == 429) retryAfter(result) else null,
            )
        }
        // The body is the only thing that ever says *why* a 4xx happened, and this branch is
        // terminal — nobody gets a second look at it. It rides along as the detail: not translated,
        // not part of the sentence, shown under it.
        throw StepFailure(
            retryable = false,
            reason = CoreMessage.WEBHOOK_HTTP.code(
                status.toString(),
                detail = result.body.decodeToString().take(BODY_EXCERPT).takeIf { it.isNotBlank() },
            ),
        )
    }

    /** Only the delta-seconds form; an HTTP-date `Retry-After` falls back to the backoff curve. */
    private fun retryAfter(result: HttpResult): Long? =
        result.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    companion object {
        const val TYPE = "webhook"
        internal const val CONTENT_TYPE = "application/json"
        private const val TIMEOUT_SEC = 30
        private const val BODY_EXCERPT = 200
        private val RETRYABLE = setOf(408, 425, 429)
    }
}
