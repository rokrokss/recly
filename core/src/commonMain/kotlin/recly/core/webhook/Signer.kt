package recly.core.webhook

import kotlin.random.Random
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Standard Webhooks signatures (ADR-010, docs/04): `v1,` + base64 of
 * `HMAC-SHA256(secret, "{id}.{timestamp}.{body}")`.
 *
 * The HMAC comes from okio, which already implements RFC 2104 in common code on top of its own
 * SHA-256 — no crypto dependency is added and no hand-rolled MAC is shipped.
 */
object Signer {
    /** What the generate button shows and what verifiers expect to paste back (docs/04). */
    const val PREFIX = "whsec_"

    private const val VERSION = "v1"
    private const val SECRET_BYTES = 32

    /**
     * The secret as the user stored it. `whsec_…` is the Standard Webhooks convention and carries
     * base64 of the raw key; anything else is a key the user typed, used as its UTF-8 bytes.
     *
     * @throws IllegalArgumentException on a `whsec_` string whose body is not base64 — silently
     * falling back to the literal text would produce signatures no verifier can reproduce.
     */
    fun secretBytes(stored: String): ByteArray =
        if (stored.startsWith(PREFIX)) {
            val decoded = stored.removePrefix(PREFIX).decodeBase64()
                ?: throw IllegalArgumentException("secret starts with '$PREFIX' but is not base64")
            decoded.toByteArray()
        } else {
            stored.encodeToByteArray()
        }

    /** [timestampSec] is unix seconds; [body] is the exact bytes that go on the wire. */
    fun sign(secret: ByteArray, id: String, timestampSec: Long, body: ByteArray): String {
        val signed = Buffer()
            .writeUtf8(id)
            .writeByte('.'.code)
            .writeUtf8(timestampSec.toString())
            .writeByte('.'.code)
            .write(body)
            .readByteString()
        return "$VERSION,${signed.hmacSha256(secret.toByteString()).base64()}"
    }

    /** 32 random bytes, base64 with its standard padding, behind the `whsec_` marker. */
    fun generateSecret(random: Random): String =
        PREFIX + ByteString.of(*random.nextBytes(SECRET_BYTES)).base64()
}
