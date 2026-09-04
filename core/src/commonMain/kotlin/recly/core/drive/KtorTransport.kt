package recly.core.drive

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.random.Random
import kotlin.random.nextULong
import okio.Buffer
import okio.FileSystem
import okio.buffer
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport

/** The engine each platform ships: OkHttp on the JVM and Android, Darwin on Apple (docs/10). */
expect fun defaultEngine(): HttpClientEngineFactory<*>

/**
 * The default [Transport]. It only executes plans — retries, resume and backoff belong to
 * [ResumableUploadPlanner] and the runners, so a shell can swap this for a background
 * `URLSession` without reimplementing any of that (ADR-015).
 */
class KtorTransport(
    private val client: HttpClient,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : Transport {
    constructor(
        engine: HttpClientEngineFactory<*> = defaultEngine(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ) : this(defaultClient(engine), fileSystem)

    /**
     * Redirects are a client-wide setting in Ktor, so the no-redirect plans get their own client.
     * It shares the engine — this is a config copy, not a second connection pool.
     */
    private val noRedirects: HttpClient by lazy { client.config { followRedirects = false } }

    override suspend fun execute(plan: HttpPlan): HttpResult {
        val response = (if (plan.followRedirects) client else noRedirects).request(plan.url) {
            method = HttpMethod.parse(plan.method)
            applyHeaders(plan)
            when (val body = plan.body) {
                null -> Unit
                is HttpBody.Bytes -> setBody(body.bytes)
                is HttpBody.Text -> setBody(body.text)
                is HttpBody.FileRange -> setBody(FileRangeContent(fileSystem, body))
                is HttpBody.Multipart -> setBody(MultipartContent(fileSystem, body))
            }
            // Multipart is the exception: its content type carries the boundary the body just
            // wrote, and only the content itself knows what that is.
            plan.body?.takeIf { it !is HttpBody.Multipart }
                ?.let { contentType(ContentType.parse(it.contentType)) }
            // Both halves of the budget, not just the whole-request one: `socketTimeoutMillis`
            // is how long the connection may sit idle, it defaults to [READ_TIMEOUT_MS] for the
            // whole client, and a `clova` submission is a synchronous call that answers with the
            // entire transcript — the server says nothing for up to fifteen minutes (docs/08).
            // Leaving the socket at a minute would abort every one of them.
            plan.timeoutSec?.let { seconds ->
                timeout {
                    requestTimeoutMillis = seconds * 1000L
                    socketTimeoutMillis = seconds * 1000L
                }
            }
        }
        return HttpResult(
            status = response.status.value,
            headers = response.headers.entries().associate { it.key to it.value },
            body = response.readRawBytes(),
        )
    }

    /**
     * `Content-Type` and `Content-Length` are Ktor's to write — it throws on an attempt to set
     * them by hand — and the plans carry them for transports that need them spelled out
     * (`queryRequest`'s `Content-Length: 0`).
     */
    private fun HttpRequestBuilder.applyHeaders(plan: HttpPlan) {
        plan.headers.forEach { (name, value) ->
            if (!name.equals("Content-Type", ignoreCase = true) &&
                !name.equals("Content-Length", ignoreCase = true)
            ) {
                headers.append(name, value)
            }
        }
    }

    private fun HttpRequestBuilder.contentType(type: ContentType) {
        headers.remove("Content-Type")
        headers.append("Content-Type", type.toString())
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 60_000L

        private fun defaultClient(engine: HttpClientEngineFactory<*>): HttpClient = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = READ_TIMEOUT_MS
            }
        }
    }
}

/**
 * Writes a `multipart/form-data` body, streaming the parts that are files: the STT providers are
 * handed a whole recording, and a 40 MB `m4a` read into a byte array is an out-of-memory on a
 * phone (docs/08 "오디오 준비").
 *
 * `Content-Length` is spelled out rather than left to chunked encoding — a file part's length is
 * known before a byte is written, and an API gateway that refuses `Transfer-Encoding: chunked` is
 * not something a user could work around.
 */
private class MultipartContent(
    private val fileSystem: FileSystem,
    body: HttpBody.Multipart,
) : OutgoingContent.WriteChannelContent() {
    private val boundary = "recly${Random.nextULong().toString(16).padStart(16, '0')}"
    private val parts = body.parts.map { it to header(it) }

    override val contentType: ContentType =
        ContentType.parse("${HttpBody.Multipart.FORM_DATA}; boundary=$boundary")

    override val contentLength: Long =
        parts.sumOf { (part, header) -> header.size + length(part) + CRLF.length } + epilogue().size

    override suspend fun writeTo(channel: ByteWriteChannel) {
        parts.forEach { (part, header) ->
            channel.writeFully(header)
            when (val source = part.source) {
                is HttpBody.Multipart.Source.Bytes -> channel.writeFully(source.bytes)
                is HttpBody.Multipart.Source.File -> {
                    val file = fileSystem.source(source.path).buffer()
                    try {
                        val buffer = Buffer()
                        while (file.read(buffer, COPY_BUFFER) != -1L) {
                            channel.writeFully(buffer.readByteArray())
                        }
                    } finally {
                        file.close()
                    }
                }
            }
            channel.writeFully(CRLF.encodeToByteArray())
        }
        channel.writeFully(epilogue())
        channel.flush()
    }

    private fun header(part: HttpBody.Multipart.Part): ByteArray = buildString {
        append("--").append(boundary).append(CRLF)
        append("Content-Disposition: form-data; name=\"").append(part.name).append('"')
        part.filename?.let { append("; filename=\"").append(it).append('"') }
        append(CRLF)
        append("Content-Type: ").append(part.contentType).append(CRLF)
        append(CRLF)
    }.encodeToByteArray()

    private fun epilogue(): ByteArray = "--$boundary--$CRLF".encodeToByteArray()

    private fun length(part: HttpBody.Multipart.Part): Long = when (val source = part.source) {
        is HttpBody.Multipart.Source.Bytes -> source.bytes.size.toLong()
        is HttpBody.Multipart.Source.File -> fileSystem.metadata(source.path).size
            ?: error("cannot size multipart part '${part.name}' at ${source.path}")
    }

    private companion object {
        const val CRLF = "\r\n"
        const val COPY_BUFFER = 64L * 1024
    }
}

/** Streams a slice of a file so an 8 MiB chunk never sits in memory (docs/10). */
private class FileRangeContent(
    private val fileSystem: FileSystem,
    private val range: HttpBody.FileRange,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.parse(range.contentType)
    override val contentLength: Long = range.length

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = fileSystem.source(range.path).buffer()
        val buffer = Buffer()
        try {
            source.skip(range.offset)
            var remaining = range.length
            while (remaining > 0) {
                val read = source.read(buffer, minOf(remaining, COPY_BUFFER))
                if (read == -1L) break
                channel.writeFully(buffer.readByteArray(read))
                remaining -= read
            }
        } finally {
            source.close()
        }
        channel.flush()
    }

    private companion object {
        const val COPY_BUFFER = 64L * 1024
    }
}
