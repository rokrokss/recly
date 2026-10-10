package app.recly.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import app.recly.android.ui.theme.ReclyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * docs/09 "Summary view": a summary's bulleted lines, each starting with a citation, put the citations one line
 * apart — closer than their 48dp targets are tall. A tap at the middle of any of them plays that one.
 */
class CitedTextTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun aTapAtTheMiddleOfEachStackedCitationPlaysThatOne() {
        val text = "- [00:00:05] Beta feedback is positive.\n- [00:00:11] On-device is the default.\n- [00:00:17] Show arriving recordings."
        val played = mutableListOf<Double>()
        ui.setContent { ReclyTheme { CitedText(text, { true }, { played += it }) } }

        val node = ui.onNode(hasText("Beta feedback", substring = true))
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.single()
        listOf("[00:00:05]" to 5.0, "[00:00:11]" to 11.0, "[00:00:17]" to 17.0).forEach { (citation, sec) ->
            val glyphs = layout.getBoundingBox(text.indexOf(citation) + citation.length / 2)
            node.performTouchInput { click(Offset(glyphs.center.x, glyphs.center.y)) }
            ui.waitForIdle()
            assertEquals(sec, played.last(), "the middle of $citation")
        }
        assertEquals(3, played.size)
    }
}
