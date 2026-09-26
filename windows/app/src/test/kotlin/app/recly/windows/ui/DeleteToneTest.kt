package app.recly.windows.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Delete cannot be undone — a recording, a key, or the confirm of a delete question — so every
 * button that says it wears the danger tone, on every surface. Read off the sources, because the
 * buttons are drawn deep inside windows a unit test does not open.
 *
 * The working directory is `windows/app` (Gradle's default for a `Test` task), as in `PreviewTest`.
 */
class DeleteToneTest {

    @Test
    fun `every Delete button is drawn in the danger tone`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        val deletes = sources.flatMap { file ->
            val text = file.readText()
            Regex("""BlueprintButton\(""").findAll(text).mapNotNull { match ->
                val call = callAt(text, match.range.last)
                if ("Str.DELETE]" in call) "${file.name}: ${call.replace(Regex("\\s+"), " ")}" else null
            }.toList()
        }
        assertTrue(deletes.size >= 5, "the Delete buttons were not found: $deletes")
        assertEquals(emptyList(), deletes.filterNot { "ButtonTone.DANGER" in it })
    }

    /** The argument list of the call whose opening parenthesis is at [open]. */
    private fun callAt(text: String, open: Int): String {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open, index + 1)
            }
        }
        return text.substring(open)
    }
}
