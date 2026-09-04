package app.recly.android.ui

import recly.core.ReclyCore
import recly.core.recording.RecordingRecord

/**
 * docs/03 "보관 · 삭제": what the delete dialog and the disconnect warning have to say *first* — how
 * much audio exists only on this phone.
 *
 * ADR-017's seven-day window is why the disk cannot answer this on its own any more: a part stays
 * here for a week *after* its upload, so "the file is still there" no longer means Drive has not got
 * it. Whether Drive has it is the core's own answer ([ReclyCore.uploaded]) and the disk is only
 * asked what is left here to lose. The PC and RecKit read it the same way.
 */
object Retention {

    /**
     * How many of one recording's parts are still only here — the count the delete dialog leads
     * with, because that is the part of the deletion nothing anywhere else can give back.
     */
    suspend fun unuploadedParts(core: ReclyCore, recordingId: String): Int {
        val record = core.recordings.get(recordingId) ?: return 0
        return onlyHere(core.uploaded(recordingId), onDisk(core, record))
    }

    /**
     * How many recordings still have audio only on this phone, over the newest [limit] of them —
     * the count the disconnect warning names, because those are the ones that stay behind.
     */
    suspend fun unuploadedRecordings(core: ReclyCore, limit: Int = SCAN): Int {
        // Asked once for the whole list rather than per row, which is what the bulk call is for.
        val uploaded = core.uploadedRecordings()
        return core.recordings.list(limit).count { onlyHere(it.id in uploaded, onDisk(core, it)) > 0 }
    }

    /**
     * The rule itself, pure so it can be pinned down without a database: a recording Drive holds
     * every part of has nothing that exists only here, however many of its files are still on this
     * phone — the seven-day cache window leaves them lying there long after the upload.
     */
    fun onlyHere(uploaded: Boolean, partsOnDisk: Int): Int = if (uploaded) 0 else partsOnDisk

    private fun onDisk(core: ReclyCore, record: RecordingRecord): Int =
        record.meta.parts.count { core.deps.fileSystem.exists(record.dir / it.file) }

    /** Deep enough for months of daily recordings, shallow enough to join in one pass. */
    const val SCAN: Int = 100
}
