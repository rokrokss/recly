@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/**
 * docs/03 "다른 기기의 녹음": the device that runs the workflow tells the others what is still to come.
 * Drive holds the recording's audio but says nothing about the `transcribe` that is going to take
 * another four minutes, so the folder itself carries it — [DriveFolderMarker.PENDING], the
 * comma-joined types of the steps still ahead, and [DriveFolderMarker.PENDING_AT], when that was
 * last true.
 *
 * The marker is **advisory**: it is one more line in someone else's list, and nothing this device
 * does depends on it, so every failure to write one is logged and dropped.
 */
interface FolderMarker {
    suspend fun mark(folderId: String, pending: List<String>)

    companion object {
        /** For the callers that have no Drive to write to — the tests, and a shell's own runners. */
        val NONE: FolderMarker = object : FolderMarker {
            override suspend fun mark(folderId: String, pending: List<String>) = Unit
        }
    }
}

/** [FolderMarker] against the recording's `{base}/` folder (ADR-014), through `files.update`. */
class DriveFolderMarker(
    private val api: DriveApi,
    private val deps: CoreDeps,
) : FolderMarker {
    override suspend fun mark(folderId: String, pending: List<String>) {
        try {
            api.updateAppProperties(
                folderId,
                mapOf(PENDING to pending.joinToString(","), PENDING_AT to deps.clock.now().isoUtc()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            deps.logger.log(
                Logger.Level.WARN,
                "drive.marker.failed",
                mapOf("folderId" to folderId, "pending" to pending.joinToString(",")),
                e,
            )
        }
    }

    companion object {
        /** `appProperties` of the recording folder: the step types after the upload, comma-joined. */
        const val PENDING: String = "pending"

        /** When [PENDING] was written — a marker nobody has refreshed since is not read (docs/03). */
        const val PENDING_AT: String = "pendingAt"
    }
}
