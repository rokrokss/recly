package app.recly.windows.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import recly.core.drive.KtorTransport
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalTranscriptionProgress
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.SttSegment

/**
 * Opt-in coverage of the production engine: the model download, the native load and a short
 * transcription, a resume from the middle and a cancel. The ordinary suite neither downloads the
 * model nor runs speech inference. Run with
 * `-Drecly.localSpeech=1 -Drecly.localSpeech.audio=<≤30 s m4a> -Drecly.localSpeech.dataDir=<dir>`,
 * plus `-Drecly.localSpeech.prepare=1` to download the model (about 1 GB) into that directory.
 */
class LocalSpeechSmokeTest {
    private fun prop(key: String): String? = System.getProperty(key)?.takeIf { it.isNotBlank() }

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
    fun `the production engine transcribes, resumes and cancels a short file`() = runBlocking {
        if (prop(GATE) != "1") {
            println("LOCAL SPEECH skipped — no -D$GATE=1")
            return@runBlocking
        }
        val audio = requireNotNull(prop("$GATE.audio")) { "provide a short local speech fixture" }
        val dataDir = requireNotNull(prop("$GATE.dataDir")) { "provide a directory for the model" }.toPath()
        val language = prop("$GATE.language") ?: "ko"
        val engine = QwenSpeechEngine.make(dataDir, KtorTransport(), FileSystem.SYSTEM, Dispatchers.IO)
        assertTrue(engine is QwenSpeechEngine, "this machine should qualify for the engine")

        val initial = engine.status(language).status
        println("local speech smoke: initial=$initial")
        if (initial == LocalEngineStatus.MODEL_REQUIRED && prop("$GATE.prepare") == "1") {
            val started = System.nanoTime()
            println("local speech smoke: prepared=${engine.prepare(language).status} in ${(System.nanoTime() - started) / 1e9}s")
        }
        assertEquals(LocalEngineStatus.READY, engine.status(language).status)
        assertEquals(LocalEngineStatus.UNSUPPORTED, engine.status("uk").status)

        val full = Collect()
        var started = System.nanoTime()
        val result = engine.transcribe(LocalTranscriptionRequest(audio, language), full)
        println("local speech smoke: completed=${result.completed} seconds=${(System.nanoTime() - started) / 1e9}")
        full.segments.forEach { println("  [%.2f–%.2f] %s".format(it.start, it.end, it.text)) }
        assertTrue(result.completed)
        assertTrue(full.segments.isNotEmpty(), "no speech was checkpointed")
        var end = 0.0
        for (segment in full.segments) {
            assertTrue(segment.start >= end - 0.01 && segment.end > segment.start)
            end = segment.end
        }
        prop("$GATE.expected")?.let { expected ->
            assertTrue(full.segments.joinToString(" ") { it.text }.contains(expected), "expected '$expected'")
        }

        // Resuming after the first checkpoint only produces what comes after it, on the file's axis.
        val from = full.segments.first().end
        val resumed = Collect()
        started = System.nanoTime()
        assertTrue(engine.transcribe(LocalTranscriptionRequest(audio, language, startTimeSec = from), resumed).completed)
        println("local speech smoke: resumed from $from in ${(System.nanoTime() - started) / 1e9}s")
        resumed.segments.forEach { println("  [%.2f–%.2f] %s".format(it.start, it.end, it.text)) }
        assertTrue(resumed.segments.isNotEmpty() && resumed.segments.all { it.start >= from - 0.01 })

        // A cancel takes effect at the next piece and reports the run as not completed.
        val cancelled = Collect().apply { onCheckpoint = { engine.cancel() } }
        assertFalse(engine.transcribe(LocalTranscriptionRequest(audio, language), cancelled).completed)
        assertEquals(1, cancelled.segments.size)
    }

    private companion object {
        const val GATE = "recly.localSpeech"
    }
}
