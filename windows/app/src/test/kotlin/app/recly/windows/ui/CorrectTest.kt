@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.message
import app.recly.windows.plain
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.chatgpt.CorrectionResult
import recly.core.message.CoreMessage
import recly.core.transcribe.EditResult
import recly.core.transcribe.TranscriptVersionKind

/**
 * docs/09 "AI view" — "Correct transcript", "After a correction" and "Version history" on the PC: what the dialog says
 * for each answer, the line the reader's marks come with, each version's word, why a restore did not happen, and the
 * request's keys — Ctrl+Enter corrects, Enter is a new line.
 */
class CorrectTest {

    private val en = StringTable.of(StringTable.BASE)
    private val ko = StringTable.of(StringTable.KOREAN)

    @Test
    fun `a saved correction closes the dialog, and anything else is said in it`() {
        assertEquals(CorrectionState.None, correctionState(CorrectionResult.Corrected(listOf(2, 5), 0, "3"), Str.DETAIL_TRANSCRIBING))
        assertEquals(CorrectionState.NothingToCorrect, correctionState(CorrectionResult.NothingToCorrect, Str.DETAIL_TRANSCRIBING))
        // A job in the way, by More's own reason for it.
        assertEquals(CorrectionState.Refused(Str.DRIVE_PENDING), correctionState(CorrectionResult.Busy, Str.DRIVE_PENDING))
        assertEquals(CorrectionState.Refused(Str.DETAIL_NO_TRANSCRIPT), correctionState(CorrectionResult.NoTranscript, Str.DETAIL_TRANSCRIBING))
        val limit = CoreMessage.CHATGPT_USAGE_LIMIT.code()
        assertEquals(CorrectionState.Failed(limit), correctionState(CorrectionResult.Failed(limit), Str.DETAIL_TRANSCRIBING))
        // A call that threw is a failure without a reason of its own.
        assertEquals(CorrectionState.Failed(null), correctionState(null, Str.DETAIL_TRANSCRIBING))
    }

    @Test
    fun `a failure is the notes' notice, Manage usage for the usage limit and Retry for the rest`() {
        assertEquals(ChatGptRecovery.MANAGE_USAGE, correctionFailure(CoreMessage.CHATGPT_USAGE_LIMIT.code()).recovery)
        assertEquals(ChatGptRecovery.NONE, correctionFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()).recovery)
        val provider = correctionFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable answer"))
        assertEquals(ChatGptRecovery.RETRY, provider.recovery)
        assertEquals(ChatGptFailure(Str.CORRECT_FAILED.message(), null, ChatGptRecovery.RETRY), correctionFailure(null))
        assertEquals("Could not correct the transcript", en[Str.CORRECT_FAILED])
    }

    @Test
    fun `the line counts the lines changed, or the speakers named when no line was`() {
        val lines = CorrectionMarks(listOf(1, 4, 9), names = 1, previousVersionId = "2", createdAt = "2026-11-04T00:00:00Z")
        assertEquals("Corrected lines: 3", correctedLine(lines, en))
        assertEquals("교정한 줄: 3개", correctedLine(lines, ko).plain())
        val names = lines.copy(segments = emptyList())
        assertEquals("Corrected speaker names: 1", correctedLine(names, en))
        assertEquals("교정한 화자 이름: 1개", correctedLine(names, ko).plain())
    }

    @Test
    fun `each version says what made it`() {
        assertEquals(
            listOf("Transcribed", "Edited", "Corrected", "Restored", "Changed on another device"),
            TranscriptVersionKind.entries.map { en[versionKindLabel(it)] },
        )
        assertEquals(listOf("전사됨", "편집됨", "교정됨", "복원됨", "다른 기기에서 바뀜"), TranscriptVersionKind.entries.map { ko[versionKindLabel(it)].plain() })
    }

    @Test
    fun `a restore that did not happen says why`() {
        assertNull(restoreRefusal(EditResult.Edited(transcript()), Str.DETAIL_TRANSCRIBING))
        assertEquals(Str.DRIVE_PENDING, restoreRefusal(EditResult.Busy, Str.DRIVE_PENDING))
        assertEquals(Str.VERSIONS_RESTORE_FAILED, restoreRefusal(EditResult.Invalid("no version 9"), Str.DETAIL_TRANSCRIBING))
        assertEquals(Str.VERSIONS_RESTORE_FAILED, restoreRefusal(EditResult.NoTranscript, Str.DETAIL_TRANSCRIBING))
        // A call that threw.
        assertEquals(Str.VERSIONS_RESTORE_FAILED, restoreRefusal(null, Str.DETAIL_TRANSCRIBING))
    }

    @Test
    fun `Enter adds a line to the request, Ctrl+Enter corrects`() {
        val corrected = mutableListOf<String>()
        var field = Rect.Zero
        val scene = ImageComposeScene(460, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                var request by remember { mutableStateOf("") }
                Column(Modifier.onGloballyPositioned { field = it.boundsInRoot() }) {
                    CorrectPanel(CorrectionState.None, request, { request = it }, { corrected += it }, onManageUsage = {}, strings = en)
                }
            }
        }
        val source = java.awt.Canvas()
        fun type(text: String) {
            text.forEach { char ->
                val awt = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
                scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
            }
            scene.render()
        }
        fun enter(ctrl: Boolean) {
            val mask = if (ctrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0
            for ((type, kind) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
                val awt = java.awt.event.KeyEvent(source, type, 0L, mask, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                scene.sendKeyEvent(KeyEvent(Key.Enter, kind, isCtrlPressed = ctrl, nativeEvent = awt))
            }
            scene.render()
        }
        try {
            scene.render()
            // Into the request's box: it has no label over it, only its placeholder in it.
            val at = Offset(field.left + 40f, field.top + 20f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render()
            // Ctrl+Enter on an empty request sends nothing.
            enter(ctrl = true)
            type("Mina is Minna")
            enter(ctrl = false)
            type("Recly")
            assertEquals(emptyList(), corrected, "Enter corrected")
            enter(ctrl = true)
            assertEquals(listOf("Mina is Minna\nRecly"), corrected)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the copy is the spec's`() {
        assertEquals("Correct transcript…", en[Str.CORRECT_MENU])
        assertEquals("Version history", en[Str.VERSIONS_TITLE])
        assertEquals("Restore the version from Nov 4, 2026", en[Str.VERSIONS_RESTORE_LABEL, "Nov 4, 2026"])
        assertEquals("전사본 교정…", ko[Str.CORRECT_MENU].plain())
        assertEquals("교정하기", ko[Str.CORRECT].plain())
        assertEquals("버전 기록", ko[Str.VERSIONS_TITLE].plain())
    }

    private fun transcript() = recly.core.transcribe.Transcript(
        recordingId = "rec",
        track = recly.core.model.Track.MONO,
        language = "en",
        provider = recly.core.transcribe.TranscriptProvider("test"),
        createdAt = "2026-11-04T00:00:00Z",
        durationSec = 5.0,
        speakers = emptyList(),
        segments = listOf(recly.core.transcribe.TranscriptSegment(0.0, 5.0, "", "hello")),
    )
}
