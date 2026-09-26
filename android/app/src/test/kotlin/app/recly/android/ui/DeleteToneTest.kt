package app.recly.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every button that deletes for good — a recording, an API key, the confirm of a delete dialog — is
 * drawn in the danger tone, on every screen. Red for a status still means a failure only.
 */
class DeleteToneTest {

    @Test
    fun `every Delete button is red`() {
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val source = file.readText()
            calls(source, "BlueprintButton(")
                .filter { "R.string.action_delete" in it && "ButtonTone.DANGER" !in it }
                .map { "${file.name}: ${it.lineSequence().first().trim()}" }
        }.toList()
        assertEquals(emptyList(), offenders)
    }

    /** The argument lists of every call to [name], by matching its parentheses. */
    private fun calls(source: String, name: String): List<String> {
        val found = mutableListOf<String>()
        var from = source.indexOf(name)
        while (from >= 0) {
            var depth = 0
            var end = from + name.length - 1
            while (end < source.length) {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            found += source.substring(from, minOf(end + 1, source.length))
            from = source.indexOf(name, end)
        }
        return found
    }
}
