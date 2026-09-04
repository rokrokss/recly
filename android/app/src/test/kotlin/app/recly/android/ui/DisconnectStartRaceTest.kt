package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * docs/03 · docs/12: the one race the [DisconnectGate] exists for, played out as the two pieces of
 * code that make it — `RecorderService.onStart` and `MainViewModel.disconnect` — with everything
 * else taken away.
 *
 * A capture that is opening has no job yet, so the core's own `Busy` guard does not see it, and
 * "also delete the recordings" walks the very directory it is about to write into. The claim under
 * test is that the two can never both go ahead, and that it holds without a lock around the start:
 * each side writes its own flag before it reads the other's, and neither of those pairs can be
 * split — they are consecutive statements on the main thread, and the file a start creates comes
 * later still, on the coroutine the service launches.
 */
class DisconnectStartRaceTest {

    /** Where a start arrives, which is the only thing that varies: the disconnect suspends once. */
    private enum class Arrival { BEFORE_THE_GATE, INSIDE_THE_REVOKE, AFTER_THE_CLEAN_UP }

    /**
     * `RecorderService.onStart`, minus the service. [arrive] is its synchronous half —
     * `session.begin()` and then `host().startsRefused()` — and [open] is everything the service
     * only does afterwards, on the coroutine where the first file of the capture is created.
     */
    private class Start(
        private val recorder: MutableStateFlow<Boolean>,
        private val audio: MutableList<String>,
    ) {
        var refused = false
            private set

        var opened = false
            private set

        fun arrive(): Boolean {
            // session.begin(): Idle to Starting, and one recording at a time.
            if (recorder.value) return false
            recorder.value = true
            // host().startsRefused(), before the notification and before anything is on disk.
            if (DisconnectGate.busy) {
                recorder.value = false // session.startFailed()
                refused = true
                return false
            }
            return true
        }

        /** The recovery pass, the encoder, the first segment — the point audio exists on disk. */
        suspend fun open() {
            yield()
            audio += "part-000"
            opened = true
        }
    }

    /** `MainViewModel.disconnect`, minus the ViewModel: the gate, the two live checks, the revoke. */
    private class Disconnect(
        private val recorder: MutableStateFlow<Boolean>,
        private val audio: MutableList<String>,
        private val revoked: CompletableDeferred<Unit>,
    ) {
        var deletedRecordings = false
            private set

        var blocked = false
            private set

        suspend fun run() = DisconnectGate.hold {
            if (recorder.value) {
                blocked = true
                return@hold
            }
            revoked.await() // The network round trip the gate is shut across.
            if (recorder.value) {
                blocked = true
                return@hold
            }
            audio.clear() // "also delete the recordings", over the directory a capture would use.
            deletedRecordings = true
        }
    }

    /**
     * The start that lands inside the revoke — a tile, a widget, the launcher shortcut or the watch
     * — is refused by the service itself, and refused before it has created anything: the gate is
     * shut at the caller, but the caller may already have gone by then.
     */
    @Test
    fun `a start that arrives while the gate is shut is refused before any file exists`() = runTest {
        val recorder = MutableStateFlow(false)
        val audio = mutableListOf<String>()
        val revoked = CompletableDeferred<Unit>()
        val disconnect = Disconnect(recorder, audio, revoked)

        val running = launch { disconnect.run() }
        yield() // As far as the revoke, and no further.
        val start = Start(recorder, audio)

        assertFalse(start.arrive())
        assertTrue(start.refused)
        assertFalse(start.opened)

        revoked.complete(Unit)
        running.join()
        assertTrue(disconnect.deletedRecordings)
        assertFalse(disconnect.blocked)
    }

    /**
     * The other order, and the half a flag alone cannot do: a start that got past `startsRefused`
     * did so *before* the flag went up, which means it had already published `Starting` — so the
     * disconnect's own live check reads it and says what is in the way instead of deleting the
     * directory out from under it (docs/12: never stop it for them).
     */
    @Test
    fun `a start that got in first stops the disconnect rather than the other way round`() = runTest {
        val recorder = MutableStateFlow(false)
        val audio = mutableListOf<String>()
        val revoked = CompletableDeferred<Unit>()
        val start = Start(recorder, audio)

        assertTrue(start.arrive())
        val disconnect = Disconnect(recorder, audio, revoked)
        val running = launch { disconnect.run() }
        start.open()
        revoked.complete(Unit)
        running.join()

        assertTrue(start.opened)
        assertFalse(start.refused)
        assertTrue(disconnect.blocked)
        assertFalse(disconnect.deletedRecordings)
        assertEquals(listOf("part-000"), audio)
    }

    /**
     * The whole claim in one: the disconnect can only be interrupted where it suspends, and that is
     * the revoke — everything else, on either side, is consecutive statements on the main thread.
     * So a start arrives before the gate, inside the revoke, or after the clean-up, and in none of
     * the three does a clean-up take away audio a capture had opened. There is no fourth arrival
     * for a `tryLock` around the start to catch.
     */
    @Test
    fun `no arrival lets a clean-up take the audio a capture opened`() = runTest {
        Arrival.entries.forEach { arrival ->
            val recorder = MutableStateFlow(false)
            val audio = mutableListOf<String>()
            val revoked = CompletableDeferred<Unit>()
            val start = Start(recorder, audio)
            val disconnect = Disconnect(recorder, audio, revoked)

            if (arrival == Arrival.BEFORE_THE_GATE && start.arrive()) start.open()
            val running = launch { disconnect.run() }
            yield()
            if (arrival == Arrival.INSIDE_THE_REVOKE && start.arrive()) start.open()
            revoked.complete(Unit)
            running.join()
            if (arrival == Arrival.AFTER_THE_CLEAN_UP && start.arrive()) start.open()

            // The one thing that must never happen: audio opened, and then walked over.
            if (start.opened) assertEquals(listOf("part-000"), audio, "$arrival lost the audio")
            // And never both refused either: one of the two always did what it came for.
            assertTrue(start.opened || disconnect.deletedRecordings, "$arrival did nothing at all")
        }
    }

    /** A start after the whole disconnect is an ordinary start: the gate is never left shut. */
    @Test
    fun `a start after the disconnect is over is let through`() = runTest {
        val recorder = MutableStateFlow(false)
        val audio = mutableListOf<String>()
        val revoked = CompletableDeferred<Unit>()
        val disconnect = Disconnect(recorder, audio, revoked)

        val running = launch { disconnect.run() }
        yield()
        revoked.complete(Unit)
        running.join()

        val start = Start(recorder, audio)
        assertTrue(start.arrive())
        start.open()

        assertTrue(disconnect.deletedRecordings)
        assertTrue(start.opened)
        assertFalse(start.refused)
    }
}
