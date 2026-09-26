package app.recly.android.work

import app.recly.android.ui.RecordingPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.Path.Companion.toPath

/**
 * A recording that has just become whole gets its peaks decoded and kept in the background — and a
 * capture that starts meanwhile wins: the decode stops and the recording is left to its first open.
 */
class WaveformPrecomputerTest {

    private val audio = RecordingPlaylist.Selection(listOf("/rec/p1.m4a".toPath()), listOf(1.0))
    private val saved = mutableMapOf<String, FloatArray>()
    private var decodes = 0
    private val capturing = MutableStateFlow(false)

    private fun precomputer(
        selection: RecordingPlaylist.Selection? = audio,
        kept: List<Float>? = null,
        decode: suspend () -> FloatArray = { FloatArray(4) { 0.5f } },
    ) = WaveformPrecomputer(
        selection = { selection },
        cached = { kept },
        save = { id, peaks -> saved[id] = peaks },
        decode = { decodes++; decode() },
        capturing = capturing,
    )

    @Test
    fun `a finalized recording's peaks are decoded and kept`() = runBlocking {
        assertTrue(precomputer().run("r"))

        assertEquals(1, decodes)
        assertEquals(4, saved.getValue("r").size)
    }

    @Test
    fun `peaks already kept are not decoded again`() = runBlocking {
        assertTrue(precomputer(kept = List(4) { 0.5f }).run("r"))

        assertEquals(0, decodes)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `nothing whole to decode is nothing done`() = runBlocking {
        assertFalse(precomputer(selection = null).run("r"))
        assertFalse(precomputer(selection = RecordingPlaylist.Selection.EMPTY).run("r"))

        assertEquals(0, decodes)
    }

    @Test
    fun `a capture that starts stops the decode and keeps nothing`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<FloatArray>()
        val run = async { precomputer(decode = { started.complete(Unit); never.await() }).run("r") }
        started.await()

        capturing.value = true

        assertFalse(withTimeout(5_000) { run.await() })
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `it waits for the microphone to be free before it starts`() = runBlocking {
        capturing.value = true
        val run = async { precomputer().run("r") }
        repeat(3) { yield() }

        assertEquals(0, decodes, "not while the microphone is taken")
        capturing.value = false

        assertTrue(withTimeout(5_000) { run.await() })
        assertEquals(1, decodes)
    }
}
