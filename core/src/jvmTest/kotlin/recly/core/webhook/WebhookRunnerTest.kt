@file:OptIn(ExperimentalTime::class)

package recly.core.webhook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.testing.MapSecureStore
import recly.core.testing.START
import recly.core.testing.STEP_RUN_ID

class WebhookRunnerTest {
    @Test
    fun `a 2xx is a success and the request is exactly what docs 04 describes`() = runBlocking {
        val harness = WebhookHarness()

        val output = harness.run()

        assertEquals(200, output.json.getValue("status").jsonPrimitive.int)
        val sent = harness.webhook.received.single()
        assertEquals("POST", sent.method)
        assertEquals("application/json", sent.contentType)
        assertEquals(STEP_RUN_ID, sent.header("webhook-id"))
        assertEquals(START.epochSeconds.toString(), sent.header("webhook-timestamp"))
        assertEquals("rec/1.0.0 (macos)", sent.header("user-agent"))
        assertTrue(sent.text.startsWith("{\"type\":\"recording.completed\""), sent.text)
    }

    /** docs/04 writes every header in lower case and receivers index on that. */
    @Test
    fun `header names go out lowercase`() = runBlocking {
        val harness = WebhookHarness(secrets = MapSecureStore(mapOf("n8n" to "whsec_" + "AA".repeat(22))))

        harness.run(harness.step(secretRef = "n8n"))

        val ours = harness.webhook.received.single().headerNames.filter { it.startsWith("webhook", true) }
        assertEquals(listOf("webhook-id", "webhook-signature", "webhook-timestamp"), ours.sorted())
        assertTrue(ours.all { it == it.lowercase() }, ours.toString())
    }

    @Test
    fun `without a secretRef there is no signature header`() = runBlocking {
        val harness = WebhookHarness()

        harness.run()

        assertNull(harness.webhook.received.single().header("webhook-signature"))
    }

    @Test
    fun `with a secretRef the signature covers the exact bytes that were sent`() = runBlocking {
        val stored = "whsec_" + java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val harness = WebhookHarness(secrets = MapSecureStore(mapOf("n8n" to stored)))

        harness.run(harness.step(secretRef = "n8n"))

        val sent = harness.webhook.received.single()
        val secret = Signer.secretBytes(stored)
        val expected = Signer.sign(secret, STEP_RUN_ID, START.epochSeconds, sent.body)
        assertEquals(expected, sent.header("webhook-signature"))
        // Negative check: the assertion above is only worth something if a different body or a
        // different secret would not produce the same string.
        assertNotEquals(expected, Signer.sign(secret, STEP_RUN_ID, START.epochSeconds, sent.body + 'x'.code.toByte()))
        assertNotEquals(expected, Signer.sign(ByteArray(32), STEP_RUN_ID, START.epochSeconds, sent.body))
    }

    @Test
    fun `a secretRef this device has no key for fails terminally without sending anything`() = runBlocking {
        val harness = WebhookHarness()

        val failure = assertFailsWith<StepFailure> { harness.run(harness.step(secretRef = "n8n")) }

        assertEquals(CoreMessage.MISSING_SECRET.code("n8n"), failure.reason)
        assertFalse(failure.retryable)
        assertTrue(harness.webhook.received.isEmpty(), "nothing should be posted without a signature")
    }

    @Test
    fun `a 500 is retryable`() = runBlocking {
        val harness = WebhookHarness()
        harness.webhook.status = 500

        val failure = assertFailsWith<StepFailure> { harness.run() }

        assertTrue(failure.retryable)
        assertNull(failure.retryAfterSec)
    }

    @Test
    fun `a 429 hands its Retry-After to the executor`() = runBlocking {
        val harness = WebhookHarness()
        harness.webhook.status = 429
        harness.webhook.responseHeaders = mapOf("Retry-After" to "120")

        val failure = assertFailsWith<StepFailure> { harness.run() }

        assertTrue(failure.retryable)
        assertEquals(120L, failure.retryAfterSec)
    }

    @Test
    fun `408 and 425 are retryable, other 4xx are terminal`() = runBlocking {
        listOf(408, 425).forEach { status ->
            val harness = WebhookHarness()
            harness.webhook.status = status
            assertTrue(assertFailsWith<StepFailure> { harness.run() }.retryable, "HTTP $status should retry")
        }
        listOf(400, 401, 404, 422).forEach { status ->
            val harness = WebhookHarness()
            harness.webhook.status = status
            val failure = assertFailsWith<StepFailure> { harness.run() }
            assertFalse(failure.retryable, "HTTP $status should be terminal")
            assertEquals(CoreMessage.WEBHOOK_HTTP.code(status.toString()), failure.reason)
        }
    }

    /** docs/04 does not follow redirects: a signed body must not be replayed at a new address. */
    @Test
    fun `a 302 is neither followed nor retried`() = runBlocking {
        val harness = WebhookHarness()
        harness.webhook.status = 302
        harness.webhook.responseHeaders = mapOf("Location" to "https://hooks.example.com/moved")

        val failure = assertFailsWith<StepFailure> { harness.run() }

        assertFalse(failure.retryable)
        assertEquals(CoreMessage.WEBHOOK_HTTP.code("302"), failure.reason)
        assertEquals(1, harness.webhook.received.size, "the redirect must not have been followed")
    }
}
