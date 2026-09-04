package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Lane P1 deliverable 6 · docs/15 §3: the editor tells the user what leaves the phone when a
 * `transcribe` step runs, and whose policy decides what happens to it next.
 *
 * The rule this test exists for is docs/15 §3's "작성 규칙": the app must not state a retention it
 * cannot see. "Kept for 30 days" is a claim about somebody else's product that goes stale without
 * anybody here noticing, so no number followed by a day is allowed in either language.
 */
class ProviderDisclosureTest {

    @Test
    fun `every disclosure exists in both languages`() {
        LOCALES.forEach { locale ->
            val strings = strings(locale)
            KEYS.forEach { key ->
                val value = strings[key]
                assertTrue(!value.isNullOrBlank(), "$locale has no $key")
            }
        }
    }

    /** docs/15 §3: three sentences — what is sent, whose policy, and go and read it. */
    @Test
    fun `each disclosure is the three lines docs 15 asks for`() {
        LOCALES.forEach { locale ->
            val strings = strings(locale)
            KEYS.forEach { key ->
                assertEquals(3, strings.getValue(key).split("\\n").size, "$locale/$key is not three lines")
            }
        }
    }

    @Test
    fun `no disclosure claims a retention period`() {
        LOCALES.forEach { locale ->
            val strings = strings(locale)
            KEYS.forEach { key ->
                val value = strings.getValue(key)
                assertEquals(
                    emptyList(),
                    RETENTION_CLAIM.findAll(value).map { it.value }.toList(),
                    "$locale/$key claims a retention docs/15 says we cannot know",
                )
            }
        }
    }

    private fun strings(locale: String): Map<String, String> {
        val file = File(MODULE_ROOT, "app/src/main/res/$locale/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private companion object {
        /** Unit tests run with the module directory as the working directory. */
        val MODULE_ROOT: File = File("..").canonicalFile

        val LOCALES = listOf("values", "values-ko")

        val KEYS = listOf("provider_disclosure_transcribe")

        /** A number and a day, in either language: "30일", "30 days", "7-day". */
        val RETENTION_CLAIM = Regex("\\d+\\s*(일|-?\\s*days?)", RegexOption.IGNORE_CASE)
    }
}
