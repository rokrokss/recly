@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import app.recly.windows.FakeSettings
import app.recly.windows.i18n.Localization
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.theme.Space
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 2026-10-09: controls that sit in one row of the detail share one height, one centre and one baseline — the
 * player bar's Play, speed chip and Highlight, and a transcript group's time and speaker.
 */
class DetailRowsTest {
    /** Each control's top, height and baseline in the root, in px at twice the size, as the shots draw them. */
    private val seen = mutableMapOf<String, Triple<Float, Float, Float>>()

    private fun Modifier.probe(name: String) = onGloballyPositioned {
        val bounds = it.boundsInRoot()
        seen[name] = Triple(bounds.top, bounds.height, bounds.top + it[FirstBaseline])
    }

    @Test
    fun `the player bar and a group's header each sit on one line`() {
        val strings = Localization(FakeSettings()) { "en" }.current
        val scene = ImageComposeScene(1600, 800, Density(2f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        BlueprintButton("Play", {}, Modifier.probe("play"), tone = ButtonTone.PRIMARY)
                        Box(Modifier.probe("speed")) { SpeedChip(1.25f, false, {}, {}, strings) }
                        BlueprintButton("Highlight", {}, Modifier.probe("highlight"), tone = ButtonTone.QUIET)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        BlueprintButton("00:11", {}, Modifier.probe("time"), tone = ButtonTone.QUIET, monospace = true)
                        Box(Modifier.probe("id")) { SpeakerBadge("S2", null) {} }
                        Box(Modifier.probe("name")) { SpeakerBadge("S1", "Mina") {} }
                    }
                }
            }
        }
        try {
            scene.render()
            listOf(listOf("play", "speed", "highlight"), listOf("time", "id", "name")).forEach { row ->
                val first = seen.getValue(row.first())
                row.drop(1).forEach { name -> assertEquals(first, seen.getValue(name), "$name against ${row.first()}") }
            }
        } finally {
            scene.close()
        }
    }
}
