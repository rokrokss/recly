package app.recly.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.recly.android.R
import app.recly.android.ui.theme.ReclyTheme
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import recly.core.model.Track
import recly.core.processing.ProcessingTranscription
import recly.core.processing.TranscriptionMode
import recly.core.transcribe.*

/** docs/09 "Detail header and More menu" · "Editing and speakers": the detail's actions, with the core faked. */
class RecordingDetailTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val transcript = Transcript(recordingId = "detail-test", track = Track.MONO,
        language = "en", provider = TranscriptProvider("test"), createdAt = "2026-10-07T00:00:00Z",
        durationSec = 30.0, speakers = listOf(TranscriptSpeaker("S1"), TranscriptSpeaker("S2")),
        segments = List(4) { TranscriptSegment(it * 8.0, it * 8.0 + 6, if (it % 2 == 0) "S1" else "S2", "Line $it") })
    private val external = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL)

    private fun detail(uploaded: Boolean = true) = DetailState(recordingId = "detail-test", title = "Weekly sync",
        loading = false, transcript = transcript, driveFetch = DriveFetch.IDLE, uploaded = uploaded)

    @Test fun transcribeAgainWaitsForTheUpload() {
        ui.setContent { ReclyTheme { RecordingDetailScreen(detail(uploaded = false), {}, {}, {}, actions = DetailActions(transcription = external)) } }
        ui.onNodeWithTag("detail-more").performClick()
        ui.onNodeWithTag("more-retranscribe").assertIsNotEnabled()
        ui.onNodeWithText(context.getString(R.string.detail_not_uploaded), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aRefusedTranscribeAgainSaysWhy() {
        ui.setContent { ReclyTheme {
            RecordingDetailScreen(detail(), {}, {}, {}, actions = DetailActions(transcription = external, onRetranscribe = { RetranscribeResult.NoAudio }))
        } }
        ui.onNodeWithTag("detail-more").performClick()
        ui.onNodeWithTag("more-retranscribe").assertIsEnabled().performClick()
        ui.onNodeWithTag("retranscribe-confirm").performClick()
        ui.onNodeWithTag("detail-refusal").assertTextEquals(context.getString(R.string.player_no_audio))
    }

    /** An edit is read-modify-write: a second speaker change waits until the first has saved. */
    @Test fun oneSpeakerChangeSavesAtATime() {
        val saved = CompletableDeferred<EditResult>()
        var edits = 0
        ui.setContent { ReclyTheme {
            RecordingDetailScreen(detail(), {}, {}, {}, actions = DetailActions(onEdit = { edits++; saved.await() }))
        } }
        ui.onNodeWithTag("transcript-speaker-0").performClick()
        ui.onNodeWithTag("speaker-change").performClick()
        ui.onNode(hasText("S2") and hasAnyAncestor(isPopup())).performClick()
        ui.onNodeWithTag("transcript-speaker-1").assertIsNotEnabled()
        ui.onNodeWithTag("transcript-speaker-0").assertIsNotEnabled()
        ui.runOnIdle { assertEquals(1, edits) }
        saved.complete(EditResult.Edited(transcript))
        ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("transcript-speaker-1") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
}
