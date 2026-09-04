package app.recly.windows.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/07 rule 9, the other half: no Korean is left in the code. What counts is a *literal* —
 * comments are allowed, because docs/ is written in Korean and this code cites its section names,
 * and log event names never reach a screen anyway (CONTRIBUTING.md).
 *
 * The phone runs the same scan (`android/.../i18n/NoHangulLiteralsTest`); this is that scanner over
 * the desktop's sources.
 */
class NoHangulLiteralsTest {

    @Test
    fun `no Korean literal is left in any main source`() {
        val offenders = SOURCES.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> hangulLiterals(file.readText()).map { "${file.path}: $it" } }
            .filterNot { offence -> ALLOWED.any { offence.endsWith(": $it") } }
            .toList()

        assertEquals(emptyList(), offenders, "user-visible text belongs in i18n/strings_*.properties")
    }

    /**
     * Every string literal in [source] that contains Hangul. Comments are skipped, so a
     * `docs/03 "이름 규칙"` citation is not an offence, and neither is a Korean sentence in a `//`
     * line.
     */
    private fun hangulLiterals(source: String): List<String> {
        val found = mutableListOf<String>()
        val literal = StringBuilder()
        var index = 0
        var inLiteral = false
        var raw = false

        fun finish() {
            if (HANGUL.containsMatchIn(literal)) found += literal.toString().trim()
            literal.clear()
            inLiteral = false
        }

        while (index < source.length) {
            val rest = source.length - index
            when {
                !inLiteral && rest >= 2 && source.startsWith("//", index) ->
                    index = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length

                !inLiteral && rest >= 2 && source.startsWith("/*", index) ->
                    index = (source.indexOf("*/", index).takeIf { it >= 0 } ?: source.length - 2) + 2

                !inLiteral && rest >= 3 && source.startsWith("\"\"\"", index) -> {
                    inLiteral = true
                    raw = true
                    index += 3
                }

                !inLiteral && source[index] == '"' -> {
                    inLiteral = true
                    raw = false
                    index++
                }

                inLiteral && raw && rest >= 3 && source.startsWith("\"\"\"", index) -> {
                    finish()
                    index += 3
                }

                inLiteral && !raw && source[index] == '\\' -> {
                    literal.append(source, index, minOf(index + 2, source.length))
                    index += 2
                }

                inLiteral && !raw && source[index] == '"' -> {
                    finish()
                    index++
                }

                else -> {
                    if (inLiteral) literal.append(source[index])
                    index++
                }
            }
        }
        return found
    }

    private companion object {
        /** Tests run with `windows/app` as the working directory (Gradle's default). */
        val SOURCES = File("src/main/kotlin")

        val HANGUL = Regex("[가-힣]")

        /**
         * Korean a literal is allowed to hold: nothing yet. A Windows registry path or a process
         * name would go here — not a sentence, which belongs in the tables.
         */
        val ALLOWED = emptySet<String>()
    }
}
