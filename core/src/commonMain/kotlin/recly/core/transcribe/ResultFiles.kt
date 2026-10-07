package recly.core.transcribe

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.Path
import recly.core.storage.CloudFiles
import recly.core.drive.DriveFileMeta
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/** Serializes local publication with recovery so a newer valid result always wins. */
internal val resultFileMutex = Mutex()

/**
 * Where a `transcribe` result goes (docs/08 "Result files"): the recording directory, so the app and
 * the next step can read it without a round trip, and the Drive folder the preceding
 * `drive.upload` made, so every other device can.
 *
 * The Drive write follows the upload step's rule: same name and same md5 is left alone, a
 * different md5 is overwritten — running the workflow again makes the newest result the canonical
 * one instead of piling up duplicates.
 */
internal class ResultFiles(private val api: CloudFiles, private val deps: CoreDeps) {
    suspend fun write(
        dir: Path,
        folderId: String,
        name: String,
        content: ByteArray,
        mimeType: String,
        /** Set on the Drive file with its content ([TranscriptMarks]); the other storages keep none. */
        appProperties: Map<String, String> = emptyMap(),
    ): ResultFile {
        resultFileMutex.withLock {
            deps.fileSystem.createDirectories(dir)
            deps.fileSystem.write(dir / name) { write(content) }
        }

        val md5 = content.toByteString().md5().hex()
        val existing = api.findChildren(folderId, name)
        val same = existing.firstOrNull { it.md5 == md5 }
        val file = when {
            same != null -> {
                deps.logger.log(Logger.Level.INFO, "drive.skip", mapOf("name" to name, "fileId" to same.id))
                same
            }

            existing.isNotEmpty() -> api.updateMedia(existing.first().id, content, mimeType, appProperties)
            else -> api.multipartUpload(DriveFileMeta(name, listOf(folderId), mimeType, appProperties), content)
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

/**
 * docs/08 "Result files": what Recly writes beside a transcript on Drive. The `.transcript.json` and
 * `.transcript.txt` files say which kind of version they hold — [TRANSCRIBED] by a transcription, the
 * first or a re-run, [EDITED] by the user's edit — so a watcher such as recly-events can tell a new
 * transcript from a correction. The recording's folder carries [FOLDER_STAMP], the version of the
 * newest transcript in it (its `editedAt`, else its `createdAt`), so other devices know when the copy
 * they hold is old without opening the folder.
 */
object TranscriptMarks {
    const val KEY: String = "reclyTranscript"
    const val TRANSCRIBED: String = "transcribed"
    const val EDITED: String = "edited"
    const val FOLDER_STAMP: String = "transcriptAt"

    fun of(transcript: Transcript): Map<String, String> = mapOf(KEY to if (transcript.editedAt != null) EDITED else TRANSCRIBED)

    fun version(transcript: Transcript): String = transcript.editedAt ?: transcript.createdAt
}

/** One written result file, in the shape of a `drive.upload` output's `files[]`. */
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
