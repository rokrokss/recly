@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import okio.Path
import okio.Path.Companion.toPath
import recly.core.ids.Ulid
import recly.core.message.CoreMessage
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/** One part the shell's transcoder wrote: a file in the directory it was given, and its length. */
data class ImportedPart(val file: String, val durationSec: Double)

/** What the shell's transcoder made of a file. */
sealed interface TranscodeResult {
    /** The parts, in order. */
    data class Done(val parts: List<ImportedPart>) : TranscodeResult

    /** The file opened but holds no audio this platform can decode — a document, an image, a DRM-locked track. */
    data object Unsupported : TranscodeResult

    /** The file could not be read at all: gone, refused, or broken. */
    data object Unreadable : TranscodeResult
}

/**
 * The platform half of an import (docs/03 "Naming rules", `source: import`): the shell's decoder and
 * encoder — `MediaExtractor`/`MediaCodec`, `AVAssetReader`/`AVAssetWriter`, the bundled ffmpeg — turn
 * a file the user picked, audio or video, into ADR-006 parts: AAC-LC `.m4a`, 16 kHz mono 32 kbps, cut
 * every [segmentSec] seconds. The files go into [outDir], under any names; the core gives them their
 * part names when the import is complete. Must cooperate with cancellation.
 */
interface AudioImporter {
    @Throws(Throwable::class)
    suspend fun transcode(sourcePath: String, outDir: String, segmentSec: Int): TranscodeResult
}

/** What `ReclyCore.importAudio` did. */
sealed interface ImportResult {
    /** A finished recording like any other, its fixed plan queued. */
    data class Imported(val recordingId: String) : ImportResult

    /**
     * Nothing is left of it — no row, no files. [reason] is a [CoreMessage] code: [CoreMessage.IMPORT_UNSUPPORTED]
     * or [CoreMessage.IMPORT_UNREADABLE], with what the platform said as its detail.
     */
    data class Failed(val reason: String) : ImportResult
}

/**
 * docs/03 "Naming rules": a file from outside becomes a recording of this device's, `source: import`.
 *
 * While the shell transcodes, the row is there with status `recording` and no parts
 * ([RecordingRecord.importing]) so the list can say so; the parts are written into a directory of their
 * own beside the recordings, and only once every one of them is there are they moved in, registered and
 * finalized in one pass ([RecordingRepository.completeImport]) and the plan queued. A failure — or a
 * cancellation — takes the row and every file with it: an import is whole or it is not there. A row a
 * killed process left behind is the core's to drop ([dropAbandoned]), never one for a recorder's crash
 * recovery to finalize.
 */
