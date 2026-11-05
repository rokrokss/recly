package app.recly.windows.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.message
import app.recly.windows.ui.OrganizingStatus
import app.recly.windows.ui.ledgerStatus
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.worded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/09 screen principle 2 (2026-11-04): the ledger has no status column. A done row is its title with the transcript's
 * first words under it; any other row wears its badge there instead, at its own width, and not the first words. The
 * row's sentence still says the state, `Done` included, and the badge stays out of it.
 */
class LedgerRowTest {

    @Test
    fun `only a row that is not done wears its badge, under its title and in place of its first words`() {
        val en = StringTable.of(StringTable.BASE)
        val rows = listOf(
            Sample("Weekly sync", "Thanks for joining.", Str.STATE_DONE.message().ledgerStatus(en)),
            Sample("Call", "We shipped it.", Str.STATE_FAILED.message().ledgerStatus(en)),
            Sample("Standup", "Next steps first.", OrganizingStatus.worded(en)),
        )
        val scene = ImageComposeScene(520, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    LedgerHeader(time = "Time", title = "Title", length = "Length")
                    rows.forEach { row ->
                        LedgerRow(
                            date = "11-04", time = "09:00", title = row.title, subtitle = row.preview, length = "01:00",
                            status = row.status, announce = "${row.title} · ${row.status.label}", onClick = {},
                        )
                    }
                }
            }
        }
        try {
            repeat(3) { scene.render() }
            val texts = scene.unmergedTextNodes()
            fun drawn(text: String) = texts.filter { it.first == text }.map { it.second }

            // Done: the first words, and no badge.
            assertEquals(1, drawn("Thanks for joining.").size, "the done row's first words are not drawn")
            assertTrue(drawn("Done").isEmpty(), "a done row wears a badge")
            // Failed and Summarizing: the badge, and no first words.
            for ((row, word) in listOf(rows[1] to "Failed", rows[2] to "Summarizing")) {
                assertEquals(word, row.status.label)
                assertTrue(drawn(row.preview).isEmpty(), "${row.title} draws its first words under its badge")
                val badge = drawn(word).single()
                val title = drawn(row.title).single()
                // Under the title, from its leading edge, at its own width rather than the column's.
                assertTrue(badge.boundsInRoot.top >= title.boundsInRoot.bottom, "$word is not under ${row.title}")
                assertTrue(badge.boundsInRoot.left > title.boundsInRoot.left - 1f, "$word starts before ${row.title}")
                assertTrue(badge.boundsInRoot.left < title.boundsInRoot.left + 40f, "$word is not at the title's leading edge")
                // The title column runs to about 440 of the 520 here; the badge stops at its word.
                assertTrue(badge.boundsInRoot.right < 300f, "$word is drawn at the column's width, to ${badge.boundsInRoot.right}")
            }
            // No status column: the header heads three.
            assertFalse("Status" in texts.map { it.first })

            // Heard as one sentence each, the state in it whatever the badge does, and the badge not read on top of it.
            rows.forEach { row ->
                val node = scene.mergedNodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() == "${row.title} · ${row.status.label}" }
                assertNull(node.config.getOrNull(SemanticsProperties.Text), "${row.title} reads its texts on top of its sentence")
            }
        } finally {
            scene.close()
        }
    }

    private data class Sample(val title: String, val preview: String, val status: LedgerStatus)

    /** Every node of the scene's merged tree — one per thing a screen reader stops on. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.mergedNodes(): List<SemanticsNode> =
        semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.withDescendants() }

    /** Every text drawn in the scene and its node, read off the unmerged tree — which keeps what a merged row clears for its sentence. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.unmergedTextNodes(): List<Pair<String, SemanticsNode>> =
        semanticsOwners.flatMap { owner -> owner.unmergedRootSemanticsNode.withDescendants() }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text to node } }

    private fun SemanticsNode.withDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.withDescendants() }
}
