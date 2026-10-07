package app.recly.android.transcribe

import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import recly.core.transcribe.SpeakerTurn
import recly.core.transcribe.SpeakerTurns

/**
 * docs/10 "Shared rules for the shells" on the phone: who speaks when, from sherpa-onnx's pyannote
 * segmentation and speaker embeddings, on the same joined input the transcription reads and from 0 s, so
 * both share one time axis.
 *
 * A diarizer call takes its whole input at once and cannot be stopped, so a recording longer than
 * [MAX_BLOCK_SEC] is diarized in equal blocks of at most that, one block of PCM in memory at a time; the
 * speakers of each block are told apart by the centroid of their embeddings and linked across blocks by
 * the core's [SpeakerTurns.link]. A block is the unit of work: each one is kept in [store] as it is done,
 * so a pause for heat, a cancel or a killed process resumes at the next block with the same labels.
 */
internal class SpeakerSeparation(
    private val segmentation: String,
    private val embedding: String,
    private val store: File,
) {
    /**
     * The turns of [path] on its own axis, labelled with recording-wide speaker numbers; null when [proceed]
     * said stop between two blocks. [speakers] is how many people the user said were there, or null.
     */
    suspend fun turns(path: String, speakers: Int?, proceed: () -> Boolean): List<SpeakerTurn>? {
        val durationSec = durationSec(path)
        val count = max(1, ceil(durationSec / MAX_BLOCK_SEC).toInt())
        val blockSec = durationSec / count
        val done = load().toMutableList()
        if (done.size < count) {
            val diarizer = OfflineSpeakerDiarization(null, OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(OfflineSpeakerSegmentationPyannoteModelConfig(segmentation), numThreads = THREADS),
                embedding = SpeakerEmbeddingExtractorConfig(embedding, numThreads = THREADS),
                // A head count from the user fixes the number of clusters; without one the threshold decides.
                clustering = FastClusteringConfig(numClusters = speakers?.takeIf { it > 0 } ?: -1, threshold = THRESHOLD),
            ))
            val extractor = if (count > 1) SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(embedding, numThreads = THREADS)) else null
            try {
                PcmDecoder(path, done.size * blockSec).use { pcm ->
                    val reader = BlockReader(pcm)
                    for (index in done.size until count) {
                        currentCoroutineContext().ensureActive()
                        if (!proceed()) return null
                        val offset = index * blockSec
                        val samples = reader.read((blockSec * SAMPLE_RATE).toInt(), toEnd = index == count - 1)
                        // Under a second there is nobody to tell apart, and the segmentation window would be all padding.
                        val turns = if (samples.size < SAMPLE_RATE) emptyList()
                            else diarizer.process(samples).map { SpeakerTurn(offset + it.start, offset + it.end, it.speaker.toString()) }
                        val centroids = extractor?.let { centroids(it, samples, offset, turns) }.orEmpty()
                        done += Block(turns, centroids)
                        save(done)
                    }
                }
            } finally {
                extractor?.release()
                diarizer.release()
            }
        }
        if (count == 1) return done.single().turns
        val ids = SpeakerTurns.link(done.map { it.centroids })
        return done.flatMapIndexed { index, block ->
            block.turns.map { turn -> turn.copy(label = ids[index].getOrNull(turn.label.toInt())?.toString() ?: turn.label) }
        }
    }

    /** The transcription that needed these turns is over; a later one starts afresh. */
    fun forget() {
        store.delete()
    }

    /**
     * One centroid per speaker of the block, by their speaker number: the mean of the unit embeddings of
     * their longest turns. A speaker with no turn long enough gets zeros, which links to nobody.
     */
    private fun centroids(extractor: SpeakerEmbeddingExtractor, samples: FloatArray, offset: Double, turns: List<SpeakerTurn>): List<FloatArray> {
        val speakers = (turns.maxOfOrNull { it.label.toInt() } ?: -1) + 1
        return List(speakers) { speaker ->
            val sum = FloatArray(extractor.dim())
            turns.filter { it.label.toInt() == speaker && it.end - it.start >= MIN_EMBED_SEC }
                .sortedByDescending { it.end - it.start }
                .take(EMBEDDINGS_PER_SPEAKER)
                .forEach { turn ->
                    val from = ((turn.start - offset) * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                    val to = (from + (minOf(turn.end - turn.start, MAX_EMBED_SEC) * SAMPLE_RATE).toInt()).coerceAtMost(samples.size)
                    val stream = extractor.createStream()
                    try {
                        stream.acceptWaveform(samples.copyOfRange(from, to), SAMPLE_RATE)
                        stream.inputFinished()
                        if (extractor.isReady(stream)) {
                            val vector = extractor.compute(stream)
                            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
                            if (norm > 0) for (i in sum.indices) sum[i] += vector.getOrElse(i) { 0f } / norm
                        }
                    } finally {
                        stream.release()
                    }
                }
            sum
        }
    }

    private class Block(val turns: List<SpeakerTurn>, val centroids: List<FloatArray>)

    private fun save(blocks: List<Block>) {
        val json = JSONArray(blocks.map { block ->
            JSONObject()
                .put("turns", JSONArray(block.turns.map { JSONArray(listOf(it.start, it.end, it.label)) }))
                .put("centroids", JSONArray(block.centroids.map { centroid -> JSONArray(centroid.map { it.toDouble() }) }))
        })
        store.parentFile?.mkdirs()
        val temp = File(store.path + ".tmp")
        temp.writeText(json.toString())
        temp.renameTo(store)
    }

    private fun load(): List<Block> = runCatching {
        val json = JSONArray(store.readText())
        List(json.length()) { index ->
            val block = json.getJSONObject(index)
            val turns = block.getJSONArray("turns")
            val centroids = block.getJSONArray("centroids")
            Block(
                List(turns.length()) { i -> turns.getJSONArray(i).let { SpeakerTurn(it.getDouble(0), it.getDouble(1), it.getString(2)) } },
                List(centroids.length()) { i -> centroids.getJSONArray(i).let { c -> FloatArray(c.length()) { c.getDouble(it).toFloat() } } },
            )
        }
    }.getOrDefault(emptyList())

    private fun durationSec(path: String): Double {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1_000_000.0) ?: 0.0
        } finally {
            extractor.release()
        }
    }

    /** The decoder's chunks cut into blocks of a given size, carrying what is left over into the next. */
    private class BlockReader(private val pcm: PcmDecoder) {
        private var carry = FloatArray(0)

        fun read(samples: Int, toEnd: Boolean): FloatArray {
            var block = FloatArray(if (toEnd) samples + SAMPLE_RATE else samples)
            var filled = 0
            fun take(from: FloatArray): FloatArray {
                val room = if (toEnd) from.size else minOf(from.size, block.size - filled)
                if (filled + room > block.size) block = block.copyOf(maxOf(block.size * 2, filled + room))
                from.copyInto(block, filled, 0, room)
                filled += room
                return from.copyOfRange(room, from.size)
            }
            carry = take(carry)
            while (toEnd || filled < block.size) {
                val chunk = pcm.read() ?: break
                carry = take(chunk)
            }
            return if (filled == block.size) block else block.copyOf(filled)
        }
    }

    companion object {
        /** docs/10: blocks of 10–20 minutes — one call under this, equal blocks over it. */
        const val MAX_BLOCK_SEC = 20 * 60.0
        private const val THREADS = 2
        /** sherpa-onnx's own starting value for the threshold mode; to be tuned on real recordings. */
        private const val THRESHOLD = 0.5f
        /** Embeddings from turns this long and up, at most this long, at most this many per speaker. */
        private const val MIN_EMBED_SEC = 1.5
        private const val MAX_EMBED_SEC = 10.0
        private const val EMBEDDINGS_PER_SPEAKER = 6
    }
}
