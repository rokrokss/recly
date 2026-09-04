package recly.core.platform

import okio.Path

/**
 * The core plans HTTP requests as data (ADR-015) and the shell executes them, so Apple can hand
 * chunk uploads to a background `URLSession` without the core knowing.
 */
interface Transport {
    suspend fun execute(plan: HttpPlan): HttpResult
}

data class HttpPlan(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody? = null,
    /**
     * Webhooks are posted to a URL the user typed, so a 3xx must come back as a 3xx: following it
     * would replay a signed body at an address the signature never promised (docs/04).
     */
    val followRedirects: Boolean = true,
    /** Whole-request budget. Null leaves it to the transport's own defaults. */
    val timeoutSec: Int? = null,
)

sealed class HttpBody {
    abstract val contentType: String

    data class Bytes(val bytes: ByteArray, override val contentType: String) : HttpBody() {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Bytes && contentType == other.contentType && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }

    /** A slice of a file, so a resumable chunk never has to be held in memory. */
    data class FileRange(
        val path: Path,
        val offset: Long,
        val length: Long,
        override val contentType: String,
    ) : HttpBody()

    data class Text(val text: String, override val contentType: String) : HttpBody()

    /**
     * `multipart/form-data`, which is how both STT providers that take an upload want the audio
     * (docs/08 `clova` `media`, `rtzr` `file`). The boundary belongs to the transport — it is the
     * one that writes the body — so [contentType] here is the bare media type.
     */
    data class Multipart(val parts: List<Part>) : HttpBody() {
        override val contentType: String = FORM_DATA

        /** [filename] is what makes a part a file upload rather than a plain form field. */
        data class Part(
            val name: String,
            val contentType: String,
            val source: Source,
            val filename: String? = null,
        )

        sealed interface Source {
            data class Bytes(val bytes: ByteArray) : Source {
                override fun equals(other: Any?): Boolean =
                    this === other || (other is Bytes && bytes.contentEquals(other.bytes))

                override fun hashCode(): Int = bytes.contentHashCode()
            }

            /** Streamed, so a 40 MB recording is never held in memory to be uploaded. */
            data class File(val path: Path) : Source
        }

        companion object {
            const val FORM_DATA: String = "multipart/form-data"
        }
    }
}

data class HttpResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** HTTP header names are case-insensitive; transports disagree on how they normalise them. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is HttpResult && status == other.status && headers == other.headers && body.contentEquals(other.body))

    override fun hashCode(): Int = (31 * status + headers.hashCode()) * 31 + body.contentHashCode()
}
