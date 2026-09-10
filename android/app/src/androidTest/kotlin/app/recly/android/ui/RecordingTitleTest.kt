@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.android.ui

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.ui.theme.ReclyTheme
import app.recly.recording.RecorderEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okio.Path
import org.junit.After
import org.junit.Rule
import org.junit.Test
import recly.core.ids.Ulid
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track

/** Exercises the actual title dialog and model against isolated local recordings, without capture. */
class RecordingTitleTest {
    @get:Rule val ui = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private val events = MutableSharedFlow<RecorderEvent>(replay = 1)
    private val fixtures = mutableListOf<String>()
    private lateinit var model: RecordingViewModel

    @After fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        runBlocking {
            val core = CoreModule.get(app).core
            fixtures.forEach { core.recordings.delete(it, deleteDrive = false) }
        }
    }

    @Test fun cancelDeletesOnlyThisTakeAndALateSaveCannotQueueIt() {
        val (id, directory) = createRecording()
        val (otherId, otherDirectory) = createRecording()
        openPrompt(id)
        ui.onNodeWithText(app.getString(R.string.recording_title_skip)).assertDoesNotExist()
        ui.onNodeWithText(app.getString(R.string.action_cancel)).performClick()
        ui.runOnIdle { model.saveTitle("late save"); model.cancelTitle() }
        ui.waitUntil(10_000) {
            runBlocking { CoreModule.get(app).core.recordings.get(id) == null }
        }
        runBlocking {
            val core = CoreModule.get(app).core
            assertFalse(core.deps.fileSystem.exists(directory))
            assertTrue(core.jobs.list().none { it.recordingId == id })
            assertNotNull(core.recordings.get(otherId))
            assertTrue(core.deps.fileSystem.exists(otherDirectory))
        }
        ui.runOnIdle { assertNull(model.state.value.untitled) }
    }

    @Test fun saveKeepsTheTakeAndThePlaceholderIsNotStoredAsItsTitle() {
        val (id, directory) = createRecording()
        openPrompt(id)
        ui.onNodeWithText("2").performClick()
        ui.onNodeWithText(app.getString(R.string.recording_title_save)).performClick()
        ui.runOnIdle { model.cancelTitle() }
        ui.waitUntil(10_000) {
            runBlocking { CoreModule.get(app).core.jobs.list().any { it.recordingId == id } }
        }
        runBlocking {
            val core = CoreModule.get(app).core
            val record = assertNotNull(core.recordings.get(id))
            assertNull(record.meta.title)
            assertEquals(2, record.meta.context?.participants)
            assertTrue(core.deps.fileSystem.exists(directory))
        }
    }

    private fun openPrompt(id: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecordingViewModel(app, events) as T
            })[RecordingViewModel::class.java]
        }
        ui.setContent {
            val state by model.state.collectAsState()
            ReclyTheme {
                if (state.untitled != null) TitleDialog(model::saveTitle, model::cancelTitle)
            }
        }
        runBlocking { events.emit(RecorderEvent.Finished(id, 60.0, 1, emptyList(), enqueue = false)) }
        ui.waitUntil(10_000) {
            model.state.value.untitled?.recordingId == id && model.state.value.workflows.isNotEmpty()
        }
    }

    private fun createRecording(): Pair<String, Path> = runBlocking {
        val core = CoreModule.get(app).core
        val id = Ulid.generate(kotlin.time.Clock.System)
        val now = core.deps.clock.now().toString()
        val meta = RecordingMeta(schema = 1, recordingId = id, source = Source.PHONE,
            platform = Platform.ANDROID, deviceId = core.deps.device.deviceId, deviceName = "Title test",
            startedAt = now, endedAt = now, durationSec = 60.0, timezone = "UTC",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 48000, 1, 128, 300),
            tracks = listOf(Track.MONO),
            parts = listOf(Part(1, Track.MONO, "p001_mono.m4a", 10, "test", 0.0, 60.0)),
            status = RecordingStatus.FINALIZED)
        // Both fixtures start in the same second, so give each its own directory.
        val directory = core.deps.dataDir / "recordings" / id
        core.recordings.create(meta, directory)
        core.deps.fileSystem.write(directory / "p001_mono.m4a") { writeUtf8("test audio") }
        fixtures += id
        id to directory
    }
}
