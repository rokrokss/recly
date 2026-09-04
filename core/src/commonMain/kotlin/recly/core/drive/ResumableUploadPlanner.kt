package recly.core.drive

import kotlinx.serialization.json.JsonObject
import okio.Path
import recly.core.model.Platform
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult

/**
 * The resumable protocol as pure data (ADR-015): this builds the next request and reads the
 * answer, and knows nothing about sockets. A shell that wants a background `URLSession` for the
 * chunk PUTs runs the very same plans.
 */
object ResumableUploadPlanner {
    /** Drive requires every chunk but the last to be a multiple of 256 KiB. */
    const val CHUNK_UNIT: Long = 262_144

    const val FILE_FIELDS: String = "id,name,md5Checksum,webViewLink"

    internal const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    internal const val JSON_TYPE = "application/json; charset=UTF-8"
    internal const val OCTET_TYPE = "application/octet-stream"

    fun startRequest(meta: DriveFileMeta, totalBytes: Long, token: String): HttpPlan {
        require(totalBytes >= 0) { "totalBytes must not be negative, was $totalBytes" }
        return HttpPlan(
            method = "POST",
            url = "$UPLOAD_URL?uploadType=resumable&fields=${urlEncode(FILE_FIELDS)}",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-Upload-Content-Type" to meta.mimeType,
                "X-Upload-Content-Length" to totalBytes.toString(),
            ),
            body = HttpBody.Text(meta.toJson().toString(), JSON_TYPE),
        )
    }

    fun chunkRequest(
        sessionUri: String,
        offset: Long,
        length: Long,
        total: Long,
        path: Path,
        token: String,
    ): HttpPlan {
        require(length > 0) { "chunk length must be positive, was $length" }
        require(offset >= 0 && offset + length <= total) { "chunk $offset+$length overflows total $total" }
        require(offset + length == total || length % CHUNK_UNIT == 0L) {
            "a non-final chunk must be a multiple of $CHUNK_UNIT, was $length"
        }
        return HttpPlan(
            method = "PUT",
            url = sessionUri,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Range" to "bytes $offset-${offset + length - 1}/$total",
            ),
            body = HttpBody.FileRange(path, offset, length, OCTET_TYPE),
        )
    }

    /** "How much did you get?" — sent after a failure, before deciding where to continue from. */
    fun queryRequest(sessionUri: String, total: Long, token: String): HttpPlan = HttpPlan(
        method = "PUT",
        url = sessionUri,
        headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Length" to "0",
            "Content-Range" to "bytes */$total",
        ),
        body = null,
    )

    fun onResponse(result: HttpResult): Outcome =
        onResponse(result.status, result.headers, result.body.decodeToString())

    fun onResponse(status: Int, headers: Map<String, List<String>>, body: String?): Outcome {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        return when {
            status == 401 -> Outcome.Unauthorized

            status == 200 || status == 201 -> {
                val file = DriveFile.from(parseObject(body))
                val location = header("Location")
                when {
                    file != null -> Outcome.Done(file)
                    location != null -> Outcome.SessionStarted(location)
                    else -> Outcome.Fail("HTTP $status without an id or a Location")
                }
            }

            // 308 Resume Incomplete. No Range header at all means Drive holds nothing yet.
            status == 308 -> Outcome.Continue(nextOffset(header("Range")))

            status == 404 || status == 410 -> Outcome.Restart

            status == 408 || status == 429 || status >= 500 ->
                Outcome.RetryAfter(header("Retry-After")?.trim()?.toIntOrNull())

            else -> Outcome.Fail("HTTP $status ${body.orEmpty().take(200)}")
        }
    }

    /** `bytes=0-1310719` means 1310720 bytes are stored, so the next one to send is that index. */
    private fun nextOffset(range: String?): Long {
        val end = range?.substringAfter('-', "")?.trim()?.toLongOrNull() ?: return 0
        return end + 1
    }

    private fun parseObject(body: String?): JsonObject? =
        if (body.isNullOrBlank()) {
            null
        } else {
            runCatching { driveJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        }

    /** docs/10: mobile and watch 1 MiB, desktop 8 MiB — both multiples of [CHUNK_UNIT]. */
    fun chunkSize(platform: Platform): Long = when (platform) {
        Platform.WEAROS, Platform.ANDROID, Platform.IOS, Platform.WATCHOS -> 1L * 1024 * 1024
        Platform.MACOS, Platform.WINDOWS -> 8L * 1024 * 1024
    }

    sealed interface Outcome {
        data class SessionStarted(val uri: String) : Outcome

        data class Continue(val nextOffset: Long) : Outcome

        data class Done(val file: DriveFile) : Outcome

        /** The session is gone; anything already sent is lost and a new session must be opened. */
        data object Restart : Outcome

        data class RetryAfter(val sec: Int?) : Outcome

        data object Unauthorized : Outcome

        data class Fail(val reason: String) : Outcome
    }
}
