package app.recly.windows.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/14 "Updates": the feed is believed only once its Ed25519 signature over the file's exact bytes verifies, and
 * only a feed this build reads — schema 1 — can offer an MSI, which is offered only when it is a later install
 * version than this one. Signed here with a key of the test's own; the shipped key is the release script's.
 */
class UpdateFeedTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = rawPublicKey(keys)
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `a signed feed with a later MSI offers it`() {
        val body = feed(installerVersion = "0.3.3").toByteArray()

        val update = UpdateFeed.newer(body, sign(body), publicKey, current = "0.3.2")

        assertEquals(
            AvailableUpdate(
                version = "0.3.3",
                installerVersion = "0.3.3",
                url = "https://github.com/rokrokss/recly/releases/download/v0.3.3-build.42/Recly-0.3.3.msi",
                size = 98_765_432,
                sha256 = SHA,
            ),
            update,
        )
    }

    /** The release script writes the signature with a newline after it. */
    @Test
    fun `the signature is read with or without its trailing newline`() {
        val body = feed().toByteArray()
        val signature = Base64.getEncoder().encodeToString(signature(body))

        assertTrue(UpdateFeed.verify(body, "$signature\n".toByteArray(), publicKey))
        assertTrue(UpdateFeed.verify(body, "$signature\r\n".toByteArray(), publicKey))
        assertTrue(UpdateFeed.verify(body, signature.toByteArray(), publicKey))
    }

    @Test
    fun `a feed that is not the signed one is refused before it is read`() {
        val body = feed().toByteArray()
        val signature = sign(body)
        val tampered = feed().replace("98765432", "98765433").toByteArray()
        val otherKey = rawPublicKey(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())

        assertFalse(UpdateFeed.verify(tampered, signature, publicKey), "a changed byte")
        assertFalse(UpdateFeed.verify(body + '\n'.code.toByte(), signature, publicKey), "a byte added")
        assertFalse(UpdateFeed.verify(body, signature, otherKey), "another key")
        assertFalse(UpdateFeed.verify(body, "not base64!".toByteArray(), publicKey), "a signature that is not base64")
        assertFalse(UpdateFeed.verify(body, Base64.getEncoder().encode(ByteArray(10)), publicKey), "a signature too short")
        assertFalse(UpdateFeed.verify(body, signature, "not a key"), "a key that is not base64")
        assertFalse(UpdateFeed.verify(body, signature, Base64.getEncoder().encodeToString(ByteArray(16))), "a key too short")
        assertEquals("signature", checkFailure { UpdateFeed.newer(tampered, signature, publicKey, "0.3.2") }.detail)
    }

    @Test
    fun `only a schema 1 feed is read`() {
        val body = feed(schema = 2).toByteArray()

        assertEquals("schema 2", checkFailure { UpdateFeed.newer(body, sign(body), publicKey, "0.3.2") }.detail)
    }

    @Test
    fun `a signed feed that is not a feed is a failed check`() {
        listOf("{}", "[]", "not json", """{"schema":1,"version":"0.3.3","windows":{"installerVersion":"0.3.3"}}""").forEach { text ->
            val body = text.toByteArray()
            assertEquals("format", checkFailure { UpdateFeed.newer(body, sign(body), publicKey, "0.3.2") }.detail, text)
        }
        listOf(feed(installerVersion = "0.3"), feed(size = 0), feed(sha = "abc")).forEach { text ->
            val body = text.toByteArray()
            assertEquals("package", checkFailure { UpdateFeed.newer(body, sign(body), publicKey, "0.3.2") }.detail, text)
        }
    }

    @Test
    fun `a release with no MSI offers Windows nothing`() {
        val body = feed(windows = false).toByteArray()

        assertNull(UpdateFeed.newer(body, sign(body), publicKey, "0.3.2"))
    }

    @Test
    fun `the same or an earlier MSI is not an update`() {
        listOf("0.3.2", "0.3.1", "0.2.9").forEach { version ->
            val body = feed(installerVersion = version).toByteArray()
            assertNull(UpdateFeed.newer(body, sign(body), publicKey, "0.3.2"), version)
        }
    }

    /** MAJOR.MINOR.BUILD as numbers, not as text. */
    @Test
    fun `install versions are compared field by field as numbers`() {
        assertTrue(UpdateFeed.isNewer("0.3.10", "0.3.9"))
        assertFalse(UpdateFeed.isNewer("0.3.9", "0.3.10"))
        assertFalse(UpdateFeed.isNewer("0.3.2", "0.3.2"), "equal is not newer")
        assertTrue(UpdateFeed.isNewer("0.2.0", "0.1.32"))
        assertFalse(UpdateFeed.isNewer("0.1.32", "0.2.0"))
        assertTrue(UpdateFeed.isNewer("1.0.0", "0.99.99"))
        assertFalse(UpdateFeed.isNewer("0.3", "0.2.0"), "not three numbers")
        assertFalse(UpdateFeed.isNewer("0.3.x", "0.2.0"))
    }

    /** The whole check over HTTP: the feed, then its `.sig` beside it, with the app as the user agent. */
    @Test
    fun `a check fetches the feed and its signature`() {
        val body = feed().toByteArray()
        val agents = mutableListOf<String?>()
        val base = serve(
            "/update.json" to body,
            "/update.json.sig" to sign(body),
            agents = agents,
        )

        val update = UpdateFeed("$base/update.json", publicKey, UpdateHttp("Recly/test (Windows)")).check("0.3.2")

        assertEquals("0.3.3", update?.installerVersion)
        assertEquals(listOf<String?>("Recly/test (Windows)", "Recly/test (Windows)"), agents.toList())
    }

    /** A release published without `update.json` answers 404, which says nothing about whether this one is the newest. */
    @Test
    fun `a missing feed is a failed check, not up to date`() {
        val base = serve()

        assertEquals("http 404", checkFailure { UpdateFeed("$base/update.json", publicKey).check("0.3.2") }.detail)
    }

    @Test
    fun `the shipped feed is the latest release's, under the key both apps carry`() {
        assertEquals("https://github.com/rokrokss/recly/releases/latest/download/update.json", UpdateFeed.FEED_URL)
        assertEquals(32, Base64.getDecoder().decode(UpdateFeed.PUBLIC_KEY).size)
        assertTrue(UpdateHttp.USER_AGENT.matches(Regex("""Recly/\d+\.\d+\.\d+ \(Windows\)""")), UpdateHttp.USER_AGENT)
    }

    private fun checkFailure(block: () -> Unit): UpdateException {
        val error = assertFailsWith<UpdateException> { block() }
        assertEquals(UpdateFailure.CHECK, error.failure)
        return error
    }

    private fun signature(body: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(keys.private)
        update(body)
        sign()
    }

    /** As the release script writes `update.json.sig`. */
    private fun sign(body: ByteArray): ByteArray = (Base64.getEncoder().encodeToString(signature(body)) + "\n").toByteArray()

    private fun serve(vararg files: Pair<String, ByteArray>, agents: MutableList<String?> = mutableListOf()): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent")
            val bytes = files.toMap()[exchange.requestURI.path]
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        this.server = server
        return "http://127.0.0.1:${server.address.port}"
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** The raw 32 bytes the apps carry: the last 32 of the X.509 encoding. */
        fun rawPublicKey(keys: KeyPair): String = Base64.getEncoder().encodeToString(keys.public.encoded.takeLast(32).toByteArray())

        /** As `scripts/desktop-update.swift` writes it: keys sorted, pretty-printed, a newline at the end. */
        fun feed(
            schema: Int = 1,
            installerVersion: String = "0.3.3",
            size: Long = 98_765_432,
            sha: String = SHA,
            windows: Boolean = true,
        ): String {
            val mac = """
              "macos" : {
                "build" : "42",
                "sha256" : "$SHA",
                "size" : 123,
                "url" : "https://github.com/rokrokss/recly/releases/download/v0.3.3-build.42/Recly-macOS-0.3.3-42.dmg"
              },
            """.trimIndent()
            val win = """
              ,
              "windows" : {
                "installerVersion" : "$installerVersion",
                "sha256" : "$sha",
                "size" : $size,
                "url" : "https://github.com/rokrokss/recly/releases/download/v0.3.3-build.42/Recly-$installerVersion.msi"
              }
            """.trimIndent()
            return "{\n$mac\n" +
                "\"notes\" : \"https://github.com/rokrokss/recly/releases/tag/v0.3.3-build.42\",\n" +
                "\"schema\" : $schema,\n\"tag\" : \"v0.3.3-build.42\",\n\"version\" : \"0.3.3\"" +
                (if (windows) "\n$win" else "") + "\n}\n"
        }
    }
}
