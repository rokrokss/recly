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
import recly.core.platform.Transport
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalModelStore
import recly.core.transcribe.LocalTranscriptionEngine
import recly.core.transcribe.LocalTranscriptionProgress
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.LocalTranscriptionResult
import recly.core.transcribe.Qwen3Asr
import recly.core.transcribe.SpeakerDiarizationModels
import recly.core.transcribe.SpeakerTurn
import recly.core.transcribe.SpeakerTurns
import recly.core.transcribe.SttSegment
import recly.core.transcribe.UnavailableLocalTranscriptionEngine

/**
 * docs/05 "Fixed processing settings" on Windows: sherpa-onnx runs Qwen3-ASR on two CPU threads, in this process — the
 * core stops it when a capture starts (`LocalTranscriptionService.captureStarted`), and the capture
 * itself is the helper's. Silero VAD cuts the recording into speech; each piece is decoded and
 * checkpointed, which is also where a cancel and the resume position take effect. Windows has no
 * thermal signal to wait on, and it is not validated for heat on a real PC yet (docs/20).
 *
 * With the speaker models ([speakers]) the recording is diarized first and each piece of speech is cut
 * where the speaker changes, so every decoded piece has one speaker; without them the transcript simply
 * has none. The user's vocabulary goes into each piece's prompt as Qwen3-ASR hotwords.
 */
class QwenSpeechEngine private constructor(
    private val store: LocalModelStore,
    private val speakers: LocalModelStore,
    private val natives: Natives,
) : LocalTranscriptionEngine {
    @Volatile private var cancelled = false

    /** Whether [prepare] is downloading right now — what settings and the card show a percentage for. */
    @Volatile private var downloading = false

    /** What the running [prepare] set out to fetch, so its percentage does not restart between the two. */
    @Volatile private var fetching: List<LocalModelStore>? = null

    override fun cancel() { cancelled = true }

    override suspend fun status(language: String): LocalEngineInfo = info(when {
        Qwen3Asr.hint(language) == null -> LocalEngineStatus.UNSUPPORTED
        !store.installed() -> LocalEngineStatus.MODEL_REQUIRED
        else -> LocalEngineStatus.READY
    })

    /**
     * The speech model, then the speaker models — one download, from the same hosts. Cancelling the caller
     * stops it between chunks; what is on disk stays for the resume. A speaker download that fails after
     * the speech model arrived does not hold back the recordings that waited for the speech model: they
     * transcribe without speakers, and the speaker row offers its own download again.
     */
    override suspend fun prepare(language: String): LocalEngineInfo {
        if (Qwen3Asr.hint(language) != null) {
            downloading = true
            fetching = missing()
            try {
                val speechWasHere = store.installed()
                store.install()
                try {
                    speakers.install()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (speechWasHere) throw e
                }
            } finally {
                downloading = false
                fetching = null
            }
        }
        return status(language)
    }

    private fun missing(): List<LocalModelStore> = listOf(store, speakers).filter { !it.installed() }.ifEmpty { listOf(store, speakers) }

    override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult =
        withContext(Dispatchers.Default) {
            val hint = Qwen3Asr.hint(request.language)
            if (hint == null || status(request.language).status != LocalEngineStatus.READY) return@withContext paused
            cancelled = false
            natives.load()
            // The whole input from 0 s even on a resume: the turns have to be on the checkpoints' axis.
            val turns = if (request.diarize && speakers.installed()) {
                SpeakerDiarizer(speakers).turns(request.path, request.expectedSpeakers) { cancelled } ?: return@withContext paused
            } else {
                emptyList()
            }
            val labels = SpeakerLabels(turns)
            // Commas separate hotwords, so one inside a term would split it in two.
            val hotwords = request.vocabulary.map { it.replace(',', ' ').trim() }.filter { it.isNotEmpty() }.joinToString(",")
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
                            if (!speech(recognizer, hint, hotwords, segment.samples, start, turns, labels, progress)) return@withContext paused
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

    /** A speech segment cut where the speaker changes ([SpeakerTurns.split]); false once cancelled. */
    private suspend fun speech(
        recognizer: OfflineRecognizer, hint: String, hotwords: String, samples: FloatArray, start: Double,
        turns: List<SpeakerTurn>, labels: SpeakerLabels, progress: LocalTranscriptionProgress,
    ): Boolean {
        if (turns.isEmpty()) return decode(recognizer, hint, hotwords, samples, start, null, progress)
        for (piece in SpeakerTurns.split(start, start + samples.size.toDouble() / SAMPLE_RATE, turns)) {
            val from = ((piece.start - start) * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val to = ((piece.end - start) * SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            val speaker = labels.of(piece.label)
            if (!decode(recognizer, hint, hotwords, samples.copyOfRange(from, to), start + from.toDouble() / SAMPLE_RATE, speaker, progress)) return false
        }
        return true
    }

    /** One speaker's speech in pieces the model's context holds; false once cancelled. */
    private suspend fun decode(
        recognizer: OfflineRecognizer, hint: String, hotwords: String, samples: FloatArray, start: Double, speaker: String?,
        progress: LocalTranscriptionProgress,
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
                // Never `createStream(hotwords)`: on Qwen3-ASR that one exits the process.
                if (hotwords.isNotEmpty()) stream.setOption("hotwords", hotwords)
                stream.acceptWaveform(piece, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            val pieceStart = start + from.toDouble() / SAMPLE_RATE
            val pieceEnd = pieceStart + piece.size.toDouble() / SAMPLE_RATE
            if (text.isNotEmpty()) progress.checkpoint(SttSegment(pieceStart, pieceEnd, speaker, text, null), pieceEnd)
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

    /**
     * The size and the share already on disk come from the stores, so a restart still knows them — of what
     * [prepare] still has to fetch: both models on a fresh PC, the speaker models alone once speech is here.
     * The revision names the speaker models once they are here, so a checkpoint made without speakers is
     * not resumed into a transcript that has them.
     */
    private fun info(status: LocalEngineStatus): LocalEngineInfo {
        val separates = speakers.installed()
        val missing = fetching ?: missing()
        val bytes = missing.sumOf { it.totalBytes }
        val present = missing.sumOf { it.progress() * it.totalBytes }
        return LocalEngineInfo(
            status = status,
            name = Qwen3Asr.NAME,
            revision = if (separates) "${Qwen3Asr.REVISION}+${SpeakerDiarizationModels.DIRECTORY}" else Qwen3Asr.REVISION,
            supportsDiarization = separates,
            supportsVocabulary = true,
            modelBytes = bytes,
            progress = (present / bytes).takeIf { it > 0.0 && it < 1.0 },
            downloading = downloading,
        )
    }

    /**
     * The native half, copied out of its jar once into [dir]. sherpa-onnx's own loader would copy it
     * to a new temporary folder on every launch, and Windows cannot delete a DLL that is loaded.
     */
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
                // A parent of its own: a store removes every other folder beside its own once it is whole.
                LocalModelStore(transport, fileSystem, dataDir / "speaker-models" / SpeakerDiarizationModels.DIRECTORY,
                    SpeakerDiarizationModels.files, io),
                Natives(fileSystem, dataDir / "native" / Qwen3Asr.REVISION) { name ->
                    QwenSpeechEngine::class.java.classLoader.getResourceAsStream("$resources/$name")
                },
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
