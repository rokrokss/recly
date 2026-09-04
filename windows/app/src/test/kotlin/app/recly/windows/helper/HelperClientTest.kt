package app.recly.windows.helper

import app.recly.windows.SilentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import recly.core.model.Track

/**
 * Deliverable 7: the docs/14 protocol against a real process — commands out on stdin, events in on
 * stdout, and the channel closing when the helper is gone.
 */
class HelperClientTest {

    @Test
    fun `a start produces one part per track and a stop ends the stream`() = runBlocking {
        coroutineScope {
            val client = HelperClient(FakeHelperCommand.command("parts=2", "sec=1.5"), Dispatchers.IO, SilentLogger)
            client.open(this)
            client.send(HelperCommand.Start("/tmp/rec", "20260827T100000Z_desktop_01H", 900, TRACKS))

            val parts = withTimeout(TIMEOUT_MS) { client.events.receiveParts(2 * TRACKS.size) }

            // docs/03 "이름 규칙": the app owns the names, the helper writes what it was told.
            assertEquals(
                listOf(
                    "20260827T100000Z_desktop_01H_p001_mic.m4a",
                    "20260827T100000Z_desktop_01H_p001_sys.m4a",
                    "20260827T100000Z_desktop_01H_p001_mix.m4a",
                    "20260827T100000Z_desktop_01H_p002_mic.m4a",
                    "20260827T100000Z_desktop_01H_p002_sys.m4a",
                    "20260827T100000Z_desktop_01H_p002_mix.m4a",
                ),
                parts.map { it.file },
            )
            assertEquals(listOf(Track.MIC, Track.SYS, Track.MIX), parts.take(3).map { it.track })
            assertEquals(1.5, parts.first().durationSec)
            assertEquals(1.5, parts.last().startOffsetSec)

            client.send(HelperCommand.Stop)
            withTimeout(TIMEOUT_MS) { client.events.drain() }
            client.close()
        }
    }

    @Test
    fun `a helper that dies closes the stream after the parts it managed to report`() = runBlocking {
        coroutineScope {
            val client = HelperClient(FakeHelperCommand.command("die"), Dispatchers.IO, SilentLogger)
            client.open(this)
            client.send(HelperCommand.Start("/tmp/rec", "base", 900, listOf(Track.MIC)))

            val received = withTimeout(TIMEOUT_MS) { client.events.drain() }

            val parts = received.filterIsInstance<HelperEvent.PartDone>()
            assertEquals(1, parts.size, "everything up to the moment it was killed")
            client.close()
        }
    }

    @Test
    fun `detect on brings back the app holding the microphone`() = runBlocking {
        coroutineScope {
            val client = HelperClient(FakeHelperCommand.command("micInUse=Zoom.exe"), Dispatchers.IO, SilentLogger)
            client.open(this)
            client.send(HelperCommand.Detect(on = true))

            val event = withTimeout(TIMEOUT_MS) { client.events.receive() }

            assertEquals(HelperEvent.MicInUse("Zoom.exe"), event)
            client.send(HelperCommand.Stop)
            client.close()
        }
    }

    @Test
    fun `a line that is not protocol does not end the recording`() = runBlocking {
        // A panic message, a stray println. The event after it still arrives.
        coroutineScope {
            val client = HelperClient(FakeHelperCommand.command("noise", "parts=1"), Dispatchers.IO, SilentLogger)
            client.open(this)
            client.send(HelperCommand.Start("/tmp/rec", "base", 900, listOf(Track.MIC)))

            val part = withTimeout(TIMEOUT_MS) { client.events.receiveParts(1) }

            assertEquals(1, part.size)
            client.send(HelperCommand.Stop)
            client.close()
        }
    }

    @Test
    fun `a command that names no helper at all is not one`() {
        // Deliverable 5: no binary, no recording — the tray says so rather than failing at start.
        assertTrue(CaptureHelper.command(env = { null }, property = { null }) == null)
        assertEquals(
            listOf("C:\\helper.exe", "--verbose"),
            CaptureHelper.command(env = { "C:\\helper.exe --verbose" }, property = { null }),
        )
    }

    /**
     * The next [count] `part_done`s. The real helper also sends a `level` line for every pump that
     * finished a tenth of a second (docs/09 화면 원칙 6), and they arrive between the parts; the
     * fake sends none. What these tests are about is the parts either way.
     */
    private suspend fun ReceiveChannel<HelperEvent>.receiveParts(count: Int): List<HelperEvent.PartDone> =
        buildList {
            while (size < count) (receive() as? HelperEvent.PartDone)?.let { add(it) }
        }

    /** Everything until the helper's stdout ends. */
    private suspend fun <T> ReceiveChannel<T>.drain(): List<T> = buildList {
        for (item in this@drain) add(item)
    }

    private companion object {
        val TRACKS = listOf(Track.MIC, Track.SYS, Track.MIX)
        const val TIMEOUT_MS = 60_000L
    }
}
