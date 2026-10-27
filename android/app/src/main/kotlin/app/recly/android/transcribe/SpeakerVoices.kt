package app.recly.android.transcribe

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import recly.core.transcribe.AudioSpan
import recly.core.transcribe.SpeakerTurns

/**
 * docs/08 "Length · size limits" on the phone: the voices the core links a long recording's pieces by — sherpa-onnx's
 * speaker embedding, the diarizer's own model at [embedding], over each stretch of the part files it is asked about,
 * each decoded from its `.m4a` on its own. Nothing is kept: the centroids go back to the core for its merge.
 */
internal class SpeakerVoices(private val embedding: String) {

    /** One centroid per key of [spans]: the mean of its spans' unit embeddings; a key none of whose spans embeds is left out. */
    suspend fun centroids(spans: Map<String, List<AudioSpan>>): Map<String, FloatArray> {
        val extractor = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(embedding, numThreads = THREADS))
        try {
            val out = LinkedHashMap<String, FloatArray>()
            for ((key, list) in spans) {
                val embeddings = list.mapNotNull { span ->
                    currentCoroutineContext().ensureActive()
                    val samples = PcmDecoder(span.file.toString(), span.startSec).use { pcm -> readSamples(span.sampleCount(), pcm::read) }
                    embed(extractor, samples)
                }
                SpeakerTurns.centroid(embeddings)?.let { out[key] = it }
            }
            return out
        } finally {
            extractor.release()
        }
    }

    /** Null when the samples are too few for the model to say anything. */
    private fun embed(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val THREADS = 2
    }
}

/** How many samples this span is at [SAMPLE_RATE]. */
internal fun AudioSpan.sampleCount(): Int = ((endSec - startSec) * SAMPLE_RATE).roundToInt().coerceAtLeast(0)

/** The first [count] samples of the chunks [next] hands out, null at the end; fewer when they end first. */
internal fun readSamples(count: Int, next: () -> FloatArray?): FloatArray {
    val out = FloatArray(count)
    var filled = 0
    while (filled < count) {
        val chunk = next() ?: break
        val n = minOf(chunk.size, count - filled)
        chunk.copyInto(out, filled, 0, n)
        filled += n
    }
    return if (filled == count) out else out.copyOf(filled)
}
