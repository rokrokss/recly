@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import okio.Path
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.RecordingRepository

/**
 * The local parts of a recording Drive already has are a cache: they stay [WINDOW] after the
 * upload succeeded and are then swept, and the detail screen fetches one back from Drive when it
 * wants to play it (`ReclyCore.audio`) — which gives that part the window again, from the file it
 * has just written. An upload that never succeeded keeps its parts forever: that half of the rule
 * is [JobStore.claimPurge]'s and is unchanged (ADR-017).
 *
 * The window is fixed by product decision: no setting, no UI, and no column of its own — the age
 * is derived from the part files' own mtimes and the recording's newest `updated_at`.
 */
class Retention(
    private val deps: CoreDeps,
    private val store: JobStore,
    private val recordings: RecordingRepository,
) {
    /**
     * Runs at the end of every [JobService.runDueJobs] pass, over every recording that still has a
     * part on disk. The rule and the claim are one transaction inside [JobStore.claimPurge], so a
     * job enqueued while this runs cannot lose its audio; only a granted claim deletes files.
     */
    suspend fun sweep(now: Instant) {
        val cutoff = now - WINDOW
        for (recordingId in store.recordingsWithLiveParts()) {
            // Rows without a recording to belong to: nothing to resolve the files against.
            val dir = recordings.get(recordingId)?.dir ?: continue
            val claim = store.claimPurge(recordingId) { files, latestUpdate ->
                files.all { ripe(dir / it, latestUpdate, cutoff) }
            }
            when (claim) {
                PurgeClaim.CLAIMED -> recordings.purgeParts(recordingId)
                PurgeClaim.OTHER_JOBS_PENDING -> retained(recordingId, "other_jobs_pending")
                PurgeClaim.SNAPSHOT_UNREADABLE -> retained(recordingId, "snapshot_unreadable")
                PurgeClaim.UPLOAD_NOT_SUCCEEDED -> retained(recordingId, "upload_not_succeeded")
                PurgeClaim.WITHIN_WINDOW -> retained(recordingId, "within_window")
            }
        }
    }

    /**
     * The age rule: `max(file mtime, latest job updated_at) <= now - WINDOW`. The job clock is in
     * it because a part whose upload only finished yesterday is not a week-old cache however old
     * the recording is; the file clock is in it because a part fetched back from Drive is written
     * now and starts its window over.
     *
     * A file that is already gone is nothing to wait for. A file system that will not say when one
     * was written cannot age it, and that part stays.
     */
    private fun ripe(path: Path, latestUpdate: Instant, cutoff: Instant): Boolean {
        val metadata = deps.fileSystem.metadataOrNull(path) ?: return true
        val modified = metadata.lastModifiedAtMillis ?: return false
        return maxOf(Instant.fromEpochMilliseconds(modified), latestUpdate) <= cutoff
    }

    private fun retained(recordingId: String, reason: String) {
        deps.logger.log(
            Logger.Level.INFO,
            "rec.retained",
            mapOf("recordingId" to recordingId, "reason" to reason),
        )
    }

    companion object {
        /** Fixed by product decision (2026-09-03): the app offers no way to change it. */
        val WINDOW = 7.days
    }
}
