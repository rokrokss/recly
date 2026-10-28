@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import app.recly.windows.FakeSettings
import app.recly.windows.i18n.Localization
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BadgeTone
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LedgerStatus
import app.recly.windows.ui.component.StatusBadge
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import kotlin.test.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-10-09: controls that sit in one row of the detail share one height, one centre and one baseline — the
 * player bar's Play, speed chip and Highlight, and a transcript group's time and speaker; and a time is the status
 * badge's size (2026-10-28).
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
                        BlueprintButton("00:11", {}, Modifier.probe("time"), tone = ButtonTone.QUIET, monospace = true, compact = true)
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

    /**
     * docs/09 "Component measurements" — Time and speaker button (2026-10-28): a time is drawn at the status badge's
     * size, label 12 and 8 × 4 around it, and still pressed anywhere in a target of the touch minimum centred on it.
     */
    @Test
    fun `a time is drawn at the status badge's size inside a target of the touch minimum`() {
        var clicks = 0
        var page = Color.Unspecified
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                page = blueprint.background
                Column(Modifier.background(blueprint.background).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Box(Modifier.probe("badge")) { StatusBadge(LedgerStatus("DONE", BadgeTone.SUCCESS, "Done")) }
                    BlueprintButton("45:29", { clicks++ }, Modifier.probe("time"), tone = ButtonTone.QUIET, monospace = true, compact = true)
                }
            }
        }
        try {
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            /** How tall the edge drawn at [x] is, from [top] to [bottom]: its first ink to its last. */
            fun edgeHeight(x: Int, top: Int, bottom: Int): Int {
                val inked = (top until bottom).filter { pixels[x, it] != page }
                return inked.last() - inked.first() + 1
            }
            val (badgeTop, badgeHeight) = seen.getValue("badge")
            val (timeTop, timeHeight) = seen.getValue("time")
            // Past the corner and short of the label, where only the top and the bottom edge are drawn.
            val x = Space.m.value.toInt() + 5
            val badge = edgeHeight(x, badgeTop.toInt(), (badgeTop + badgeHeight).toInt())
            val time = edgeHeight(x, timeTop.toInt(), (timeTop + timeHeight).toInt())
            assertTrue(abs(badge - time) <= 1, "the time is $time tall, the badge $badge")
            assertTrue(time < 32, "the time is drawn $time tall")
            // The target: the touch minimum, with the drawn box in its middle — pressed above the box and below it.
            assertTrue(timeHeight >= MinTouch.value, "the target is $timeHeight tall")
            fun click(y: Float) {
                val at = Offset(x.toFloat(), y)
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.render()
            }
            click(timeTop + 2f)
            click(timeTop + timeHeight - 2f)
            assertEquals(2, clicks)
        } finally {
            scene.close()
        }
    }
}
