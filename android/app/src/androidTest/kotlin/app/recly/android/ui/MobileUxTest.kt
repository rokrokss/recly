@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.android.ui

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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

    @Test
    fun selectedWorkflowCannotBeDeletedThroughAStaleRow() {
        val model = ViewModelProvider(ui.activity)[WorkflowsViewModel::class.java]
        ui.waitUntil(20_000) { !model.state.value.loading }
        val core = runBlocking { CoreModule.get(ui.activity).core }
        val item = model.state.value.items.first()
        runBlocking { core.workflows.setDeviceDefault(item.id) }
        ui.waitUntil(10_000) { model.state.value.items.any { it.id == item.id && it.isDeviceDefault } }
        selectTab(R.string.tab_workflows)
        ui.onNodeWithTag("workflow-delete-${item.id}").assertIsNotEnabled()
        ui.onNodeWithTag("transcription-setup").performClick()
        ui.onNodeWithTag("transcription-setup-body").assertIsDisplayed()
        ui.onNodeWithTag("transcription-setup-close").performClick()
        val before = runBlocking { core.workflows.current() }

        ui.runOnIdle {
            model.dismissMessage()
            model.delete(item.copy(isDeviceDefault = false))
        }
        ui.waitUntil(10_000) { model.state.value.message != null }
        val after = runBlocking { core.workflows.current() }
        kotlin.test.assertEquals(before.revision, after.revision)
        kotlin.test.assertEquals(before.workflows.map { it.id }, after.workflows.map { it.id })
        ui.onNodeWithTag("workflow-delete-${item.id}").assertIsNotEnabled()
    }

    private fun selectTab(label: Int) {
        ui.onNode(hasText(ui.activity.getString(label)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    }

    private fun openEditor(): WorkflowsViewModel {
        val model = ViewModelProvider(ui.activity)[WorkflowsViewModel::class.java]
        ui.waitUntil(20_000) { !model.state.value.loading }
        selectTab(R.string.tab_workflows)
        val item = model.state.value.items.first()
        ui.onNodeWithTag("workflow-summary-${item.id}").assertHasNoClickAction()
        ui.onNodeWithTag("workflow-edit-${item.id}").performClick()
        ui.onNodeWithTag("workflow-name").assertIsDisplayed()
        return model
    }

    @Test
    fun switchingKeyFormsRequiresDiscardAndKeepsTheSameKey() {
        val model = openEditor()
        ui.runOnIdle {
            model.openSecrets("first_key")
            model.secretValue("unsaved test value")
            model.openSecrets("first_key")
            assertTrue(model.state.value.discardTarget == null)
            model.openSecrets("second_key")
            assertTrue(model.state.value.secretsOpen?.value == "unsaved test value")
        }
        ui.onNodeWithText(ui.activity.getString(R.string.keep_editing)).performClick()
        ui.runOnIdle {
            assertTrue(model.state.value.secretsOpen?.value == "unsaved test value")
            model.openSecrets("second_key")
        }
        ui.onNodeWithText(ui.activity.getString(R.string.discard_changes)).performClick()
        ui.runOnIdle {
            assertTrue(model.state.value.secretsOpen?.name == "second_key")
            assertTrue(model.state.value.secretsOpen?.value == "")
            model.closeSecrets()
        }
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
    fun editingWithTheKeyboardKeepsSaveVisible() {
        val model = openEditor()
        ui.onNodeWithTag("workflow-name").performClick().performTextReplacement("UX test draft")
        // Window insets become visible before the IME animation has finished resizing Compose.
        ui.waitUntil(5_000) {
            val view = ui.activity.window.decorView
            val insets = view.rootWindowInsets
            val bottom = ui.onNodeWithTag("workflow-save").fetchSemanticsNode().boundsInWindow.bottom
            insets?.isVisible(android.view.WindowInsets.Type.ime()) == true &&
                bottom <= view.height - insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        }
        val save = ui.onNodeWithTag("workflow-save").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val view = ui.activity.window.decorView
        val keyboardTop = view.height - view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
        assertTrue(save.bottom <= keyboardTop, "Save overlaps the keyboard")
        ui.onNodeWithText(ui.activity.getString(R.string.action_cancel)).performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.keep_editing)).performClick()
        ui.onNodeWithTag("workflow-name").assertTextContains("UX test draft")
    }

    @Test
    fun backFromAnotherTabKeepsTheParkedDraft() {
        val model = openEditor()
        ui.runOnIdle { model.update { it.copy(name = "Parked draft") } }
        selectTab(R.string.tab_settings)
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        selectTab(R.string.tab_workflows)
        ui.onNodeWithTag("workflow-name").assertTextContains("Parked draft")
        ui.runOnIdle { model.cancel() }
        ui.onNodeWithText(ui.activity.getString(R.string.discard_changes)).performClick()
        ui.runOnIdle { assertTrue(model.state.value.editor == null) }
    }

    @Test
    fun savingAnInvalidNameReturnsToTheFieldThatNeedsFixing() {
        val model = openEditor()
        ui.runOnIdle {
            model.update { it.copy(name = "") }
            model.openStep(0)
        }
        ui.onNodeWithTag("workflow-save").performClick()
        ui.waitUntil(10_000) { model.state.value.editor?.errors?.name != null }
        ui.onNodeWithTag("workflow-name").assertIsDisplayed().assertIsFocused()
        ui.onNodeWithTag("workflow-save").assertIsDisplayed()
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
