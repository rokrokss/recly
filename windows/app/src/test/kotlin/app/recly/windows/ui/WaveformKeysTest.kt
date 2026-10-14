@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

/** docs/09 Accessibility: the waveform, focused, moves the playhead with the arrows and plays or pauses with Space, as on the Mac. */
class WaveformKeysTest {

    @Test
    fun `Space on the focused waveform plays and pauses, the arrows seek`() {
        var plays = 0
        val seeks = mutableListOf<Double>()
        val audio = RecordingPlaylist.Selection(listOf("a.m4a".toPath()), listOf(40.0))
        val scene = ImageComposeScene(600, 200, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Waveform(
                    audio = audio, peaks = FloatArray(0), positionSec = 10.0, label = "Position",
                    onScrub = {}, onSeek = { seeks += it }, onPlayPause = { plays++ },
                )
            }
        }
        val source = java.awt.Canvas()
        fun press(key: Key, code: Int, char: Char) {
            for ((id, type) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(key, type, codePoint = char.code, nativeEvent = java.awt.event.KeyEvent(source, id, 0L, 0, code, char)))
            }
            scene.render()
        }
        try {
            scene.render()
            // Nothing has the focus yet, so Space reaches no one.
            press(Key.Spacebar, java.awt.event.KeyEvent.VK_SPACE, ' ')
            assertEquals(0, plays)
            press(Key.Tab, java.awt.event.KeyEvent.VK_TAB, '\t')
            press(Key.Spacebar, java.awt.event.KeyEvent.VK_SPACE, ' ')
            assertEquals(1, plays)
            press(Key.DirectionRight, java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.CHAR_UNDEFINED)
            assertEquals(listOf(15.0), seeks)
        } finally {
            scene.close()
        }
    }
}
