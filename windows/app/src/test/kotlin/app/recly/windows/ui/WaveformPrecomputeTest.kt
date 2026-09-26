package app.recly.windows.ui

import app.recly.windows.SilentLogger
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okio.Path
import okio.Path.Companion.toPath
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.RecordingRecord

/**
 * A recording finalized on this PC has its waveform worked out in the background and kept, so its
 * first open draws at once — and the work gives way to a capture, a delete and a disconnect.
 */
class WaveformPrecomputeTest {

    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val dir: Path = "/recordings/01J9REC".toPath()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        worker.close()
    }

    @Test
    fun `a finalized recording has its waveform worked out and kept`() {
        val kept = CopyOnWriteArrayList<Pair<String, List<Float>>>()
        val precompute = precompute(keep = { id, peaks -> kept += id to peaks })

        precompute.enqueue("rec-1")

        val (id, peaks) = await("nothing was kept") { kept.firstOrNull() }
        assertEquals("rec-1", id)
        // The mix track's one part: half a second, two windows.
        assertEquals(listOf(0.5f, 0.5f), peaks)
    }

    @Test
    fun `a waveform already kept for the same parts is not decoded again`() {
        val spawns = CopyOnWriteArrayList<Path>()
        val kept = CopyOnWriteArrayList<List<Float>>()
        val precompute = precompute(
            spawns = spawns,
            kept = { listOf(0.1f, 0.2f) },
            keep = { _, peaks -> kept += peaks },
        )

        precompute.enqueue("rec-1")
        precompute.enqueue("rec-2")

        // The queue runs in order: once the second has been looked at, the first was too.
        await("the queue did not run") { loads.takeIf { it.size == 2 } }
        Thread.sleep(50)
        assertTrue(spawns.isEmpty(), "a kept waveform was decoded again")
        assertTrue(kept.isEmpty())
    }

    /** A capture starting, or a delete or a disconnect about to remove the files: nothing is decoded. */
    @Test
    fun `nothing is decoded while the playback gate is up`() {
        val spawns = CopyOnWriteArrayList<Path>()
        val precompute = precompute(spawns = spawns, busy = { true })

        precompute.enqueue("rec-1")

        await("the queue did not run") { loads.takeIf { it.isNotEmpty() } ?: skipped.takeIf { it > 0 } }
        Thread.sleep(50)
        assertTrue(spawns.isEmpty())
    }

    /** A delete waits on this: the ffmpeg reading the part is gone when it returns, and nothing is kept. */
    @Test
    fun `a halt stops the decode and keeps nothing`() {
        val reading = CountDownLatch(1)
        val kept = CopyOnWriteArrayList<List<Float>>()
        val precompute = precompute(
            process = { BlockingProcess(reading) },
            keep = { _, peaks -> kept += peaks },
        )

        precompute.enqueue("rec-1")
        reading.await()

        assertTrue(precompute.halt(), "the decoder outlived the halt")
        Thread.sleep(50)
        assertTrue(kept.isEmpty())
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private val loads = CopyOnWriteArrayList<String>()

    @Volatile private var skipped = 0

    private fun precompute(
        spawns: MutableList<Path> = CopyOnWriteArrayList(),
        process: () -> Process = { Pcm(pcm(List(8_000) { 16_384 })) },
        kept: suspend (String) -> List<Float>? = { null },
        keep: suspend (String, List<Float>) -> Unit = { _, _ -> },
        busy: () -> Boolean = { false },
    ) = WaveformPrecompute(
        scope = scope,
        worker = worker,
        load = { id -> loads += id; record(id) },
        kept = kept,
        keep = keep,
        spawn = { path, _ -> spawns += path; process() },
        exists = { true },
        busy = { busy().also { if (it) skipped++ } },
        logger = SilentLogger,
    )

    private fun record(id: String) = RecordingRecord(
        id = id,
        meta = RecordingMeta(
            schema = 1,
            recordingId = id,
            source = Source.DESKTOP,
            platform = Platform.WINDOWS,
            deviceId = "device",
            deviceName = "PC",
            startedAt = "2026-09-27T10:00:00.000Z",
            timezone = "Asia/Seoul",
            durationSec = 0.5,
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MIC, Track.SYS, Track.MIX),
            parts = Track.entries.filter { it != Track.MONO }.map { track ->
                Part(1, track, "p001_${track.name.lowercase()}.m4a", 8_000, "0".repeat(64), 0.0, 0.5)
            },
            status = RecordingStatus.FINALIZED,
        ),
        dir = dir,
    )

    private fun <T : Any> await(what: String, read: () -> T?): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError(what)
    }

    private fun pcm(samples: List<Int>): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}

/** An ffmpeg that has written its part and exited. */
private class Pcm(pcm: ByteArray) : Process() {
    private val stdout = ByteArrayInputStream(pcm)
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() = Unit
}

/** An ffmpeg in the middle of a long part: its stdout blocks until the process is destroyed. */
private class BlockingProcess(private val reading: CountDownLatch) : Process() {
    private val killed = CountDownLatch(1)
    private val stdout = object : InputStream() {
        override fun read(): Int {
            reading.countDown()
            killed.await()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
    }
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int = 0.also { killed.await() }
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = killed.await(timeout, unit)
    override fun exitValue(): Int = 1
    override fun destroy() = killed.countDown()
    override fun destroyForcibly(): Process = also { killed.countDown() }
}