internal class AudioImport(
    private val deps: CoreDeps,
    private val recordings: RecordingRepository,
    private val enqueue: suspend (String) -> Unit,
) {
    private val gate = Mutex()
    private val running = mutableSetOf<String>()

    suspend fun import(sourcePath: String, displayName: String, startedAt: Instant?, importer: AudioImporter): ImportResult {
        val start = startedAt ?: deps.clock.now()
        val id = Ulid.generate(object : kotlin.time.Clock {
            override fun now(): Instant = start
        })
        val meta = RecordingMeta(
            schema = 1,
            recordingId = id,
            source = Source.IMPORT,
            platform = deps.device.platform,
            deviceId = deps.device.deviceId,
            deviceName = deps.device.name,
            title = titleOf(displayName),
            startedAt = start.isoUtc(),
            timezone = TimeZone.currentSystemDefault().id,
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, SEGMENT_SEC),
            tracks = listOf(Track.MONO),
            parts = emptyList(),
            status = RecordingStatus.RECORDING,
        )
        val base = MetaWriter.baseName(meta)
        val dir = deps.dataDir / RecordingRepository.RECORDINGS / base
        val staging = deps.dataDir / STAGING / id
        gate.withLock { running += id }
        try {
            sweepStaging()
            withContext(deps.io) { deps.fileSystem.createDirectories(staging) }
            recordings.create(meta, dir)
            val outcome = try {
                importer.transcode(sourcePath, staging.toString(), SEGMENT_SEC)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return fail(id, staging, CoreMessage.IMPORT_UNREADABLE.code(detail = e.message ?: e::class.simpleName))
            }
            val parts = when (outcome) {
                is TranscodeResult.Done -> outcome.parts
                TranscodeResult.Unsupported -> return fail(id, staging, CoreMessage.IMPORT_UNSUPPORTED.code())
                TranscodeResult.Unreadable -> return fail(id, staging, CoreMessage.IMPORT_UNREADABLE.code())
            }
            val staged = stage(staging, base, parts)
                ?: return fail(id, staging, CoreMessage.IMPORT_UNSUPPORTED.code(detail = "no audio parts"))
            val durationSec = staged.sumOf { it.second.durationSec }
            recordings.completeImport(id, staged, start + (durationSec * 1000).toLong().milliseconds, durationSec)
            withContext(deps.io) { deps.fileSystem.deleteRecursively(staging, mustExist = false) }
            deps.logger.log(Logger.Level.INFO, "rec.import", mapOf("recordingId" to id, "parts" to staged.size, "durationSec" to durationSec))
        } catch (e: Throwable) {
            // A cancellation included: half an import is never left to be finalized and uploaded.
            withContext(NonCancellable) { discard(id, staging) }
            throw e
        } finally {
            withContext(NonCancellable) { gate.withLock { running -= id } }
        }
        // Complete from here on: a queue that cannot take it now is a recording waiting for its job,
        // which the recorders' recovery queues, not an import to undo.
        enqueue(id)
        return ImportResult.Imported(id)
    }

    /** Whether [recordingId] is an import this process is running now. */
    suspend fun running(recordingId: String): Boolean = gate.withLock { recordingId in running }

    /**
     * A recorder's crash recovery meets an import row still saying `recording`: one being imported right
     * now is left alone (false); one a killed process left is dropped with its files (true).
     */
    suspend fun dropAbandoned(recordingId: String): Boolean {
        if (running(recordingId)) return false
        val record = recordings.get(recordingId) ?: return false
        if (!record.importing) return false
        recordings.delete(recordingId)
        withContext(deps.io) { deps.fileSystem.deleteRecursively(deps.dataDir / STAGING / recordingId, mustExist = false) }
        deps.logger.log(Logger.Level.WARN, "rec.import.abandoned", mapOf("recordingId" to recordingId))
        return true
    }

    /**
     * The transcoder's files, hashed and given their part names, in order; null when there is no part,
     * a part has no length, or a name is not a plain file in [staging].
     */
    private suspend fun stage(staging: Path, base: String, parts: List<ImportedPart>): List<Pair<Path, Part>>? {
        if (parts.isEmpty()) return null
        var offset = 0.0
        return parts.mapIndexed { index, imported ->
            val file = imported.file.toPath()
            if (file.isAbsolute || file.segments.size != 1 || imported.file == ".." || !(imported.durationSec > 0)) return null
            val path = staging / imported.file
            val bytes = withContext(deps.io) { deps.fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size } ?: return null
            val part = Part(
                part = index + 1,
                track = Track.MONO,
                file = MetaWriter.partFileName(base, index + 1, Track.MONO),
                bytes = bytes,
                sha256 = withContext(deps.io) { PartHasher.sha256(deps.fileSystem, path) },
                startOffsetSec = offset,
                durationSec = imported.durationSec,
            )
            offset += imported.durationSec
            path to part
        }
    }

    private suspend fun fail(id: String, staging: Path, reason: String): ImportResult {
        discard(id, staging)
        deps.logger.log(Logger.Level.WARN, "rec.import.failed", mapOf("recordingId" to id, "reason" to reason))
        return ImportResult.Failed(reason)
    }

    private suspend fun discard(id: String, staging: Path) {
        recordings.delete(id)
        withContext(deps.io) { deps.fileSystem.deleteRecursively(staging, mustExist = false) }
    }

    /** Staging directories no import of this process owns: left by a kill. */
    private suspend fun sweepStaging() {
        val root = deps.dataDir / STAGING
        val owned = gate.withLock { running.toSet() }
        withContext(deps.io) {
            deps.fileSystem.listOrNull(root)?.filter { it.name !in owned }?.forEach { deps.fileSystem.deleteRecursively(it, mustExist = false) }
        }
    }

    /** The file's name without its extension, as the title; none when that leaves nothing. */
    private fun titleOf(displayName: String): String? {
        val name = displayName.trim()
        val dot = name.lastIndexOf('.')
        return (if (dot > 0) name.substring(0, dot) else name).trim().take(TITLE_MAX).takeIf { it.isNotEmpty() }
    }

    companion object {
        /** ADR-006's nominal part length. */
        const val SEGMENT_SEC: Int = 900

        /** Beside the recordings, so nothing that scans a recording's directory meets a half-made part. */
        private const val STAGING = "import-tmp"

        /** `spec/recording.meta.schema.json` `title`. */
        private const val TITLE_MAX = 200
    }
}
