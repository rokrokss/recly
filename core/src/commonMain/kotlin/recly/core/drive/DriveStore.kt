@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import recly.core.db.RecDatabase
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.wire
import recly.core.platform.CoreDeps

/** A resolved folder and when we last saw it on Drive. */
data class CachedFolder(val folderId: String, val checkedAt: Instant)

/**
 * The two bits of Drive bookkeeping that outlive a single job: the folder ids we resolved and the
 * md5 of each part. Hashing a 3.6 MB segment is cheap but not free, and every retry of an upload
 * would otherwise redo it. Same discipline as the other stores — one mutex on [CoreDeps.io].
 */
class DriveStore(
    private val db: RecDatabase,
    private val deps: CoreDeps,
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    suspend fun md5(recordingId: String, part: Int, track: Track): String? = locked {
        queries.selectPart(recordingId, part.toLong(), track.wire).executeAsOneOrNull()?.md5
    }

    suspend fun putMd5(recordingId: String, part: Int, track: Track, md5: String): Unit = locked {
        queries.updatePartMd5(md5, recordingId, part.toLong(), track.wire)
    }

    suspend fun folder(path: String): CachedFolder? = locked {
        queries.selectFolderCache(path).executeAsOneOrNull()
            ?.let { CachedFolder(it.folder_id, Instant.parse(it.checked_at)) }
    }

    suspend fun putFolder(path: String, folderId: String, checkedAt: Instant): Unit = locked {
        queries.upsertFolderCache(path, folderId, checkedAt.isoUtc())
    }

    suspend fun forgetFolder(path: String): Unit = locked { queries.deleteFolderCache(path) }

    /** "연결 해제" (docs/03): the ids are about someone else's Drive once the grant is gone. */
    suspend fun forgetAllFolders(): Unit = locked { queries.deleteAllFolderCache() }

    /** The recording's own `{base}/` folder, on its row (docs/03): survives the queue rows. */
    suspend fun rememberRecordingFolder(recordingId: String, folderId: String): Unit = locked {
        queries.updateRecordingFolder(folderId, recordingId)
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }
}
