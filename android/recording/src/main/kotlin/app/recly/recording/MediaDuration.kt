package app.recly.recording

import android.media.MediaMetadataRetriever
import okio.Path

/**
 * How long a segment file is, according to the file itself. An interface only so a JVM test can
 * decide which containers are readable — the device is always [MediaDuration].
 */
internal fun interface DurationProbe {
    /**
     * `null` when the file cannot be read: a tail the encoder never finished, or a JVM without the
     * platform behind it.
     */
    fun seconds(file: Path): Double?
}

/** The container's own duration — the only honest length of a segment that is already closed. */
internal object MediaDuration : DurationProbe {
    override fun seconds(file: Path): Double? = runCatching {
        MediaMetadataRetriever().use {
            it.setDataSource(file.toFile().absolutePath)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()?.takeIf { it > 0 }?.let { it / 1000.0 }
}
