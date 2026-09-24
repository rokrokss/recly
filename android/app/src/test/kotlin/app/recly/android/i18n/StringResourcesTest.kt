package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * docs/07 rule 9, the completeness half: every key exists in all supported languages, in all three Android
 * modules. A key that only exists in `values/` falls back to English silently, which is exactly the
 * bug this catches — a translated screen must not contain an untranslated fallback.
 *
 * The watch and the recorder are checked from here because their own test counts are fixed by the
 * lane; nothing about the files needs their modules to be built.
 */
class StringResourcesTest {

    @Test
    fun `every module has the same keys in every supported language`() {
        MODULES.forEach { module ->
            val base = keysOf(res(module, "values"))
            assertTrue(base.isNotEmpty(), "$module has no base strings")
            QUALIFIERS.forEach { qualifier ->
                assertEquals(base, keysOf(res(module, qualifier)), "$module/$qualifier: string keys")
            }
        }
    }

    @Test
    fun `translations retain the base format arguments`() {
        MODULES.forEach { module ->
            val base = stringsOf(res(module, "values"))
            QUALIFIERS.forEach { qualifier ->
                val translated = stringsOf(res(module, qualifier))
                base.forEach { (key, value) ->
                    assertEquals(formatArgs(value), formatArgs(translated.getValue(key)), "$module/$qualifier/$key")
                }
            }
        }
    }

    @Test
    fun `every module has the same plurals in every supported language`() {
        MODULES.forEach { module ->
            val base = pluralsOf(res(module, "values"))
            QUALIFIERS.forEach { qualifier ->
                assertEquals(base, pluralsOf(res(module, qualifier)), "$module/$qualifier: plural keys")
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res(module, qualifier))
                val plurals = document.getElementsByTagName("plurals")
                for (index in 0 until plurals.length) {
                    val element = plurals.item(index) as Element
                    val items = element.getElementsByTagName("item")
                    assertTrue((0 until items.length).any { (items.item(it) as Element).getAttribute("quantity") == "other" })
                    for (item in 0 until items.length) {
                        assertEquals(setOf("%1\$d"), formatArgs(items.item(item).textContent), "$module/$qualifier: count argument")
                    }
                }
            }
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

        assertEquals(listOf("en", "ko", "ja", "zh-Hans", "zh-Hant", "es", "fr", "de", "pt", "ar", "hi", "ru"), declared)
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

        val QUALIFIERS = listOf("values-ko", "values-ja", "values-b+zh+Hans", "values-b+zh+Hant", "values-es", "values-fr", "values-de", "values-pt", "values-ar", "values-hi", "values-ru")

        val MODULES = listOf("app", "wear", "recording")

        val FORMAT_ARG = Regex("%\\d+\\$[a-zA-Z]")
    }
}
