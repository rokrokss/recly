package recly.core.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * docs/07 §5: the core says things in keys, the shell says them in words. What matters here is
 * that the round trip is exact for every key — a shell that cannot read a code back shows the
 * user a raw `MISSING_SECRET:webhook_secret` — and that a sentence stored by an older build is
 * recognisably *not* a key.
 */
class CoreMessageTest {

    private val SECRET_KEYS = setOf(CoreMessage.MISSING_SECRET, CoreMessage.INVALID_SECRET)

    @Test
    fun `every key round-trips through its code, with and without an argument`() {
        CoreMessage.entries.forEach { message ->
            // The two that name a secret only parse with a real `secretRef`; see below.
            val arg = if (message in SECRET_KEYS) "webhook_secret" else "x"
            if (message !in SECRET_KEYS) {
                assertEquals(CoreMessageRef(message), CoreMessageRef.parse(message.code()))
            }
            assertEquals(CoreMessageRef(message, arg), CoreMessageRef.parse(message.code(arg)))
            assertEquals(
                CoreMessageRef(message, arg, "why"),
                CoreMessageRef.parse(message.code(arg, detail = "why")),
            )
        }
    }

    /**
     * docs/08 "오류": a transcribe failure names the reason and hands the provider's own words over
     * as the detail, which is what the shell shows under the sentence.
     */
    @Test
    fun `a docs 08 reason comes back with the provider's words beside it`() {
        val reasons = listOf(
            CoreMessage.AUTH_REJECTED to "assemblyai.submit HTTP 401",
            CoreMessage.QUOTA to "rtzr.transcribe HTTP 429",
            CoreMessage.PROVIDER_ERROR to "clova is synchronous and has no 'x' to poll",
            CoreMessage.UNSUPPORTED_AUDIO to "rtzr.transcribe HTTP 415 {\"error\":\"nope\"}",
            CoreMessage.NO_INPUT_TRACK to "recording has [spk]",
            CoreMessage.RESULT_TIMEOUT to "'ref-1' did not finish within 2h",
        )

        reasons.forEach { (message, detail) ->
            assertEquals(CoreMessageRef(message, null, detail), CoreMessageRef.parse(message.code(detail = detail)))
        }
    }

    /** The detail is opaque too — a response body brings its own punctuation. */
    @Test
    fun `a detail survives whatever is in it`() {
        val body = """{"error":"nope|maybe","code":7}"""
        val parsed = CoreMessageRef.parse(CoreMessage.WEBHOOK_HTTP.code("400", detail = body))

        assertEquals(CoreMessageRef(CoreMessage.WEBHOOK_HTTP, "400", body), parsed)
    }

    @Test
    fun `a key with a detail and no argument still parses`() {
        assertEquals(
            CoreMessageRef(CoreMessage.NEEDS_AUTH, null, "token revoked"),
            CoreMessageRef.parse(CoreMessage.NEEDS_AUTH.code(detail = "token revoked")),
        )
    }

    /**
     * The reason the two secret keys are strict: builds before the keys existed wrote exactly
     * these two strings into `step_run.last_error`, and reading them as the new wire form would
     * show an empty secret name for one and a parser complaint where the name goes for the other.
     */
    @Test
    fun `the sentences an older build stored for a secret are not keys`() {
        assertNull(CoreMessageRef.parse("MISSING_SECRET"))
        assertNull(CoreMessageRef.parse("INVALID_SECRET: expected whsec_ or base64"))
    }

    @Test
    fun `a secret key parses only with a real secretRef`() {
        assertEquals(
            CoreMessageRef(CoreMessage.MISSING_SECRET, "hook_main"),
            CoreMessageRef.parse("MISSING_SECRET:hook_main"),
        )
        listOf("", "Hook", "0hook", "hook main", "a" + "b".repeat(32)).forEach {
            assertNull(CoreMessageRef.parse("MISSING_SECRET:$it"), "'$it' is not a secretRef")
        }
    }

    @Test
    fun `the code is the documented shape`() {
        assertEquals("NEEDS_AUTH", CoreMessage.NEEDS_AUTH.code())
        assertEquals("MISSING_SECRET:webhook_secret", CoreMessage.MISSING_SECRET.code("webhook_secret"))
    }

    /** An argument is opaque: a diagnostic with colons in it must come back whole. */
    @Test
    fun `an argument keeps every separator it contains`() {
        val detail = "drive.uploadChunk: HTTP 400: nope"
        val parsed = CoreMessageRef.parse(CoreMessage.STEP_FAILED.code(detail))

        assertEquals(CoreMessageRef(CoreMessage.STEP_FAILED, detail), parsed)
    }

    /** An empty argument is still an argument — `STEP_FAILED:` is not `STEP_FAILED`. */
    @Test
    fun `an empty argument is distinct from none`() {
        assertEquals(CoreMessageRef(CoreMessage.STEP_FAILED, ""), CoreMessageRef.parse("STEP_FAILED:"))
        assertEquals(CoreMessageRef(CoreMessage.STEP_FAILED, null), CoreMessageRef.parse("STEP_FAILED"))
    }

    /** docs/07 §5 compatibility: a row an older build wrote holds prose, and it is shown as it is. */
    @Test
    fun `a stored sentence is not a key`() {
        assertNull(CoreMessageRef.parse("Google Drive 권한 재동의가 필요합니다"))
        assertNull(CoreMessageRef.parse("webhook: HTTP 500"))
        assertNull(CoreMessageRef.parse(""))
    }
}
