package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.Element

/**
 * A count in English is a plural or a label with the number after it ("Recordings waiting: 3"),
 * never "3 recording(s)". "Minimum length (s)" is seconds and has no count in it.
 */
class EnglishCountsTest {

    @Test
    fun `no English count is written with (s)`() {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
        val offenders = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { "(s)" in it.textContent && COUNT.containsMatchIn(it.textContent) }
            .map { it.getAttribute("name") }
        assertEquals(emptyList(), offenders)
    }

    private companion object {
        val COUNT = Regex("%\\d+\\\$d")
    }
}
