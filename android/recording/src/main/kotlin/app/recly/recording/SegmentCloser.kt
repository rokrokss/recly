package app.recly.recording

import okio.FileSystem
import okio.Path
import recly.core.model.Part
import recly.core.recording.PartHasher

/**
 * Turns the segment files the encoder left on disk into registered [Part]s. One place, because the
 * three callers must agree: a segment boundary closes one file, a stop drains everything the
 * encoder may have opened, and a recovery after a crash finds whatever survived.
 *
 * `setNextOutputFile` creates the file it is armed with, so there is always one more file than
 * there are segments — an empty one is not a part, it is the arming, and it goes.
 */
internal class SegmentCloser(
    private val fs: FileSystem,
    private val dir: Path,
    private val ledger: SegmentLedger,
    private val bytesPerSec: Int,
) {
    /**
     * Closes parts up to and including [lastPart], stopping at the first file with nothing in it.
     * [hintSec] is the wall-clock length of the first one, for a container that cannot be read.
     */
    suspend fun drain(lastPart: Int, hintSec: Double? = null, register: suspend (Part) -> Unit): Int {
        var closed = 0
        var hint = hintSec
        while (ledger.openPart <= lastPart) {
            if (!closeOne(hint, register)) break
            hint = null
            closed++
        }
        return closed
    }

    /** False when the open file holds nothing — it is deleted and there is nothing after it. */
    private suspend fun closeOne(hintSec: Double?, register: suspend (Part) -> Unit): Boolean {
        val path = dir / ledger.openFileName()
        val bytes = fs.metadataOrNull(path)?.size ?: 0L
        if (bytes <= 0L) {
            fs.delete(path, mustExist = false)
            return false
        }
        val durationSec = MediaDuration.seconds(path)
            ?: hintSec?.takeIf { it > 0 }
            ?: (bytes.toDouble() / bytesPerSec)
        register(ledger.close(bytes, PartHasher.sha256(fs, path), durationSec))
        return true
    }
}
