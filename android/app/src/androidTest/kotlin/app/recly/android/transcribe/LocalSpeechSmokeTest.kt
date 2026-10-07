package app.recly.android.transcribe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import recly.core.drive.KtorTransport
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalTranscriptionProgress
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.SttSegment

/**
 * Opt-in coverage of the production engine on a device: MediaCodec decoding, the native load, a
 * short transcription, a resume from the middle and a cancel. The ordinary suite neither downloads
 * the model nor runs speech inference. Push a ≤30 s m4a into the app's external files directory and
 * pass `localSpeech=1`, `localSpeechAudio=<file name>` and, to download the model (about 1 GB),
 * `localSpeechPrepare=1` as instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class LocalSpeechSmokeTest {
    private class Collect : LocalTranscriptionProgress {
        val segments = mutableListOf<SttSegment>()
        var onCheckpoint: () -> Unit = {}
        override suspend fun checkpoint(segment: SttSegment, completedThroughSec: Double) {
            assertTrue(completedThroughSec >= segment.end)
            segments += segment
            onCheckpoint()
        }
    }

    @Test
    fun productionEngineTranscribesResumesAndCancels() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("pass localSpeech=1 to run local speech inference", arguments.getString("localSpeech") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getExternalFilesDir(null)!!.resolve(arguments.getString("localSpeechAudio")!!).absolutePath
        val language = arguments.getString("localSpeechLanguage") ?: "ko"
        val engine = QwenSpeechEngine.make(context, KtorTransport(), app.recly.android.core.AndroidLogger())
        assertTrue(engine is QwenSpeechEngine, "this device should qualify for the engine")

        val initial = engine.status(language).status
        log("initial=$initial")
        if (initial == LocalEngineStatus.MODEL_REQUIRED && arguments.getString("localSpeechPrepare") == "1") {
            val started = System.nanoTime()
            log("prepared=${engine.prepare(language).status} in ${(System.nanoTime() - started) / 1e9}s")
        }
        assertEquals(LocalEngineStatus.READY, engine.status(language).status)

        val full = Collect()
        var started = System.nanoTime()
        val result = engine.transcribe(LocalTranscriptionRequest(audio, language), full)
        log("completed=${result.completed} seconds=${(System.nanoTime() - started) / 1e9}")
        full.segments.forEach { log("[%.2f–%.2f] %s".format(it.start, it.end, it.text)) }
        assertTrue(result.completed)
        assertTrue(full.segments.isNotEmpty(), "no speech was checkpointed")

        val from = full.segments.first().end
        val resumed = Collect()
        started = System.nanoTime()
        assertTrue(engine.transcribe(LocalTranscriptionRequest(audio, language, startTimeSec = from), resumed).completed)
        log("resumed from $from in ${(System.nanoTime() - started) / 1e9}s")
        resumed.segments.forEach { log("[%.2f–%.2f] %s".format(it.start, it.end, it.text)) }
        assertTrue(resumed.segments.isNotEmpty() && resumed.segments.all { it.start >= from - 0.01 })

        val cancelled = Collect().apply { onCheckpoint = { engine.cancel() } }
        assertFalse(engine.transcribe(LocalTranscriptionRequest(audio, language), cancelled).completed)
        assertEquals(1, cancelled.segments.size)
    }

    private fun log(message: String) = Log.i("LocalSpeechSmoke", message)
}
