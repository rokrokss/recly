package recly.core.transcribe

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.withContext
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
data class RecordingResult(val transcript: Transcript? = null)

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
    suspend fun load(record: RecordingRecord, outputs: List<JsonObject>): RecordingResult {
        val base = MetaWriter.baseName(record.meta)
        val name = TranscribeRunner.jsonFileName(base)
        val transcript = read(
            record.dir,
            name,
            fileId(outputs, "transcript", "jsonFileId") ?: adoptedFileId(record, name),
        )?.let { bytes ->
            runCatching { recJson.decodeFromString<Transcript>(bytes.decodeToString()) }
                .onFailure {
                    deps.logger.log(Logger.Level.WARN, "results.transcript.unreadable", mapOf("id" to record.id))
                }
                .getOrNull()
        }
        return RecordingResult(transcript)
    }

    /** The local copy if there is one, else Drive's — which then becomes the local copy. */
    private suspend fun read(dir: Path, name: String, fileId: String?): ByteArray? = withContext(deps.io) {
        val local = dir / name
        if (deps.fileSystem.exists(local)) {
            return@withContext deps.fileSystem.read(local) { readByteArray() }
        }
        val id = fileId ?: return@withContext null
        val bytes = try {
            api.download(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Nothing to show, and nothing the user can do about it here: the screen says so and
            // the next opening tries again.
            deps.logger.log(Logger.Level.WARN, "results.download.failed", mapOf("name" to name), e)
            return@withContext null
        }
        // A `transcribe` rerun may have written a newer local copy while the download was in
        // flight; older Drive bytes must not land on top of it.
        if (deps.fileSystem.exists(local)) {
            deps.logger.log(Logger.Level.INFO, "results.download.superseded", mapOf("name" to name))
            return@withContext deps.fileSystem.read(local) { readByteArray() }
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

    /** The last step that produced one wins: a workflow re-run replaces what the earlier one wrote. */
    private fun fileId(outputs: List<JsonObject>, group: String, field: String): String? =
        outputs.mapNotNull { it[group]?.jsonObject?.string(field) }.lastOrNull()

    /**
     * An adopted recording (docs/03 "다른 기기의 녹음") has no step output here: the transcript, if the
     * other device made one, is a file in its Drive folder under the name the step gives it. Looked
     * up at each opening until a copy is here — a transcript that lands later is found later — and
     * skipped when the file is already local, since [read] never asks for the id then.
     */
    private suspend fun adoptedFileId(record: RecordingRecord, name: String): String? {
        val folderId = record.driveFolderId ?: return null
        if (deps.fileSystem.exists(record.dir / name)) return null
        return try {
            api.findChild(folderId, name)?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            deps.logger.log(Logger.Level.WARN, "results.lookup.failed", mapOf("name" to name), e)
            null
        }
    }
}
