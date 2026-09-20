package app.recly.recording

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only microphone sources are candidates. Playback, telephony and output-only routes never are. */
internal data class MicrophoneRoute(val id: Int, val kind: Kind, val name: String) {
    enum class Kind(val priority: Int) { WIRED(0), BLUETOOTH(1), BUILT_IN(2), OTHER(3) }
}

/** Platform-free preference and verification rules. A requested input is not proof of routing. */
internal class MicrophoneRouteState {
    private val rejected = mutableSetOf<Int>()
    private var initialized = false
    private var requested: Int? = null
    private var requestedAt = 0L
    private var missingSince: Long? = null
    private var resetMissingRoute = false
    private var reportedMissing = false

    data class Result(val requested: Int?, val actual: MicrophoneRoute?, val rejected: Boolean, val unavailable: Boolean)

    fun devicesRemoved(ids: Set<Int>) { rejected.removeAll(ids) }

    fun update(
        inputs: List<MicrophoneRoute>, actual: MicrophoneRoute?, nowMs: Long, silenced: Boolean,
        prefer: (Int?) -> Boolean,
    ): Result {
        rejected.retainAll(inputs.map { it.id }.toSet())
        // A route the OS has actually activated is usable even if an earlier preference timed out.
        actual?.let { rejected.remove(it.id) }
        val eligible = inputs.filter {
            it.kind in setOf(MicrophoneRoute.Kind.WIRED, MicrophoneRoute.Kind.BLUETOOTH) && it.id !in rejected
        }
        val priority = eligible.minOfOrNull { it.kind.priority }
        val best = eligible.filter { it.kind.priority == priority }
        val chosen = best.firstOrNull { it.id == actual?.id } ?: best.minByOrNull { it.id }
        var refused = false
        if (!initialized || requested != chosen?.id) {
            initialized = true
            requested = chosen?.id
            requestedAt = nowMs
            if (!prefer(requested)) {
                requested?.let { rejected.add(it) }
                requested = null
                prefer(null)
                refused = true
            }
        }
        if (requested != null && requested != actual?.id && nowMs - requestedAt >= 5_000) {
            rejected.add(requested!!)
            requested = null
            prefer(null)
            refused = true
        }
        if (actual != null || silenced) {
            missingSince = null
            resetMissingRoute = false
            reportedMissing = false
        } else {
            val since = missingSince ?: nowMs.also { missingSince = it }
            if (!resetMissingRoute && nowMs - since >= 5_000) {
                prefer(null)
                resetMissingRoute = true
            }
            if (!reportedMissing && nowMs - since >= 10_000) {
                reportedMissing = true
                return Result(requested, actual, refused, unavailable = true)
            }
        }
        return Result(requested, actual, refused, unavailable = false)
    }
}

/** App-local input routing, shared by phone and Wear. No communication/output route is acquired.
 * Every refresh and stop is dispatched under SegmentedRecorder's mutex, including delayed events.
 */
internal class MicrophoneRouting(
    private val manager: AudioManager,
    private val recorder: MediaRecorder,
    private val scope: CoroutineScope,
    private val dispatch: (() -> Unit) -> Unit,
    private val event: (String, Map<String, Any>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val state = MicrophoneRouteState()
    @Volatile private var active = false
    private var devicesRegistered = false
    private var routingRegistered = false
    private var poll: Job? = null
    private var lastActual: Int? = null
    private var lastRate = 0
    private val eventLock = Any()
    private var refreshScheduled = false
    private val removedIds = mutableSetOf<Int>()
    private val refreshEvent = Runnable {
        val removed = synchronized(eventLock) {
            refreshScheduled = false
            removedIds.toSet().also { removedIds.clear() }
        }
        dispatch { if (active) { state.devicesRemoved(removed); refresh() } }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleRefresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            scheduleRefresh(removedDevices.map { it.id }.toSet())
        }
    }
    private val routing = AudioRouting.OnRoutingChangedListener { scheduleRefresh() }

    /** Before prepare/start, request a current external input. null preserves the OS default. */
    fun prepare() {
        val inputs = inputs()
        state.update(inputs, actual = null, nowMs = SystemClock.elapsedRealtime(), silenced = true, prefer = ::prefer)
    }

    fun start() {
        active = true
        manager.registerAudioDeviceCallback(devices, handler)
        devicesRegistered = true
        recorder.addOnRoutingChangedListener(routing, handler)
        routingRegistered = true
        refresh()
        poll = scope.launch {
            while (true) {
                delay(1_000)
                dispatch { if (active) refresh() }
            }
        }
    }

    fun stop() {
        synchronized(eventLock) {
            active = false
            handler.removeCallbacks(refreshEvent)
            refreshScheduled = false
            removedIds.clear()
        }
        poll?.cancel()
        poll = null
        if (devicesRegistered) runCatching { manager.unregisterAudioDeviceCallback(devices) }
        if (routingRegistered) runCatching { recorder.removeOnRoutingChangedListener(routing) }
        devicesRegistered = false
        routingRegistered = false
    }

    private fun scheduleRefresh(removed: Set<Int> = emptySet()) {
        // Device-list and route updates arrive separately. One bounded refresh must not flood
        // the recorder mutex and delay preparing the next output segment.
        synchronized(eventLock) {
            if (!active) return
            removedIds.addAll(removed)
            if (refreshScheduled) return
            refreshScheduled = true
            handler.postDelayed(refreshEvent, 250)
        }
    }

    private fun refresh() {
        val actual = runCatching { recorder.routedDevice?.asMicrophone() }.getOrNull()
        val configuration = runCatching { recorder.activeRecordingConfiguration }.getOrNull()
        val result = state.update(inputs(), actual, SystemClock.elapsedRealtime(),
            configuration?.isClientSilenced == true, ::prefer)
        val rate = configuration?.format?.sampleRate ?: 0
        if (actual?.id != lastActual || rate != lastRate) {
            lastActual = actual?.id
            lastRate = rate
            event("rec.input.route", mapOf("device" to (actual?.name ?: "unavailable"),
                "deviceId" to (actual?.id ?: -1), "sampleRateHz" to rate))
        }
        if (result.rejected) event("rec.input.preferenceRejected", mapOf("actualDeviceId" to (actual?.id ?: -1)))
        // Some vendors do not publish a route during silencing. A missing route is diagnostic,
        // not proof the encoder failed: the recorder's own error callback owns fatal failures.
        if (result.unavailable) event("rec.input.unavailable", emptyMap())
    }

    private fun inputs(): List<MicrophoneRoute> = runCatching {
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { it.asMicrophone() }
    }.getOrDefault(emptyList())

    private fun prefer(id: Int?): Boolean = runCatching {
        val device = id?.let { desired ->
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == desired }
                ?: return false
        }
        recorder.setPreferredDevice(device)
    }.getOrDefault(false)

    private fun AudioDeviceInfo.asMicrophone(): MicrophoneRoute? {
        if (!isSource) return null
        val kind = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> MicrophoneRoute.Kind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> MicrophoneRoute.Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicrophoneRoute.Kind.BUILT_IN
            // Retain an actual vendor-specific source as evidence of routing, but never select
            // unknown, playback or telephony devices as a microphone preference.
            else -> MicrophoneRoute.Kind.OTHER
        }
        return MicrophoneRoute(id, kind, productName.toString())
    }
}
