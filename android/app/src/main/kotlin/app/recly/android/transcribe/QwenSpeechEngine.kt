package app.recly.android.transcribe

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import recly.core.platform.Transport
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalModelStore
import recly.core.transcribe.LocalTranscriptionEngine
import recly.core.transcribe.LocalTranscriptionProgress
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.LocalTranscriptionResult
import recly.core.transcribe.Qwen3Asr
import recly.core.transcribe.SttSegment
import recly.core.transcribe.UnavailableLocalTranscriptionEngine

/**
 * docs/05 "고정 처리 설정 도입" on the phone: sherpa-onnx runs Qwen3-ASR on two CPU threads. Silero VAD cuts the
 * recording into speech; each piece is decoded and checkpointed, which is also where a thermal or
 * Battery Saver pause, a cancel and the resume position take effect. It is not validated for heat
 * on a real device yet (docs/20).
 */
class QwenSpeechEngine private constructor(
    private val power: PowerManager,
    private val store: LocalModelStore,
) : LocalTranscriptionEngine {
    @Volatile private var cancelled = false
    /** Downloads in flight: `prepare` is the download, and `status` says whether one is running. */
    private val downloads = AtomicInteger()

    override fun cancel() { cancelled = true }

    override suspend fun status(language: String): LocalEngineInfo = info(when {
        Qwen3Asr.hint(language) == null -> LocalEngineStatus.UNSUPPORTED
        !store.installed() -> LocalEngineStatus.MODEL_REQUIRED
        !admitted() -> LocalEngineStatus.WAITING
        else -> LocalEngineStatus.READY
    })

    override suspend fun prepare(language: String): LocalEngineInfo {
        if (Qwen3Asr.hint(language) != null) {
            downloads.incrementAndGet()
            try {
                store.install()
            } finally {
                downloads.decrementAndGet()
            }
        }
        return status(language)
    }

    override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult =
        withContext(Dispatchers.Default) {
            val hint = Qwen3Asr.hint(request.language)
            if (hint == null || status(request.language).status != LocalEngineStatus.READY) return@withContext paused
            cancelled = false
            val recognizer = OfflineRecognizer(config = recognizerConfig())
            val vad = Vad(config = vadConfig())
            try {
                PcmDecoder(request.path, request.startTimeSec).use { pcm ->
                    // One window per call: the VAD decides speech or silence once per call, so a longer
                    // one moves the start of speech to its end and hides every pause inside it.
                    var pending = FloatArray(0)
                    while (true) {
                        val chunk = pcm.read()
                        if (chunk == null) vad.flush() else {
                            val samples = pending + chunk
                            var at = 0
                            while (samples.size - at >= VAD_WINDOW) {
                                vad.acceptWaveform(samples.copyOfRange(at, at + VAD_WINDOW))
                                at += VAD_WINDOW
                            }
                            pending = samples.copyOfRange(at, samples.size)
                        }
                        while (!vad.empty()) {
                            val segment = vad.front()
                            vad.pop()
                            val start = request.startTimeSec + segment.start.toDouble() / SAMPLE_RATE
                            if (!decode(recognizer, hint, segment.samples, start, progress)) return@withContext paused
                        }
                        if (chunk == null) break
                    }
                }
            } finally {
                vad.release()
                recognizer.release()
            }
            LocalTranscriptionResult(emptyList(), completed = true)
        }

    /** A speech segment in pieces the model's context holds; false once admission is withdrawn. */
    private suspend fun decode(
        recognizer: OfflineRecognizer, hint: String, samples: FloatArray, start: Double, progress: LocalTranscriptionProgress,
    ): Boolean {
        if (samples.isEmpty()) return true
        val pieces = ceil(samples.size.toDouble() / MAX_PIECE_SAMPLES).toInt()
        val size = ceil(samples.size.toDouble() / pieces).toInt()
        for (from in samples.indices step size) {
            currentCoroutineContext().ensureActive()
            if (cancelled || !admitted()) return false
            val piece = samples.copyOfRange(from, minOf(from + size, samples.size))
            val stream = recognizer.createStream()
            val text = try {
                if (hint.isNotEmpty()) stream.setOption("language", hint)
                stream.acceptWaveform(piece, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            val pieceStart = start + from.toDouble() / SAMPLE_RATE
            val pieceEnd = pieceStart + piece.size.toDouble() / SAMPLE_RATE
            if (text.isNotEmpty()) progress.checkpoint(SttSegment(pieceStart, pieceEnd, null, text, null), pieceEnd)
        }
        return true
    }

    private fun admitted(): Boolean =
        power.currentThermalStatus < PowerManager.THERMAL_STATUS_LIGHT && !power.isPowerSaveMode

    private fun recognizerConfig() = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            qwen3Asr = OfflineQwen3AsrModelConfig(
                convFrontend = store.path("conv_frontend.onnx").toString(),
                encoder = store.path("encoder.int8.onnx").toString(),
                decoder = store.path("decoder.int8.onnx").toString(),
                tokenizer = store.path(Qwen3Asr.TOKENIZER).toString(),
                maxNewTokens = MAX_NEW_TOKENS,
            ),
            numThreads = THREADS,
        ),
    )

    private fun vadConfig() = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = store.path(Qwen3Asr.VAD).toString(),
            minSilenceDuration = MIN_SILENCE_SEC,
            windowSize = VAD_WINDOW,
            maxSpeechDuration = MAX_SPEECH_SEC,
        ),
    )

    /** The size, and how much of it is on disk while it is partly there — read from the disk, so it survives a restart. */
    private fun info(status: LocalEngineStatus) = LocalEngineInfo(
        status, Qwen3Asr.NAME, Qwen3Asr.REVISION,
        modelBytes = store.totalBytes,
        progress = store.progress().takeIf { it > 0.0 && it < 1.0 },
        downloading = downloads.get() > 0,
    )

    companion object {
        private val paused = LocalTranscriptionResult(emptyList(), completed = false)
        private const val THREADS = 2
        private const val MAX_NEW_TOKENS = 256
        private const val MIN_SILENCE_SEC = 0.5f
        private const val VAD_WINDOW = 512
        /** Where the VAD starts looking harder for a pause; it is not a cut. */
        private const val MAX_SPEECH_SEC = 15f
        /** The cut: past the context sherpa-onnx returns empty text rather than an error. */
        private const val MAX_PIECE_SAMPLES = 20 * SAMPLE_RATE
        /** 8 GB phones report a little over 7 GiB, 6 GB ones about 5.5. */
        private const val MIN_MEMORY_BYTES = 6L shl 30

        /**
         * The placeholder on a phone without the memory for the model, or in a 32-bit process, whose
         * ABIs the APK leaves sherpa-onnx out of (build.gradle.kts `packaging`): `local` is then never offered.
         */
        fun make(context: Context, transport: Transport): LocalTranscriptionEngine {
            if (!Process.is64Bit()) return UnavailableLocalTranscriptionEngine()
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            if (memory.totalMem < MIN_MEMORY_BYTES) return UnavailableLocalTranscriptionEngine()
            val dir = context.noBackupFilesDir.absolutePath.toPath() / "models" / Qwen3Asr.DIRECTORY
            return QwenSpeechEngine(
                context.getSystemService(PowerManager::class.java),
                LocalModelStore(transport, FileSystem.SYSTEM, dir, Qwen3Asr.files, Dispatchers.IO),
            )
        }
    }
}
