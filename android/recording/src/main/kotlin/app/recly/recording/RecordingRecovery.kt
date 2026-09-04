@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.ReclyCore
import recly.core.job.JobStatus
import recly.core.model.RecordingStatus
import recly.core.platform.Logger
import recly.core.recording.RecordingRecord

/**
 * What the process finds when it comes back from a kill: docs/03 promises a recording is
 * recoverable "up to the last boundary", and that promise is only kept if somebody looks.
 *
 * Four things get left behind. Segment files the encoder finished but the database never heard
 * about — the boundary callback lost its race with the kill. Parts whose `addPart` failed and left
 * a `.pending` marker, which a stop refuses to finalize over. A row still marked `recording`, which
 * no list screen and no executor will ever act on. And a recording that was finalized while the
 * user was still being asked for its title, and so was never enqueued.
 *
 * Runs at process start and again before every new recording, under one mutex so the two callers
 * cannot both be scanning while a third starts writing.
 */
class RecordingRecovery internal constructor(
    private val core: ReclyCore,
    private val host: RecorderHost,
    duration: DurationProbe,
) {
    /** What every caller outside this module gets: the platform reads a segment's length. */
    constructor(core: ReclyCore, host: RecorderHost) : this(core, host, MediaDuration)

    private val reconciler = PartReconciler(core, duration)

    /** How many recordings this changed — recovered, enqueued or dropped. */
    suspend fun reconcile(): Int = mutex.withLock {
        var touched = 0
        core.recordings.list(LIMIT).forEach { record ->
            val handled = when (record.meta.status) {
                RecordingStatus.RECORDING -> recover(record)
                RecordingStatus.FINALIZED -> settle(record)
                RecordingStatus.TRANSFERRED -> false
            }
            if (handled) touched++
        }
        touched
    }

    /** Files first, then the row: a finalize with parts missing would write the wrong duration. */
    private suspend fun recover(record: RecordingRecord): Boolean {
        val reconciled = reconciler.reconcile(record.id) ?: return false

        if (reconciled.files == 0) {
            // Nothing readable was ever recorded: no file at all, or only the tail the kill left
            // unreadable — quarantined by this pass or an earlier one. A row that stays `recording`
            // for good is one the user can do nothing with from the app, so the row and the
            // directory go, the quarantined bytes with them (2026-09-04 decision, docs/03).
            core.recordings.delete(record.id)
            log(Logger.Level.WARN, "rec.recovered.empty", record.id, 0, 0.0)
            return true
        }
        if (reconciled.pending > 0) {
            // Still unfilable — the disk or the database is unhappy. Leave the row open so the next
            // pass tries again rather than finalizing a recording that is missing audio.
            log(Logger.Level.ERROR, "rec.recovered.pendingRemains", record.id, reconciled.pending, reconciled.durationSec)
            return true
        }

        core.recordings.finalize(
            recordingId = record.id,
            endedAt = Instant.fromEpochMilliseconds(reconciled.endedAtMs),
            durationSec = reconciled.durationSec,
            title = null,
        )
        log(Logger.Level.WARN, "rec.recovered", record.id, reconciled.files, reconciled.durationSec)
        ready(record.id)
        return true
    }

    /**
     * A finalized recording is normally finished business, and two things can still be wrong with
     * it: a part that never made it in (a stop that deferred, or a boundary marker), and a missing
     * job (the process died while the title dialog was open).
     *
     * A finalized row with a job and no markers is skipped without touching the disk — a part can
     * only go missing while the row still says `recording`, which the other path covers.
     */
    private suspend fun settle(record: RecordingRecord): Boolean {
        val jobs = core.recordings.jobStatuses(record.id)
        if (jobs.isNotEmpty() && reconciler.markers(record.dir).isEmpty()) return false

        val reconciled = reconciler.reconcile(record.id) ?: return false
        if (reconciled.registered > 0) {
            // The meta's duration was written without this part; it has to say so now.
            reconciler.refinalize(record, reconciled)
            val event = if (JobStatus.DONE in jobs) "rec.recovered.partLate" else "rec.recovered.part"
            log(Logger.Level.WARN, event, record.id, reconciled.registered, reconciled.durationSec)
        }
        if (reconciled.pending > 0) {
            log(Logger.Level.ERROR, "rec.recovered.pendingRemains", record.id, reconciled.pending, reconciled.durationSec)
            return true
        }
        if (jobs.isEmpty()) {
            ready(record.id)
            core.deps.logger.log(
                Logger.Level.INFO,
                "rec.recovered.ready",
                mapOf("recordingId" to record.id),
            )
            return true
        }
        return reconciled.registered > 0
    }

    /**
     * Nothing is enqueued here, on either device. A recovered recording is finalized and nobody is
     * going to be asked to name it, so it goes to the shell as ready — which on the phone means a
     * job and on the watch means the transfer queue (docs/11 W4, "주의": the watch runs no workflow
     * and a job minted there would sit `PENDING` for the life of the install).
     */
    private suspend fun ready(recordingId: String) = host.onRecordingReady(recordingId, enqueue = true)

    private fun log(level: Logger.Level, event: String, recordingId: String, parts: Int, durationSec: Double) {
        core.deps.logger.log(
            level,
            event,
            mapOf("recordingId" to recordingId, "parts" to parts, "durationSec" to durationSec),
        )
    }

    private companion object {
        /** Deep enough that a device left offline for weeks still gets everything reconciled. */
        const val LIMIT = 200

        val mutex = Mutex()
    }
}
