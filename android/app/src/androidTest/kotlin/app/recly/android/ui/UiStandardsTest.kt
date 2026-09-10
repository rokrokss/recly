package app.recly.android.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import app.recly.android.R
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression checks for actual glyph bounds at the device’s configured text size. */
class UiStandardsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun language(tag: String) {
        ui.runOnUiThread {
            ui.activity.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                android.os.LocaleList.forLanguageTags(tag)
        }
        ui.waitUntil(10_000) { ui.activity.resources.configuration.locales[0].language == tag }
        ui.waitForIdle()
    }

    @Before fun useEnglish() { language("en") }
    @After fun restoreEnglish() { language("en") }

    @Test fun koreanNavigationAndHeadingRemainWhole() {
        language("ko")
        kotlin.test.assertEquals("워크플로우", ui.activity.getString(R.string.tab_workflows))
        navigationLabelsRemainWholeAtTheConfiguredTextSize()
        theWorkflowHeadingKeepsReadableWidthBesideItsActions()
    }

    private fun clipped(layout: TextLayoutResult): Boolean =
        (0 until layout.lineCount).any { layout.isLineEllipsized(it) || layout.getLineRight(it) > layout.size.width + 1f } ||
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) < layout.layoutInput.text.length

    @Test
    fun navigationLabelsRemainWholeAtTheConfiguredTextSize() {
        for (key in listOf(R.string.tab_record, R.string.tab_jobs, R.string.tab_workflows, R.string.tab_settings)) {
            val label = ui.activity.getString(key)
            val layouts = mutableListOf<TextLayoutResult>()
            ui.onNode(hasText(label) and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse(layouts.any { clipped(it) }, "$label is visually clipped: ${layouts.map { layout -> "width=${layout.size.width}, lines=${layout.lineCount}, right=${(0 until layout.lineCount).map(layout::getLineRight)}, end=${layout.getLineEnd(layout.lineCount - 1, true)}, text=${layout.layoutInput.text}" }}")
        }
    }

    @Test
    fun theWorkflowHeadingKeepsReadableWidthBesideItsActions() {
        val label = ui.activity.getString(R.string.tab_workflows)
        val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        ui.onNode(hasText(label) and isTab).performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNode(hasText(label) and !hasAnyAncestor(isTab), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { it.size.width > 0 && !clipped(it) }, "The page heading is squeezed out by its actions")
    }
}
