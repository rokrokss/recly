package recly.core.transcribe

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.DriveFileMeta
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/**
 * Where a `transcribe` result goes (docs/08 "결과 파일"): the recording directory, so the app and
 * the next step can read it without a round trip, and the Drive folder the preceding
 * `drive.upload` made, so every other device and the webhook receiver can.
 *
 * The Drive write follows the upload step's rule: same name and same md5 is left alone, a
 * different md5 is overwritten — running the workflow again makes the newest result the canonical
 * one instead of piling up duplicates.
 */
internal class ResultFiles(private val api: DriveApi, private val deps: CoreDeps) {
    suspend fun write(
        dir: Path,
        folderId: String,
        name: String,
        content: ByteArray,
        mimeType: String,
    ): ResultFile {
        deps.fileSystem.createDirectories(dir)
        deps.fileSystem.write(dir / name) { write(content) }

        val md5 = content.toByteString().md5().hex()
        val existing = api.findChildren(folderId, name)
        val same = existing.firstOrNull { it.md5 == md5 }
        val file = when {
            same != null -> {
                deps.logger.log(Logger.Level.INFO, "drive.skip", mapOf("name" to name, "fileId" to same.id))
                same
            }

            existing.isNotEmpty() -> api.updateMedia(existing.first().id, content, mimeType)
            else -> api.multipartUpload(DriveFileMeta(name, listOf(folderId), mimeType), content)
        }
        return ResultFile(
            name = name,
            bytes = content.size.toLong(),
            sha256 = content.toByteString().sha256().hex(),
            fileId = file.id,
            webViewLink = file.webViewLink,
        )
    }
}

/** One written result file, in the shape the webhook payload's `files[]` needs (docs/04). */
internal data class ResultFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val fileId: String,
    val webViewLink: String?,
) {
    fun toJson(track: String): JsonObject = buildJsonObject {
        put("part", 0)
        put("track", track)
        put("name", name)
        put("bytes", bytes)
        put("sha256", sha256)
        put("fileId", fileId)
        webViewLink?.let { put("webViewLink", it) }
    }
}
