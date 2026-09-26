package app.recly.windows.transcribe

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.source
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
 * docs/05 "고정 처리 설정 도입" on Windows: sherpa-onnx runs Qwen3-ASR on two CPU threads, in this process — the
 * core stops it when a capture starts (`LocalTranscriptionService.captureStarted`), and the capture
 * itself is the helper's. Silero VAD cuts the recording into speech; each piece is decoded and
 * checkpointed, which is also where a cancel and the resume position take effect. Windows has no
 * thermal signal to wait on, and it is not validated for heat on a real PC yet (docs/20).
 */
class QwenSpeechEngine private constructor(
    private val store: LocalModelStore,
    private val natives: Natives,
) : LocalTranscriptionEngine {
    @Volatile private var cancelled = false

    override fun cancel() { cancelled = true }

    override suspend fun status(language: String): LocalEngineInfo = info(when {
        Qwen3Asr.hint(language) == null -> LocalEngineStatus.UNSUPPORTED
        !store.installed() -> LocalEngineStatus.MODEL_REQUIRED
        else -> LocalEngineStatus.READY
    })

    override suspend fun prepare(language: String): LocalEngineInfo {
        if (Qwen3Asr.hint(language) != null) store.install()
        return status(language)
    }

    override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult =
        withContext(Dispatchers.Default) {
            val hint = Qwen3Asr.hint(request.language)
            if (hint == null || status(request.language).status != LocalEngineStatus.READY) return@withContext paused
            cancelled = false
            natives.load()
            val recognizer = OfflineRecognizer(recognizerConfig())
            val vad = Vad(vadConfig())
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

    /** A speech segment in pieces the model's context holds; false once cancelled. */
    private suspend fun decode(
        recognizer: OfflineRecognizer, hint: String, samples: FloatArray, start: Double, progress: LocalTranscriptionProgress,
    ): Boolean {
        if (samples.isEmpty()) return true
        val pieces = ceil(samples.size.toDouble() / MAX_PIECE_SAMPLES).toInt()
        val size = ceil(samples.size.toDouble() / pieces).toInt()
        for (from in samples.indices step size) {
            currentCoroutineContext().ensureActive()
            if (cancelled) return false
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

    private fun recognizerConfig() = OfflineRecognizerConfig.builder()
        .setOfflineModelConfig(
            OfflineModelConfig.builder()
                .setQwen3Asr(
                    OfflineQwen3AsrModelConfig.builder()
                        .setConvFrontend(store.path("conv_frontend.onnx").toString())
                        .setEncoder(store.path("encoder.int8.onnx").toString())
                        .setDecoder(store.path("decoder.int8.onnx").toString())
                        .setTokenizer(store.path(Qwen3Asr.TOKENIZER).toString())
                        .setMaxNewTokens(MAX_NEW_TOKENS)
                        .build(),
                )
                .setNumThreads(THREADS)
                .setDebug(false)
                .build(),
        )
        .build()

    /** Every field set: the Java builders' defaults differ from the Kotlin ones the phone uses. */
    private fun vadConfig() = VadModelConfig.builder()
        .setSileroVadModelConfig(
            SileroVadModelConfig.builder()
                .setModel(store.path(Qwen3Asr.VAD).toString())
                .setThreshold(0.5f)
                .setMinSilenceDuration(MIN_SILENCE_SEC)
                .setMinSpeechDuration(0.25f)
                .setWindowSize(VAD_WINDOW)
                .setMaxSpeechDuration(MAX_SPEECH_SEC)
                .build(),
        )
        .setSampleRate(SAMPLE_RATE)
        .setNumThreads(1)
        .setDebug(false)
        .build()

    private fun info(status: LocalEngineStatus) = LocalEngineInfo(status, Qwen3Asr.NAME, Qwen3Asr.REVISION)

    /**
     * The native half, copied out of its jar once into [dir]. sherpa-onnx's own loader would copy it
     * to a new temporary folder on every launch, and Windows cannot delete a DLL that is loaded.
     */
    private class Natives(private val fileSystem: FileSystem, private val dir: Path, private val resources: String) {
        private val names = listOf(System.mapLibraryName("onnxruntime"), System.mapLibraryName("sherpa-onnx-jni"))

        fun load() {
            fileSystem.createDirectories(dir)
            for (name in names) {
                if (fileSystem.exists(dir / name)) continue
                val temp = dir / "$name.tmp"
                val input = javaClass.classLoader.getResourceAsStream("$resources/$name") ?: error("sherpa-onnx has no '$name' here")
                input.source().use { source -> fileSystem.write(temp) { writeAll(source) } }
                fileSystem.atomicMove(temp, dir / name)
            }
            System.setProperty("sherpa_onnx.native.path", dir.toString())
        }
    }

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
        /** 8 GB PCs report a little under 8 GiB, 6 GB ones about 5.9. */
        private const val MIN_MEMORY_BYTES = 6L shl 30

        /**
         * The placeholder on a PC without the memory for the model, or one this build carries no
         * native library for (sherpa-onnx's `win-x64` is the one the MSI ships): `local` is then
         * never offered.
         */
        fun make(dataDir: Path, transport: Transport, fileSystem: FileSystem, io: CoroutineDispatcher): LocalTranscriptionEngine {
            val memory = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
            val resources = "sherpa-onnx/native/${osArch()}"
            val bundled = QwenSpeechEngine::class.java.classLoader.getResource("$resources/${System.mapLibraryName("sherpa-onnx-jni")}") != null
            if (memory < MIN_MEMORY_BYTES || !bundled) return UnavailableLocalTranscriptionEngine()
            return QwenSpeechEngine(
                LocalModelStore(transport, fileSystem, dataDir / "models" / Qwen3Asr.DIRECTORY, Qwen3Asr.files, io),
                Natives(fileSystem, dataDir / "native" / Qwen3Asr.REVISION, resources),
            )
        }

        /** sherpa-onnx's folder name for this machine inside its native jar. */
        private fun osArch(): String {
            val os = System.getProperty("os.name").lowercase()
            val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
            return when {
                "win" in os -> if (arm) "win-arm64" else "win-x64"
                "mac" in os -> if (arm) "osx-aarch64" else "osx-x64"
                else -> if (arm) "linux-aarch64" else "linux-x64"
            }
        }
    }
}
