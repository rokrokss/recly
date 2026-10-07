package app.recly.windows.transcribe

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import recly.core.transcribe.LocalModelStore
import recly.core.transcribe.SpeakerDiarizationModels
import recly.core.transcribe.SpeakerTurn
import recly.core.transcribe.SpeakerTurns

/**
 * docs/10 "Shared rules for the shells": who speaks when, on this PC — sherpa-onnx's offline diarization over
 * [SpeakerDiarizationModels], run on the whole input from 0 s so its turns are on the transcription's own axis.
 *
 * A recording of 20 minutes or more is diarized in blocks ([BlockCutter]) and each block's speakers are
 * embedded and linked across blocks with [SpeakerTurns.link]: sherpa takes the whole input in one call
 * and clusters with a matrix that grows with the square of it, so an hour in one call is gigabytes.
 * The same audio always gives the same turns, so a resumed transcription gets the labels it started with.
 */
internal class SpeakerDiarizer(private val store: LocalModelStore) {

    /**
     * The turns, labelled `"0"`, `"1"`, …; null when [cancelled] stopped it between chunks of the decoding or
     * between blocks. A block's own native call runs to its end: sherpa-onnx (v1.13.8) calls its progress
     * callback only while embedding and ignores what it returns, so there is no stopping it inside one.
     * [expectedSpeakers] is used as a fixed count only for a recording diarized in one call, and only when it
     * is a count: a block may hold fewer of the people than the room did, and "6+" is a floor.
     */
    suspend fun turns(path: String, expectedSpeakers: Int?, cancelled: () -> Boolean): List<SpeakerTurn>? {
        val blocks = mutableListOf<Pair<List<SpeakerTurn>, List<FloatArray>>>()
        var diarizer: OfflineSpeakerDiarization? = null
        val embedder = SpeakerEmbeddingExtractor(embeddingConfig())
        try {
            val cutter = BlockCutter(BLOCK_SAMPLES, MAX_BLOCK_SAMPLES)
            var stopped = false
            suspend fun diarize(startSample: Long, samples: FloatArray, last: Boolean) {
                currentCoroutineContext().ensureActive()
                if (cancelled()) {
                    stopped = true
                    return
                }
                val single = last && blocks.isEmpty()
                val engine = diarizer ?: OfflineSpeakerDiarization(config(if (single) clusters(expectedSpeakers) else -1)).also {
                    check(it.sampleRate == SAMPLE_RATE) { "diarization expects ${it.sampleRate} Hz" }
                    diarizer = it
                }
                val offset = startSample.toDouble() / SAMPLE_RATE
                val segments = engine.process(samples)
                val speakers = segments.map { it.speaker }.distinct().sorted()
                val turns = segments.map { SpeakerTurn(offset + it.start, offset + it.end, speakers.indexOf(it.speaker).toString()) }
                // One block has no other block to be linked with, so its speakers need no embedding.
                val centroids = if (single) emptyList() else speakers.map { speaker -> embed(embedder, samples, segments.filter { it.speaker == speaker }) }
                blocks += turns to centroids
            }
            PcmDecoder(path, 0.0).use { pcm ->
                while (!stopped) {
                    // Between chunks too, not only between blocks: a block is up to 20 minutes of audio to decode.
                    currentCoroutineContext().ensureActive()
                    if (cancelled()) {
                        stopped = true
                        break
                    }
                    val chunk = pcm.read() ?: break
                    for ((start, samples) in cutter.add(chunk)) if (!stopped) diarize(start, samples, last = false)
                }
                if (!stopped) cutter.finish()?.let { (start, samples) -> diarize(start, samples, last = true) }
            }
            if (stopped) return null
        } finally {
            diarizer?.release()
            embedder.release()
        }
        if (blocks.size == 1) return blocks.single().first
        val ids = SpeakerTurns.link(blocks.map { it.second })
        return blocks.flatMapIndexed { index, (turns, _) -> turns.map { it.copy(label = ids[index][it.label.toInt()].toString()) } }
    }

