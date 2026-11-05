package app.recly.recording

import android.content.ContextWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * docs/03 "Free storage on the device" on Android — the phone and the Galaxy Watch share this recorder: a start under
 * 200 MB free (the core's `StorageFloor`) is refused before the microphone opens, leaves nothing on disk or in the
 * database, says how much was free in the log, and reaches the screen as [RecorderEvent.NotEnoughStorage].
 */
class NotEnoughStorageTest {

    private val disk = FakeFileSystem()
    private val logger = RecordingLogger()
    private val core = testCore(disk, logger)

    @Test
    fun `a start under 200 MB is refused before anything is opened`() = runBlocking {
        val asked = mutableListOf<okio.Path>()
        val recorder = SegmentedRecorder(
            context = ContextWrapper(null),
            core = core,
            scope = this,
            onError = { fail("a refusal is not a failure: $it") },
            onStorageLow = { fail("nothing was recording") },
            freeBytes = { asked += it; 150_000_000L },
        )

        val refused = assertFailsWith<RecorderError> { recorder.start(title = null) }

        assertTrue(refused.notEnoughStorage)
        assertFalse(recorder.isRecording)
        assertEquals(listOf("/data".toPath()), asked, "the volume that holds the recording directory")
        assertEquals(emptyList(), core.recordings.list(limit = 10), "no row")
        assertFalse(disk.exists("/data/recordings".toPath()), "no directory")
        assertEquals(listOf(mapOf<String, Any?>("freeBytes" to 150_000_000L)), logger.fieldsOf("rec.recorder.storageRefused"))
        assertFalse("rec.recorder.startAborted" in logger.events, "nothing was opened to give back")
    }

    @Test
    fun `the session goes back to idle and the screen is told why`() = runBlocking {
        val state = MutableStateFlow<RecorderState>(RecorderState.Idle)
        val events = MutableSharedFlow<RecorderEvent>(replay = 4)
        var idles = 0
        val session = RecorderSession(
            host = TestHost(core, enqueues = false),
            state = state,
            events = events,
            completionScope = CoroutineScope(Dispatchers.Unconfined),
            onIdle = { idles++ },
        )
        assertTrue(session.begin())

        session.refusedForStorage()

        assertEquals(RecorderState.Idle, state.value)
        assertEquals(listOf<RecorderEvent>(RecorderEvent.NotEnoughStorage), events.replayCache)
        assertEquals(1, idles, "the service has nothing left to do")
        assertTrue(session.begin(), "the next start is taken")
    }
}
