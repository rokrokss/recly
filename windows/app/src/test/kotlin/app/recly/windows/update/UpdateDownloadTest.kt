package app.recly.windows.update

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/14 "Updates": the MSI is kept only when it is the file the signed feed describes — its size and its SHA-256 —
 * and only then has its own name; a file that is not leaves nothing behind. Served from a local HTTP server, the
 * way GitHub's asset host answers.
 */
class UpdateDownloadTest {

    private val dir = File("build/update-download/${UUID.randomUUID()}").absoluteFile
    private val msi = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun `the signed file is kept under its install version, and the earlier ones go`() {
        dir.mkdirs()
        File(dir, "Recly-0.3.1.msi").writeText("old")
        File(dir, "Recly-0.3.2-123.part").writeText("left by a quit")
        File(dir, "msiexec.log").writeText("the last install's log")

        val path = UpdateDownload(dir.toPath()).fetch(update(serve(msi)))

        assertEquals(File(dir, "Recly-0.3.3.msi").toPath(), path)
        assertContentEquals(msi, Files.readAllBytes(path))
        assertEquals(setOf("Recly-0.3.3.msi", "msiexec.log"), dir.list()!!.toSet())
    }

    @Test
    fun `a file of another size is refused`() {
        val longer = msi + byteArrayOf(1)
        val shorter = msi.copyOf(msi.size - 1)

        listOf(longer, shorter).forEach { served ->
            val error = assertFailsWith<UpdateException> { UpdateDownload(dir.toPath()).fetch(update(serve(served))) }
            assertEquals(UpdateFailure.SIGNATURE, error.failure)
            assertEquals("size", error.detail)
            assertNothingKept()
        }
    }

    /** The same size, one byte changed: only the hash can tell. */
    @Test
    fun `a file with another SHA-256 is refused`() {
        val changed = msi.copyOf().also { it[1000] = (it[1000] + 1).toByte() }

        val error = assertFailsWith<UpdateException> { UpdateDownload(dir.toPath()).fetch(update(serve(changed))) }

        assertEquals(UpdateFailure.SIGNATURE, error.failure)
        assertEquals("sha256", error.detail)
        assertNothingKept()
    }

    @Test
    fun `a redirect to the asset host is followed`() {
        val path = UpdateDownload(dir.toPath()).fetch(update(serve(msi, redirect = true)))

        assertContentEquals(msi, Files.readAllBytes(path))
        assertEquals(2, requests)
    }

    /** No `Content-Length`, so the size is only known once the file has come. */
    @Test
    fun `a file that runs past its size is refused as it comes`() {
        val error = assertFailsWith<UpdateException> {
            UpdateDownload(dir.toPath()).fetch(update(serve(msi + ByteArray(100_000), chunked = true)))
        }

        assertEquals(UpdateFailure.SIGNATURE, error.failure)
        assertNothingKept()
    }

    @Test
    fun `an asset that is not there is a failed download`() {
        val base = serve(msi)
        val error = assertFailsWith<UpdateException> {
            UpdateDownload(dir.toPath()).fetch(update(base).copy(url = "$base/missing.msi"))
        }

        assertEquals(UpdateFailure.DOWNLOAD, error.failure)
        assertEquals("http 404", error.detail)
    }

    /** What an install that did not go through left: the next click installs it without fetching it again. */
    @Test
    fun `a verified MSI already here is used again`() {
        dir.mkdirs()
        File(dir, "Recly-0.3.3.msi").writeBytes(msi)
        val base = serve(ByteArray(0))

        val path = UpdateDownload(dir.toPath()).fetch(update(base))

        assertContentEquals(msi, Files.readAllBytes(path))
        assertEquals(0, requests, "nothing was fetched")
    }

    @Test
    fun `an MSI already here that is not the signed one is fetched again`() {
        dir.mkdirs()
        File(dir, "Recly-0.3.3.msi").writeText("half of it")

        val path = UpdateDownload(dir.toPath()).fetch(update(serve(msi)))

        assertContentEquals(msi, Files.readAllBytes(path))
        assertEquals(1, requests)
    }

    /** A folder that cannot be made — a file in its place — is this PC's disk, not the download. */
    @Test
    fun `a folder that cannot be written is a failed install`() {
        dir.parentFile.mkdirs()
        dir.writeText("a file where the folder goes")

        val error = assertFailsWith<UpdateException> { UpdateDownload(dir.toPath()).fetch(update(serve(msi))) }

        assertEquals(UpdateFailure.INSTALL, error.failure)
        dir.delete()
    }

    private fun assertNothingKept() {
        assertFalse(File(dir, "Recly-0.3.3.msi").exists(), "a refused file is not kept")
        assertTrue(dir.list().orEmpty().none { it.endsWith(".part") }, "nor its part")
    }

    @Volatile private var requests = 0

    private fun update(base: String) = AvailableUpdate(
        version = "0.3.3",
        installerVersion = "0.3.3",
        url = "$base/Recly-0.3.3.msi",
        size = msi.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(msi).joinToString("") { "%02x".format(it) },
    )

    /**
     * Serves [bytes] at `/Recly-0.3.3.msi`; [chunked] sends it with no length announced, and [redirect] answers there
     * with a 302 to where the bytes are, as GitHub sends a release asset to its asset host.
     */
    private fun serve(bytes: ByteArray, chunked: Boolean = false, redirect: Boolean = false): String {
        server?.stop(0)
        requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++
            val path = exchange.requestURI.path
            if (redirect && path == "/Recly-0.3.3.msi") {
                exchange.responseHeaders.add("Location", "/assets/Recly-0.3.3.msi")
                exchange.sendResponseHeaders(302, -1)
            } else if (path == (if (redirect) "/assets/Recly-0.3.3.msi" else "/Recly-0.3.3.msi")) {
                exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
                runCatching { exchange.responseBody.use { it.write(bytes) } }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }
        server.start()
        this.server = server
        return "http://127.0.0.1:${server.address.port}"
    }
}
