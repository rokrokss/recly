package recly.core.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.KtorTransport

/**
 * `HttpBody.Multipart` as [KtorTransport] writes it (docs/08: both `clova` and `rtzr` take the
 * audio as a form upload). What matters is that the file part is streamed and that the length is
 * spelled out — an API gateway that refuses chunked encoding is not something a user can work
 * around.
 */
class MultipartTest {
    private val fs = FakeFileSystem()
    private val audio = "/data/joined.m4a".toPath()
    private var sent: HttpRequestData? = null

    private val transport = KtorTransport(
        HttpClient(MockEngine { request -> sent = request; respondOk() }) { install(HttpTimeout) },
        fs,
    )

    init {
        fs.createDirectories(audio.parent!!)
        fs.write(audio) { writeUtf8(AUDIO) }
    }

    @Test
    fun `the parts are written in order, with the boundary the content type names`() = runBlocking {
        transport.execute(plan())

        val request = sent!!
        val type = request.body.contentType!!.toString()
        assertTrue(type.startsWith("${HttpBody.Multipart.FORM_DATA}; boundary="), type)
        val boundary = type.substringAfter("boundary=")
        val body = request.body.toByteArray().decodeToString()
        assertEquals(
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"media\"; filename=\"joined.m4a\"\r\n" +
                "Content-Type: audio/mp4\r\n\r\n" +
                "$AUDIO\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"params\"\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                "$PARAMS\r\n" +
                "--$boundary--\r\n",
            body,
        )
    }

    @Test
    fun `the length is known before a byte is written`() = runBlocking {
        transport.execute(plan())

        val request = sent!!
        assertEquals(request.body.toByteArray().size.toLong(), request.body.contentLength)
    }

    private fun plan() = HttpPlan(
        method = "POST",
        url = "https://provider.example.com/recognizer/upload",
        body = HttpBody.Multipart(
            listOf(
                HttpBody.Multipart.Part(
                    name = "media",
                    contentType = "audio/mp4",
                    source = HttpBody.Multipart.Source.File(audio),
                    filename = "joined.m4a",
                ),
                HttpBody.Multipart.Part(
                    name = "params",
                    contentType = "application/json",
                    source = HttpBody.Multipart.Source.Bytes(PARAMS.encodeToByteArray()),
                ),
            ),
        ),
    )

    private companion object {
        const val AUDIO = "aac-frames-of-a-whole-recording"
        const val PARAMS = """{"completion":"sync"}"""
    }
}
