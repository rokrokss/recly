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
import java.io.File
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
import recly.core.transcribe.SpeakerDiarizationModels
import recly.core.transcribe.SpeakerTurn
import recly.core.transcribe.SpeakerTurns
import recly.core.transcribe.SttSegment
import recly.core.transcribe.UnavailableLocalTranscriptionEngine

/**
 * docs/05 "Fixed processing settings" on the phone: sherpa-onnx runs Qwen3-ASR on two CPU threads. Silero VAD cuts the
 * recording into speech; each piece is decoded and checkpointed, which is also where a thermal or
 * Battery Saver pause, a cancel and the resume position take effect. It is not validated for heat
 * on a real device yet (docs/20).
 *
 * With the speaker models ([speakers]) on the phone and speakers asked for, the recording is diarized
 * first ([SpeakerSeparation]) and each speech piece is cut where the speaker changes, so every piece
 * Qwen decodes is one person's — Qwen gives a piece's times only, so a change inside one sentence the
 * diarizer did not see stays in it (docs/09 "On-device speaker separation"). The vocabulary goes to Qwen
 * as its hotwords, which sherpa-onnx puts in the model's prompt.
 */
class QwenSpeechEngine private constructor(
    private val power: PowerManager,
    private val store: LocalModelStore,
    private val speakers: LocalModelStore,
    private val speakerDir: okio.Path,
    private val turnsDir: File,
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

    /** The speech model and the speaker models in one download: what a device without either fetches together. */
    override suspend fun prepare(language: String): LocalEngineInfo {
        if (Qwen3Asr.hint(language) != null) {
            downloads.incrementAndGet()
            try {
                store.install()
                speakers.install()
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
            // Diarized first, from 0 s on the same input; a missing speaker model never holds the transcript up.
            val separation = if (request.diarize && speakers.installed()) separation(request.path) else null
            val turns = separation?.let { it.turns(request.path, request.expectedSpeakers) { !cancelled && admitted() } ?: return@withContext paused }
            val hotwords = hotwords(request.vocabulary)
            val recognizer = OfflineRecognizer(config = recognizerConfig())
            val vad = Vad(config = vadConfig())
            val labels = Labels(turns.orEmpty())
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
            separation?.forget()
            LocalTranscriptionResult(emptyList(), completed = true)
        }

    /**
     * One speech segment from the VAD: cut where the speaker changes when there are [turns]
     * ([SpeakerTurns.split]), each piece decoded with its speaker's label.
     */
    private suspend fun speech(
        recognizer: OfflineRecognizer, hint: String, hotwords: String, samples: FloatArray, start: Double,
        turns: List<SpeakerTurn>?, labels: Labels, progress: LocalTranscriptionProgress,
    ): Boolean {
        if (turns == null) return decode(recognizer, hint, hotwords, samples, start, null, progress)
        val end = start + samples.size.toDouble() / SAMPLE_RATE
        for (piece in SpeakerTurns.split(start, end, turns)) {
            val from = ((piece.start - start) * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val to = ((piece.end - start) * SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            val label = labels.of(piece)
            if (!decode(recognizer, hint, hotwords, samples.copyOfRange(from, to), start + from.toDouble() / SAMPLE_RATE, label, progress)) return false
        }
        return true
    }

    /**
     * Every segment must carry a speaker or the core keeps none of them (docs/10): a piece no turn reached
     * takes the speaker before it, and the very first such piece the turn nearest to it.
     */
    private class Labels(private val turns: List<SpeakerTurn>) {
        private var last: String? = null

        fun of(piece: SpeakerTurn): String {
            val label = piece.label.ifEmpty {
                last ?: turns.minByOrNull { maxOf(it.start - piece.end, piece.start - it.end) }?.label.orEmpty()
            }
            if (label.isNotEmpty()) last = label
            return label
        }
    }

    /** A speech segment in pieces the model's context holds; false once admission is withdrawn. */
    private suspend fun decode(
        recognizer: OfflineRecognizer, hint: String, hotwords: String, samples: FloatArray, start: Double, speaker: String?,
        progress: LocalTranscriptionProgress,
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
                // Never `recognizer.createStream(hotwords)`: on Qwen3-ASR that call ends the process.
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

    /**
     * docs/05 "Fixed processing settings": transcribe unless the device is really hot. `SEVERE` is where Android
     * defines a large impact on the user and JobScheduler stops every job; `LIGHT` and `MODERATE` are
     * routine while charging. Battery Saver does not hold it back.
     */
    private fun admitted(): Boolean = power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE

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

    /**
     * The size of what a download would fetch — the speech and speaker models together while the speech
     * model is missing, the speaker models alone after — and how much of that is on disk while it is partly
     * there, read from the disk so it survives a restart. Speakers are separated once their models are here.
     */
    private fun info(status: LocalEngineStatus): LocalEngineInfo {
        val missing = listOf(store, speakers).filter { !it.installed() }.ifEmpty { listOf(store, speakers) }
        val total = missing.sumOf { it.totalBytes }
        val present = missing.sumOf { it.progress() * it.totalBytes }
        return LocalEngineInfo(
            status, Qwen3Asr.NAME, Qwen3Asr.REVISION,
            supportsDiarization = speakers.installed(),
            supportsVocabulary = true,
            modelBytes = total,
            progress = (present / total).takeIf { it > 0.0 && it < 1.0 },
            downloading = downloads.get() > 0,
        )
    }

    /** The diarizer for [path], its finished blocks kept under a name of the input's own. */
    private fun separation(path: String): SpeakerSeparation {
        val input = File(path)
        val key = "${path.hashCode().toUInt()}-${input.length()}-${input.lastModified()}"
        return SpeakerSeparation(
            (speakerDir / SpeakerDiarizationModels.SEGMENTATION).toString(),
            (speakerDir / SpeakerDiarizationModels.EMBEDDING).toString(),
            File(turnsDir, "$key.json"),
        )
    }

    /**
     * Qwen's hotwords: the terms joined by commas (a comma inside a term would split it), as many as fit in
     * [HOTWORDS_MAX_CHARS] — they share the model's context with the audio and the transcript, and a long
     * list takes room from both (sherpa-onnx warns past 48 tokens).
     */
    private fun hotwords(vocabulary: List<String>): String {
        val terms = vocabulary.map { it.replace(',', ' ').trim() }.filter { it.isNotEmpty() }
        var length = 0
        return terms.takeWhile { term -> (length + term.length + 1 <= HOTWORDS_MAX_CHARS).also { if (it) length += term.length + 1 } }
            .joinToString(",")
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
        /** About 40 tokens of Korean, fewer of English words: the short list sherpa-onnx asks for. */
        private const val HOTWORDS_MAX_CHARS = 80
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
            val root = context.noBackupFilesDir.absolutePath.toPath()
            val dir = root / "models" / Qwen3Asr.DIRECTORY
            // A parent of their own: a store removes every other folder beside its own once it is whole.
            val speakerDir = root / "speaker-models" / SpeakerDiarizationModels.DIRECTORY
            return QwenSpeechEngine(
                context.getSystemService(PowerManager::class.java),
                LocalModelStore(transport, FileSystem.SYSTEM, dir, Qwen3Asr.files, Dispatchers.IO),
                LocalModelStore(transport, FileSystem.SYSTEM, speakerDir, SpeakerDiarizationModels.files, Dispatchers.IO),
                speakerDir,
                File(context.noBackupFilesDir, "speaker-turns"),
            )
        }
    }
}
