package recly.core.transcribe

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.string
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.AudioParts
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord

/** What a recording's detail screen shows (docs/08 "결과 파일"). It is absent until a `transcribe`
 * step has run. */
enum class TranscriptAvailability { PENDING, NOT_REQUESTED, FAILED, UNAVAILABLE, READY, EMPTY }

data class RecordingResult(
    val transcript: Transcript? = null,
    val availability: TranscriptAvailability = when {
        transcript == null -> TranscriptAvailability.PENDING
        transcript.segments.none { it.text.isNotBlank() } -> TranscriptAvailability.EMPTY
        else -> TranscriptAvailability.READY
    },
)

/**
 * Reads back what the step wrote (docs/08 "결과 파일"), for the app's own detail screen.
 *
 * The local copy is the fast path and the offline one; Drive is the fallback for a recording whose
 * step ran on another device, or whose files were restored without it. A download is kept as the
 * local copy, so the trip is made once.
 */
class RecordingResults(private val api: DriveApi, private val deps: CoreDeps) {

    /**
     * @param outputs the `StepOutput`s of the recording's job, newest last — where the Drive file
     * id is (`transcript.jsonFileId`).
     */
    suspend fun load(record: RecordingRecord, outputs: List<JsonObject>, repair: Boolean = false): RecordingResult = try {
        loadResult(record, outputs, repair)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        deps.logger.log(Logger.Level.WARN, "results.transcript.unreadable", mapOf("id" to record.id), e)
        RecordingResult(availability = TranscriptAvailability.UNAVAILABLE)
    }

    private suspend fun loadResult(record: RecordingRecord, outputs: List<JsonObject>, repair: Boolean): RecordingResult {
        val base = MetaWriter.baseName(record.meta)
        val name = TranscribeRunner.jsonFileName(base)
        val transcript = read(
            record,
            name,
            fileId(outputs, "transcript", "jsonFileId"),
            repair,
        )?.let { bytes ->
            recJson.decodeFromString<Transcript>(bytes.decodeToString())
        }
        return RecordingResult(transcript)
    }

    /** The local copy if there is one, else Drive's — which then becomes the local copy. */
    private suspend fun read(record: RecordingRecord, name: String, fileId: String?, repair: Boolean): ByteArray? = withContext(deps.io) {
        val dir = record.dir
        val local = dir / name
        val existing = resultFileMutex.withLock { localBytes(local) }
        if (existing != null && (!repair || valid(existing))) {
            return@withContext existing
        }
        val id = fileId ?: adoptedFileId(record, name) ?: return@withContext existing
        val bytes = try {
            api.download(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Keep an unavailable result distinct from an unfinished transcription; the shell
            // offers another read without restarting the workflow.
            deps.logger.log(Logger.Level.WARN, "results.download.failed", mapOf("name" to name), e)
            throw e
        }
        // Validate before publishing; a corrupt remote response must not poison the local cache.
        recJson.decodeFromString<Transcript>(bytes.decodeToString())
        resultFileMutex.withLock {
            // The same lock covers ResultFiles.write: a rerun that published during the request
            // wins, and cannot slip between this check and the atomic replacement.
            val current = localBytes(local)
            if (current != null && (!repair || valid(current))) {
                deps.logger.log(Logger.Level.INFO, "results.download.superseded", mapOf("name" to name))
                return@withLock current
            }
            // Beside the recording directories rather than in one of them, for the same reason as
            // `AudioParts`: `RecordingRepository.delete` takes the whole directory, and a temp open
            // inside it would go with it mid-write. A unique name per download, then a rename: a
            // half-written file is never visible as the local copy, and two downloads at once do not
            // hand `atomicMove` each other's temp file.
            val temps = (dir.parent ?: dir) / AudioParts.TEMP_DIR
            deps.fileSystem.createDirectories(temps)
            val temp = temps / "$name.${Random.nextInt(Int.MAX_VALUE)}.tmp"
            deps.fileSystem.write(temp) { write(bytes) }
            deps.fileSystem.createDirectories(dir)
            deps.fileSystem.atomicMove(temp, local)
            deps.logger.log(Logger.Level.INFO, "results.download", mapOf("name" to name, "bytes" to bytes.size))
            bytes
        }
    }

    private fun localBytes(path: Path): ByteArray? =
        if (deps.fileSystem.exists(path)) deps.fileSystem.read(path) { readByteArray() } else null

    private fun valid(bytes: ByteArray): Boolean =
        runCatching { recJson.decodeFromString<Transcript>(bytes.decodeToString()) }.isSuccess

    /** The last step that produced one wins: a workflow re-run replaces what the earlier one wrote. */
    private fun fileId(outputs: List<JsonObject>, group: String, field: String): String? =
        outputs.mapNotNull { it[group]?.jsonObject?.string(field) }.lastOrNull()

    /**
     * An adopted recording (docs/03 "다른 기기의 녹음") has no step output here: the transcript, if the
     * other device made one, is a file in its Drive folder under the name the step gives it. Looked
     * up at each opening until a copy is here — a transcript that lands later is found later — and
     * skipped when a valid copy is already local, including during an explicit repair.
     */
    private suspend fun adoptedFileId(record: RecordingRecord, name: String): String? {
        val folderId = record.driveFolderId ?: return null
        return try {
            api.findChild(folderId, name)?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            deps.logger.log(Logger.Level.WARN, "results.lookup.failed", mapOf("name" to name), e)
            throw e
        }
    }
}
