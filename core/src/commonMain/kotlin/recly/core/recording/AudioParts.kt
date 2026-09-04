package recly.core.recording

import kotlin.random.Random
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.string
import recly.core.model.Part
import recly.core.model.Track
import recly.core.model.wire
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/**
 * The audio the detail screen plays: one track's parts, in part order.
 *
 * [paths] are the files that are on this device; [missing] names the parts that are not — a part
 * that was never uploaded and has since gone, or one whose fetch did not verify. Playing what is
 * here with a gap named is better than refusing to play at all.
 */
data class RecordingAudio(
    val track: Track,
    val paths: List<Path>,
    val missing: List<Int>,
)

/**
 * Reads a recording's audio back for playback (docs/03 "로컬 저장"), the way
 * [recly.core.transcribe.RecordingResults] reads the transcript: the local file is the fast path
 * and the offline one, and Drive is the fallback for a part the retention sweep has already taken.
 *
 * A fetched part is kept — written into the recording's directory under the name its row gives it
 * and marked present again — so the trip is made once and the sweep starts its window over.
 */
class AudioParts(
    private val api: DriveApi,
    private val recordings: RecordingRepository,
    private val deps: CoreDeps,
) {
    /**
     * @param outputs the `StepOutput`s of the recording's jobs, newest last — where the Drive file
     * id of each uploaded part is (`files[] {part, track, fileId}`).
     */
    suspend fun load(record: RecordingRecord, outputs: List<JsonObject>): RecordingAudio =
        withContext(deps.io) {
            // What the recording was mixed down to if it has one, and the single track otherwise:
            // playing the mic and the system tracks at once is not this screen's job.
            val track = if (record.meta.parts.any { it.track == Track.MIX }) Track.MIX else Track.MONO
            val paths = mutableListOf<Path>()
            val missing = mutableListOf<Int>()
            // An adopted recording has no upload output here; its parts' ids came with the row.
            val adopted = if (record.remote) recordings.driveFileIds(record.id) else emptyMap()
            record.meta.parts.filter { it.track == track }.sortedBy { it.part }.forEach { part ->
                val path = record.dir / part.file
                when {
                    deps.fileSystem.exists(path) -> paths += path
                    fetch(record, part, fileId(outputs, part) ?: adopted[part.part to part.track]) -> paths += path
                    else -> missing += part.part
                }
            }
            RecordingAudio(track, paths, missing)
        }

    /**
     * True when the part is on disk afterwards. A part no upload output names was never in Drive —
     * there is nothing to fetch, and it stays missing — and so does one whose bytes do not hash to
     * what the row says: half a file under the name of a part is worse than no file at all.
     *
     * Everything else — no token, a network that is not there, Drive refusing — travels to the
     * caller, so the screen can say what went wrong instead of showing a recording as half gone.
     */
    private suspend fun fetch(
        record: RecordingRecord,
        part: Part,
        fileId: String?,
    ): Boolean {
        if (fileId == null) return false
        val bytes = api.download(fileId)
        val sha256 = PartHasher.sha256(bytes)
        if (sha256 != part.sha256) {
            deps.logger.log(
                Logger.Level.WARN,
                "audio.download.mismatch",
                mapOf("recordingId" to record.id, "part" to part.part, "sha256" to sha256),
            )
            return false
        }
        // Beside the recordings rather than inside `record.dir`: `RecordingRepository.delete` takes
        // that whole directory, and a temp open in it would be deleted out from under the write —
        // or, on Windows, refuse to be deleted and leave the directory behind. Nothing sweeps this
        // one. A unique name per fetch, then a rename: a half-written file is never visible as the
        // part, and two fetches at once do not hand `atomicMove` each other's temp file.
        val temps = (record.dir.parent ?: record.dir) / TEMP_DIR
        deps.fileSystem.createDirectories(temps)
        val temp = temps / "${part.file}.${Random.nextInt(Int.MAX_VALUE)}.tmp"
        deps.fileSystem.write(temp) { write(bytes) }
        // The rename and the mark belong to the repository's lock: a purge or a delete that runs
        // while this was downloading must not land between the two ([RecordingRepository
        // .restorePart]). A recording that was deleted meanwhile leaves the temp to be dropped.
        if (recordings.restorePart(record.id, part.part, part.track, temp) == null) {
            deps.logger.log(
                Logger.Level.WARN,
                "audio.download.gone",
                mapOf("recordingId" to record.id, "part" to part.part),
            )
            return false
        }
        deps.logger.log(
            Logger.Level.INFO,
            "audio.download",
            mapOf("recordingId" to record.id, "part" to part.part, "bytes" to bytes.size),
        )
        return true
    }

    /** The last upload that named this part wins: a re-run replaces the file the earlier one made. */
    private fun fileId(outputs: List<JsonObject>, part: Part): String? =
        outputs.mapNotNull { output ->
            (output["files"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .lastOrNull { it.matches(part) }
                ?.string("fileId")
        }.lastOrNull()

    private fun JsonObject.matches(part: Part): Boolean =
        (this["part"] as? JsonPrimitive)?.intOrNull == part.part && string("track") == part.track.wire

    companion object {
        /** Sibling of the recording directories, so no recording's deletion can touch it. */
        const val TEMP_DIR: String = ".tmp"
    }
}
