package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.Element

/**
 * docs/03 "다른 기기의 녹음" · docs/09 화면 원칙 2: the three lines a row uses to say that what it is
 * about is happening somewhere else — the watch handing a recording over, another device uploading
 * one, another device transcribing one.
 *
 * These are cross-shell lines (docs/07 rule 11) that only this shell says so far, so they are
 * locked here rather than in `CrossShellDictionaryTest`, which by its own rule holds nothing a
 * single shell says. When the Apple and Windows shells carry them, the lines move there and this
 * test goes away.
 */
class RemoteStateTextTest {

    @Test
    fun `each of the three says exactly what the dictionary says`() {
        val en = strings("values")
        val ko = strings("values-ko")

        LINES.forEach { (key, said) ->
            val (english, korean) = said
            assertEquals(english, en[key], "$key reads differently in English")
            assertEquals(korean, ko[key], "$key reads differently in Korean")
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

        val LINES = mapOf(
            "job_state_receiving" to ("Receiving from the watch" to "워치에서 받는 중"),
            "job_state_remote_uploading" to ("Uploading on another device" to "다른 기기에서 업로드 중"),
            "job_state_remote_transcribing" to ("Transcribing on another device" to "다른 기기에서 전사 중"),
        )
    }
}
