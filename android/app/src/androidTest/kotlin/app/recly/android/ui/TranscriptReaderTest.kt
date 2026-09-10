package app.recly.android.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import okio.Path.Companion.toPath
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.recly.android.ui.theme.ReclyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import recly.core.model.Track
import recly.core.transcribe.*

class TranscriptReaderTest {
    @get:Rule val ui = createComposeRule()
    private val transcript = Transcript(recordingId = "reader-test", track = Track.MONO,
        language = "en", provider = TranscriptProvider("test"), createdAt = "2026-09-10T00:00:00Z",
        durationSec = 7200.0, speakers = listOf(TranscriptSpeaker("S1")),
        segments = List(120) { TranscriptSegment(it * 60.0, it * 60.0 + 2, "S1", "Passage number $it 안녕하세요") })

    @Test fun waveformSupportsKeyboardSeekAndFocusExit() {
        val audio = RecordingPlaylist.Selection(listOf("unused.m4a".toPath()), listOf(20.0))
        var position by mutableStateOf(0.0)
        ui.setContent {
            ReclyTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
                Column {
                    Waveform(audio, floatArrayOf(), position, {}, { position = it })
                    app.recly.android.ui.component.BlueprintButton("Next", {}, Modifier.testTag("after-waveform"))
                }
            }
        }
        ui.onNodeWithTag("waveform").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithTag("waveform").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        ui.runOnIdle { assertEquals(5.0, position) }
        ui.onNodeWithTag("waveform").performKeyInput { pressKey(Key.DirectionLeft) }
        ui.runOnIdle { assertEquals(0.0, position) }
        ui.onNodeWithTag("waveform").performKeyInput { pressKey(Key.Tab) }
        ui.onNodeWithTag("after-waveform").assertIsFocused()
    }

    @Test fun seekAndHeaderCopyKeepTheCompleteTranscript() {
        var target = -1.0
        ui.setContent { ReclyTheme {
            Column {
                TranscriptCopyButton(transcript)
                TranscriptReader(transcript, true, { target = it }, Modifier.fillMaxSize())
            }
        } }
        ui.onNodeWithTag("transcript-text-119").assertDoesNotExist()
        ui.onNodeWithTag("transcript-search-toggle").assertDoesNotExist()
        ui.onNodeWithTag("transcript-search").assertDoesNotExist()
        ui.onNodeWithTag("transcript-passages").performScrollToNode(hasTestTag("transcript-text-119"))
        ui.onNodeWithTag("transcript-text-119").assertIsDisplayed()
        ui.onNodeWithTag("transcript-time-119").performClick()
        ui.runOnIdle { assertEquals(7140.0, target) }
        ui.onNodeWithTag("transcript-copy").performClick()
        ui.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.contains("Passage number 0"))
            assertTrue(copied.contains("Passage number 119"))
        }
    }

    @Test fun missingAudioDisablesSeekingButKeepsHeaderCopyAvailable() {
        ui.setContent { ReclyTheme {
            Column {
                TranscriptCopyButton(transcript)
                TranscriptReader(transcript, false, {}, Modifier.fillMaxSize())
            }
        } }
        ui.onNodeWithTag("transcript-time-0").assertIsNotEnabled()
        ui.onNodeWithTag("transcript-copy").assertIsEnabled()
        ui.onNodeWithTag("transcript-search-toggle").assertDoesNotExist()
        ui.onNodeWithTag("transcript-text-0").assertIsDisplayed()
    }
}
