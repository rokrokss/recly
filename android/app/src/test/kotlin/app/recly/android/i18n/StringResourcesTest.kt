package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * docs/07 rule 9, the completeness half: every key exists in both languages, in all three Android
 * modules. A key that only exists in `values/` falls back to English silently, which is exactly the
 * bug this catches — the phone would be Korean everywhere except the one string nobody translated.
 *
 * The watch and the recorder are checked from here because their own test counts are fixed by the
 * lane; nothing about the files needs their modules to be built.
 */
class StringResourcesTest {

    @Test
    fun `every module has the same keys in English and Korean`() {
        MODULES.forEach { module ->
            val base = keysOf(res(module, "values"))
            val korean = keysOf(res(module, "values-ko"))

            assertTrue(base.isNotEmpty(), "$module has no base strings")
            assertEquals(emptySet(), base - korean, "$module: keys with no Korean translation")
            assertEquals(emptySet(), korean - base, "$module: Korean keys with no English base")
        }
    }

    /**
     * A translation whose format arguments do not match the base crashes `getString` at runtime
     * rather than looking wrong, so it is worth its own check.
     */
    @Test
    fun `a translation uses the same format arguments as its base`() {
        MODULES.forEach { module ->
            val base = stringsOf(res(module, "values"))
            val korean = stringsOf(res(module, "values-ko"))

            base.forEach { (key, value) ->
                assertEquals(
                    formatArgs(value),
                    formatArgs(korean.getValue(key)),
                    "$module/$key: the Korean string's format arguments differ",
                )
            }
        }
    }

    /**
     * A `<plurals>` is a resource like any other, and a count the app says out loud in one language
     * and not the other is the same bug as a missing string. The *quantities* deliberately are not
     * compared: English needs `one` and `other`, Korean has only `other`, and that is the point of
     * the resource type.
     */
    @Test
    fun `every module has the same plurals in English and Korean`() {
        MODULES.forEach { module ->
            val base = pluralsOf(res(module, "values"))
            val korean = pluralsOf(res(module, "values-ko"))

            assertEquals(emptySet(), base - korean, "$module: plurals with no Korean translation")
            assertEquals(emptySet(), korean - base, "$module: Korean plurals with no English base")
        }
    }

    /** The languages the app declares are the languages it actually ships. */
    @Test
    fun `locales_config names exactly the translations that exist`() {
        val declared = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(MODULE_ROOT, "app/src/main/res/xml/locales_config.xml"))
            .getElementsByTagName("locale")
            .let { nodes -> (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") } }

        assertEquals(listOf("en", "ko"), declared)
    }

    private fun res(module: String, qualifier: String): File =
        File(MODULE_ROOT, "$module/src/main/res/$qualifier/strings.xml")

    private fun keysOf(file: File): Set<String> = stringsOf(file).keys

    private fun pluralsOf(file: File): Set<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("plurals")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }.toSet()
    }

    private fun stringsOf(file: File): Map<String, String> {
        assertTrue(file.isFile, "missing ${file.path}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun formatArgs(value: String): Set<String> = FORMAT_ARG.findAll(value).map { it.value }.toSet()

    private companion object {
        /** Unit tests run with the module directory as the working directory. */
        val MODULE_ROOT: File = File("..").canonicalFile

        val MODULES = listOf("app", "wear", "recording")

        val FORMAT_ARG = Regex("%\\d+\\$[a-zA-Z]")
    }
}