    /** One speaker's voice in a block: its longest turns, up to [EMBED_MAX_SEC] of them. */
    private fun embed(embedder: SpeakerEmbeddingExtractor, samples: FloatArray, turns: List<OfflineSpeakerDiarizationSegment>): FloatArray {
        var room = (EMBED_MAX_SEC * SAMPLE_RATE).toInt()
        val stream = embedder.createStream()
        try {
            for (turn in turns.sortedByDescending { it.end - it.start }) {
                if (room <= 0) break
                val from = (turn.start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                val to = minOf((turn.end * SAMPLE_RATE).toInt().coerceIn(from, samples.size), from + room)
                if (to <= from) continue
                stream.acceptWaveform(samples.copyOfRange(from, to), SAMPLE_RATE)
                room -= to - from
            }
            stream.inputFinished()
            return if (embedder.isReady(stream)) embedder.compute(stream) else FloatArray(embedder.dim)
        } finally {
            stream.release()
        }
    }

    private fun clusters(expected: Int?): Int = expected?.takeIf { it in 1 until OPEN_COUNT } ?: -1

    /** Every field set: the Java builders' defaults differ from the Kotlin ones (`debug` is on). */
    private fun config(clusters: Int) = OfflineSpeakerDiarizationConfig.builder()
        .setSegmentation(
            OfflineSpeakerSegmentationModelConfig.builder()
                .setPyannote(
                    OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                        .setModel(store.path(SpeakerDiarizationModels.SEGMENTATION).toString())
                        .setWindowShiftRatio(0.1f)
                        .build(),
                )
                .setNumThreads(THREADS)
                .setDebug(false)
                .setProvider("cpu")
                .build(),
        )
        .setEmbedding(embeddingConfig())
        .setClustering(FastClusteringConfig.builder().setNumClusters(clusters).setThreshold(THRESHOLD).setComputeConfidence(false).build())
        .setMinDurationOn(0.2f)
        .setMinDurationOff(0.5f)
        .build()

    private fun embeddingConfig() = SpeakerEmbeddingExtractorConfig.builder()
        .setModel(store.path(SpeakerDiarizationModels.EMBEDDING).toString())
        .setNumThreads(THREADS)
        .setDebug(false)
        .setProvider("cpu")
        .build()

    private companion object {
        const val THREADS = 2
        /** sherpa-onnx's own default distance for "another speaker"; to be tuned on real recordings. */
        const val THRESHOLD = 0.5f
        const val BLOCK_SAMPLES = 15 * 60 * SAMPLE_RATE
        /** Below 20 minutes the whole recording is one call; the last block is 5–20 minutes. */
        const val MAX_BLOCK_SAMPLES = 20 * 60 * SAMPLE_RATE
        const val EMBED_MAX_SEC = 30.0
        /** The title prompt's "6+": six and possibly more, so not a count to cluster to. */
        const val OPEN_COUNT = 6
    }
}

/**
 * A stream of samples cut into blocks of [block], except that the last one takes the tail with it — so a
 * recording under [max] is one block, and no block is shorter than `max - block` unless the whole is.
 */
internal class BlockCutter(private val block: Int, private val max: Int) {
    private var buffer = FloatArray(0)
    private var filled = 0
    private var emitted = 0L

    /** The blocks [chunk] completes, each with its first sample's index in the stream. */
    fun add(chunk: FloatArray): List<Pair<Long, FloatArray>> {
        val out = mutableListOf<Pair<Long, FloatArray>>()
        var at = 0
        while (at < chunk.size) {
            if (filled == max) {
                out += emitted to buffer.copyOfRange(0, block)
                buffer.copyInto(buffer, 0, block, filled)
                filled -= block
                emitted += block
            }
            if (buffer.size < max) buffer = buffer.copyOf(minOf(max, maxOf(buffer.size * 2, filled + chunk.size - at)))
            val n = minOf(chunk.size - at, max - filled)
            chunk.copyInto(buffer, filled, at, at + n)
            filled += n
            at += n
        }
        return out
    }

    /** What is left, as the last block; null when nothing is. */
    fun finish(): Pair<Long, FloatArray>? = if (filled == 0) null else emitted to buffer.copyOfRange(0, filled)
}

/**
 * A piece's speaker for the transcript, where [SpeakerTurns.split] could not name one: the piece before it,
 * or the earliest turn's for the start — every segment has to carry one or none is kept (docs/10).
 */
internal class SpeakerLabels(private val turns: List<SpeakerTurn>) {
    private var last: String? = null

    fun of(label: String): String = label.ifEmpty { last ?: turns.minBy { it.start }.label }.also { last = it }
}
