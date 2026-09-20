package app.recly.recording

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import recly.core.model.Range

/**
 * The `silenced` ranges of docs/03: stretches where the platform kept the session alive but fed it
 * zeroes (`isClientSilenced` — a privacy toggle, a higher-priority capture, a call). They are not
 * gaps: the audio is there, it is just silent, and the meta has to say which part is which.
 *
 * The transitions arrive on a system callback and the ranges are written into the meta once, at
 * stop. Only [onSilenced] carries logic, so the merging is testable without a device.
 */
class SilenceMonitor(private val elapsedSec: () -> Double) {

    private val ranges = mutableListOf<Range>()
    private var openStartSec: Double? = null
    private var silenced = false
    private var registered: AudioManager.AudioRecordingCallback? = null

    /**
     * Since Android 10 an ordinary app only ever sees its own configurations here, and
     * `MediaRecorder` exposes no session id to filter on, so every reported config is ours.
     */
    @Synchronized
    fun start(audioManager: AudioManager) {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                synchronized(this@SilenceMonitor) {
                    if (registered !== this) return
                    onSilenced(configs.any { it.isClientSilenced }, elapsedSec())
                }
            }
        }
        registered = callback
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** Unregisters, closes a range still open, and returns everything for the meta. */
    @Synchronized
    fun stop(audioManager: AudioManager): List<Range> {
        val callback = registered
        registered = null
        callback?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
        onSilenced(false, elapsedSec())
        return ranges.toList()
    }

    /**
     * Only transitions count: the callback fires on every routing change, and a run of "still
     * silenced" must stay one range. A range that resumes where the previous one ended (a flap the
     * callback reported twice) is merged into it rather than appended.
     */
    @Synchronized
    internal fun onSilenced(nowSilenced: Boolean, atSec: Double) {
        if (nowSilenced == silenced) return
        silenced = nowSilenced
        if (nowSilenced) {
            openStartSec = atSec
            return
        }
        val startSec = openStartSec ?: return
        openStartSec = null
        if (atSec <= startSec) return
        val previous = ranges.lastOrNull()
        if (previous != null && startSec <= previous.endSec) {
            ranges[ranges.lastIndex] = previous.copy(endSec = atSec)
        } else {
            ranges += Range(startSec = startSec, endSec = atSec, reason = REASON)
        }
    }

    @Synchronized
    internal fun ranges(): List<Range> = ranges.toList()

    private companion object {
        /** docs/03's example reason for a lost microphone. */
        const val REASON = "mic_taken"
    }
}
