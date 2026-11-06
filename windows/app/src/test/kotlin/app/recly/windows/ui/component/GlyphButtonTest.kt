package app.recly.windows.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 "Detail header and More menu": Import audio, Export and More are icons on Windows as on the other shells. The
 * icon has no words, so the button is announced by its label; off, it does not take the click.
 */
class GlyphButtonTest {

    private fun clicksOn(enabled: Boolean): Int {
        var clicks = 0
        val scene = ImageComposeScene(44, 44, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                GlyphButton(Glyph.IMPORT, "Import audio…", { clicks++ }, enabled = enabled)
            }
        }
        try {
            repeat(3) { scene.render() }
            // A corner of the target, away from the glyph: the whole square is the button.
            val at = Offset(2f, 2f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            repeat(3) { scene.render() }
            return clicks
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the whole target takes the click, and not while it is off`() {
        assertEquals(1, clicksOn(enabled = true))
        assertEquals(0, clicksOn(enabled = false))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a screen reader hears one button, by its label`() {
        val scene = ImageComposeScene(44, 44, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                GlyphButton(Glyph.EXPORT, "Export…", {})
            }
        }
        try {
            repeat(3) { scene.render() }
            val named = scene.semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.withDescendants() }
                .filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }
            assertEquals(listOf(listOf("Export…")), named.map { it.config[SemanticsProperties.ContentDescription] })
            assertEquals(Role.Button, named.single().config.getOrNull(SemanticsProperties.Role))
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.withDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.withDescendants() }
}
