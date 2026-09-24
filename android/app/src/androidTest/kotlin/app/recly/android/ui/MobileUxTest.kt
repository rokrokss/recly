@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.recly.android.R
import app.recly.android.core.CoreModule
import kotlinx.coroutines.runBlocking
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.MetaWriter
import recly.core.ids.Ulid
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real activity, IME, navigation and decoder checks; no account or user recording is required. */
@RunWith(AndroidJUnit4::class)
class MobileUxTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun selectTab(label: Int) {
        ui.onNode(hasText(ui.activity.getString(label)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    }

    @Test
    fun finalizingAnOpenRecordingShowsItsPlayerWithoutReopening() {
        val core = runBlocking { CoreModule.get(ui.activity).core }
        val now = core.deps.clock.now()
        val id = Ulid.generate(kotlin.time.Clock.System)
        val meta = RecordingMeta(schema = 1, recordingId = id, source = Source.PHONE, platform = Platform.ANDROID,
            deviceId = core.deps.device.deviceId, deviceName = "UX test", startedAt = now.toString(), timezone = "UTC",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 48000, 1, 128, 300),
            tracks = listOf(Track.MONO), parts = emptyList(), status = RecordingStatus.RECORDING)
        val directory = core.deps.dataDir / "recordings" / MetaWriter.baseName(meta)
        val model = ViewModelProvider(ui.activity)[JobsViewModel::class.java]
        runBlocking { core.recordings.create(meta, directory) }
        try {
            ui.waitUntil(10_000) { model.state.value.items.any { it.recordingId == id } }
            selectTab(R.string.tab_jobs)
            ui.runOnIdle { model.openDetail(model.state.value.items.first { it.recordingId == id }) }
            ui.waitUntil(10_000) { model.state.value.detail?.let { !it.loading && it.writing } == true }
            runBlocking {
                core.deps.fileSystem.write(directory / "p001_mono.m4a") { writeUtf8("test audio") }
                core.recordings.addPart(id, Part(1, Track.MONO, "p001_mono.m4a", 10, "0".repeat(64), 0.0, 1.0))
                core.recordings.finalize(id, now, 1.0)
            }
            ui.waitUntil(10_000) { model.state.value.detail?.let { !it.writing && !it.audio.isEmpty && it.driveFetch == DriveFetch.IDLE } == true }
            ui.onNodeWithTag("play-pause").assertIsDisplayed()
            ui.runOnIdle { model.closeDetail() }
            ui.onNodeWithTag("recording-$id").performClick()
            val delete = ui.onNodeWithTag("recording-delete-$id").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val detail = ui.onNodeWithTag("open-detail").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(delete.left > detail.right, "Delete must stay to the right of the ordinary actions")
            assertTrue(delete.center.x > ui.activity.window.decorView.width / 2f, "Delete must stay on the right")
        } finally {
            ui.runOnIdle { model.closeDetail() }
            runBlocking { core.recordings.delete(id, deleteDrive = false) }
        }
    }

    @Test
    fun anInvalidAudioFileReportsFailureAndTheNextPressRetries() {
        val file = File.createTempFile("invalid-audio", ".m4a", ui.activity.cacheDir)
        file.writeText("not an audio file")
        lateinit var player: RecordingPlayer
        try {
            ui.runOnIdle {
                player = RecordingPlayer(ui.activity)
                player.load(RecordingPlaylist.Selection(listOf(file.path.toPath()), listOf(2.0)))
                player.play()
            }
            ui.waitUntil(10_000) { player.failed }
            ui.runOnIdle {
                assertFalse(player.isPlaying || player.buffering)
                player.play()
                assertFalse(player.failed)
            }
            ui.waitUntil(10_000) { player.failed }
        } finally {
            ui.runOnIdle { player.release() }
            file.delete()
        }
    }
}
