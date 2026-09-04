package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.Element

/**
 * docs/07 rule 9, the other half: no Korean is left in code. What counts is a *literal* — comments
 * are allowed, because docs/ is written in Korean and the code cites its section names, and log
 * strings never reach a screen anyway (CONTRIBUTING.md).
 *
 * The base `values/strings.xml` is checked too: English is the base language, so Korean there means
 * a string that was never translated but merely moved.
 */
class NoHangulLiteralsTest {

    @Test
    fun `no Korean literal is left in any main source`() {
        val offenders = MODULES
            .flatMap { module -> File(MODULE_ROOT, "$module/src/main/kotlin").walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> hangulLiterals(file.readText()).map { "${file.path}: $it" } }

        assertEquals(emptyList(), offenders, "user-visible text belongs in strings.xml (docs/07)")
    }

    @Test
    fun `the base strings are English`() {
        MODULES.forEach { module ->
            val korean = baseStrings(module)
                .filterKeys { it !in ALLOWED_BASE_KEYS }
                .filterValues { HANGUL.containsMatchIn(it) }

            assertEquals(emptyMap(), korean, "$module/values/strings.xml is the English base")
        }
    }

    /** The values only — an XML comment may cite a docs/ section, which is written in Korean. */
    private fun baseStrings(module: String): Map<String, String> {
        val file = File(MODULE_ROOT, "$module/src/main/res/values/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    /**
     * Every string literal in [source] that contains Hangul. Comments are skipped, so a `docs/03
     * "이름 규칙"` citation is not an offence, and neither is a Korean sentence inside a `//` line.
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
        val MODULE_ROOT: File = File("..").canonicalFile

        val MODULES = listOf("app", "wear", "recording")

        val HANGUL = Regex("[가-힣]")

        /** The one thing an English base says in Korean: the name of the Korean language. */
        val ALLOWED_BASE_KEYS = setOf("settings_language_ko")
    }
}
