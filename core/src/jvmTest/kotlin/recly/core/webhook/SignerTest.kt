package recly.core.webhook

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SignerTest {
    /**
     * The vector published with the Standard Webhooks reference implementations (the one their
     * Python/Go/JS test suites all sign): secret `whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw`, id
     * `msg_p5jXN8AQM9LWM0D4loKWxJek`, timestamp 1614265330, body `{"test": 2432232314}`.
     */
    @Test
    fun `matches the Standard Webhooks reference vector`() {
        val signature = Signer.sign(
            secret = Signer.secretBytes("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"),
            id = "msg_p5jXN8AQM9LWM0D4loKWxJek",
            timestampSec = 1614265330,
            body = """{"test": 2432232314}""".toByteArray(),
        )

        assertEquals("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=", signature)
    }

    /** Independent of okio: the JDK's own HMAC over the same `{id}.{timestamp}.{body}` string. */
    @Test
    fun `matches an independent javax crypto Mac computation`() {
        val stored = "whsec_${java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })}"
        val secret = Signer.secretBytes(stored)
        val body = """{"type":"recording.completed","data":{"x":"한글"}}""".toByteArray()
        val id = "01J9STEPR0N0123456789ABCDE"
        val timestamp = 1_774_490_700L

        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            java.util.Base64.getEncoder().encodeToString(doFinal("$id.$timestamp.".toByteArray() + body))
        }

        assertEquals("v1,$expected", Signer.sign(secret, id, timestamp, body))
    }

    @Test
    fun `whsec_ carries base64 of the key, anything else is used as text`() {
        val raw = ByteArray(32) { (it * 7).toByte() }
        val encoded = java.util.Base64.getEncoder().encodeToString(raw)

        assertTrue(raw.contentEquals(Signer.secretBytes("whsec_$encoded")))
        assertTrue("hunter2".toByteArray().contentEquals(Signer.secretBytes("hunter2")))
        // The prefix is the only thing that decides; the same text signs differently with it.
        assertNotEquals(
            Signer.sign(Signer.secretBytes("whsec_$encoded"), "id", 1, ByteArray(0)),
            Signer.sign(Signer.secretBytes(encoded), "id", 1, ByteArray(0)),
        )
    }

    @Test
    fun `a whsec_ secret that is not base64 is refused rather than signed with the literal text`() {
        assertFailsWith<IllegalArgumentException> { Signer.secretBytes("whsec_not base64!!") }
    }

    @Test
    fun `generateSecret is whsec_ plus 32 bytes of standard padded base64`() {
        val secret = Signer.generateSecret(Random(7))

        assertTrue(secret.startsWith("whsec_"), secret)
        val body = secret.removePrefix("whsec_")
        // 32 bytes -> 44 base64 characters, the last of which is the '=' pad. Padding is kept:
        // verifiers decode the string as-is.
        assertEquals(44, body.length)
        assertTrue(body.endsWith("="), body)
        assertEquals(32, java.util.Base64.getDecoder().decode(body).size)
        assertNotEquals(secret, Signer.generateSecret(Random(8)))
    }
}
