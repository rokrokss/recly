package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import recly.core.ReclyCore
import recly.core.recording.RecordingRecord

/**
 * docs/03 "보관 · 삭제": what the delete dialog and the disconnect warning have to say *first* — how
 * much audio exists only on this PC.
 *
 * ADR-017's seven-day window is why the disk cannot answer this on its own any more: a part stays
 * here for a week *after* its upload, so "the file is still there" no longer means Drive has not got
 * it. Whether Drive has it is the core's own answer ([ReclyCore.uploaded]) and the disk is only
 * asked what is left here to lose. The phone and RecKit read it the same way.
 */
object Retention {

    /**
     * How many of one recording's parts are still only here — the count the delete dialog leads
     * with, because that is the part of the deletion nothing anywhere else can give back.
     */
    suspend fun unuploadedParts(core: ReclyCore, recordingId: String): Int {
        val record = runCatching { core.recordings.get(recordingId) }.getOrNull() ?: return 0
        // A question the core could not answer is not a promise that Drive has it: the warning the
        // user can still act on is the one that names the files.
        val uploaded = runCatching { core.uploaded(recordingId) }.getOrDefault(false)
        return onlyHere(uploaded, onDisk(core, record))
    }

    /**
     * How many recordings still have audio only here, over the newest [limit] of them — the count
     * the disconnect warning names, because those are the ones that stay behind.
     */
    suspend fun unuploadedRecordings(core: ReclyCore, limit: Int = SCAN): Int {
        // Asked once for the whole list rather than per row, which is what the bulk call is for.
        val uploaded = runCatching { core.uploadedRecordings() }.getOrDefault(emptySet())
        return runCatching { core.recordings.list(limit) }.getOrDefault(emptyList())
            .count { onlyHere(it.id in uploaded, onDisk(core, it)) > 0 }
    }

    /**
     * The rule itself, pure so it can be pinned down without a database: a recording Drive holds
     * every part of has nothing that exists only here, however many of its files are still on this
     * PC — the seven-day cache window leaves them lying there long after the upload.
     */
    fun onlyHere(uploaded: Boolean, partsOnDisk: Int): Int = if (uploaded) 0 else partsOnDisk

    private fun onDisk(core: ReclyCore, record: RecordingRecord): Int =
        record.meta.parts.count { part ->
            runCatching { core.deps.fileSystem.exists(record.dir / part.file) }.getOrDefault(false)
        }

    /** Enough of the list to count what Drive has not got — the same depth the phone scans. */
    const val SCAN: Int = 100
}

/**
 * docs/03 "앱에서 지우기": what the delete dialog has to know before it can ask. [unuploaded] is how
 * many parts are still only on this PC, which the dialog says first — that is the part of the
 * deletion nothing anywhere else can give back.
 */
data class DeleteRequest(
    val recordingId: String,
    /** A name rather than a sentence: the dialog is open while the language can change under it. */
    val title: UiMessage,
    val unuploaded: Int,
    /**
     * docs/03: another device recorded it and this PC only read it out of Drive, so there is no
     * "leave it in Drive" half to keep — the dialog says what the deletion costs instead of asking.
     */
    val remote: Boolean = false,
)

/**
 * docs/03 "로그아웃 vs 연결 해제": the warning is not a yes/no, it is a few facts and a separate
 * question. [unuploaded] is one of them — how many recordings have never reached Drive and would be
 * left on this PC (principle 3: an original is not deleted by a decision about an account).
 * [recording] is what the dialog cannot let past, see [canConfirm].
 */
data class DisconnectPrompt(
    val unuploaded: Int,
    val recording: Boolean = false,
) {

    /**
     * Whether the disconnect may go ahead.
     *
     * A capture that is running has no job yet — the job is made at the stop — so the core's own
     * `Busy` guard, which looks at the queue, does not cover it: "also delete the recordings" would
     * take the directory out from under the recorder that is still writing into it. And a disconnect
     * that stopped the recording for the user would be answering a question nobody asked, so the
     * answer is to say what is in the way and let them stop it themselves.
     */
    val canConfirm: Boolean get() = !recording

    /** The line that says what is in the way, or null when nothing is. */
    val blocker: Str? get() = if (recording) Str.DISCONNECT_STOP_RECORDING else null

    /**
     * True when this freshly read state carries a warning the dialog the user confirmed never
     * showed — a recording that finished since [shown] was built and grew the count. The confirm
     * re-presents with this instead of acting on a promise the dialog did not make. [recording] is
     * not compared: it has its own live guards ([canConfirm] on the dialog, and
     * [DisconnectGuard.liveBlocker] at run time).
     */
    fun warnsMore(shown: DisconnectPrompt): Boolean = unuploaded > shown.unuploaded
}
