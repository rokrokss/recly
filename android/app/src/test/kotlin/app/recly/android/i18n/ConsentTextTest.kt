package app.recly.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Lane P1 deliverable 7: the recording-consent reminder is the Mac's, "같은 질문 · 같은 본문 · 같은
 * 관할 링크 · 같은 다시 묻지 않기" — in both languages, because since I18N-L2 the Mac says all of it
 * in two. A user with a Mac and a phone is being told about the same law by the same product, and a
 * rewording on either side fails here, which is the only place it could be noticed.
 *
 * The Mac's own String Catalog is read rather than a copy of it — the same shape as Windows'
 * `ConsentTest`, which holds those two together.
 */
class ConsentTextTest {

    private val catalog = File(REPO_ROOT, "apple/RecMac/RecMac/Localizable.xcstrings").readText()

    @Test
    fun `the question, the body and the two buttons are word for word the Mac's, in both languages`() {
        LOCALES.forEach { locale ->
            val strings = strings(locale)
            SHARED.forEach { key ->
                val value = strings[key] ?: error("$locale has no $key")
                assertTrue(
                    catalog.contains(value.escapedForJson()),
                    "$locale/$key is not in the Mac's own wording: $value",
                )
            }
        }
    }

    /** The link is a link and not a third button, on both, for the same reason: the question is open. */
    @Test
    fun `the guidance link is the one the Mac points at`() {
        val url = Regex("\"(https://en\\.wikipedia\\.org/[^\"]+)\"")
            .find(File(REPO_ROOT, "apple/RecMac/RecMac/MenuModel.swift").readText())
            ?.groupValues
            ?.get(1)
            ?: error("the Mac no longer links to a jurisdiction summary")

        assertTrue(
            File(REPO_ROOT, "android/app/src/main/kotlin/app/recly/android/ui/RecordingScreen.kt")
                .readText()
                .contains(url),
            "the phone's consent dialog does not open $url",
        )
    }

    private fun strings(locale: String): Map<String, String> {
        val file = File(REPO_ROOT, "android/app/src/main/res/$locale/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.unescapedFromAndroid()
        }
    }

    /**
     * The DOM hands back what the XML holds, and what the XML holds is Android's own escaping: an
     * apostrophe has to be backslashed and a line break is written `\n`. `aapt` undoes both at build
     * time; this has to undo them itself before the text can be compared with anybody else's.
     */
    private fun String.unescapedFromAndroid(): String =
        replace("\\n", "\n").replace("\\'", "'")

    /** A `.xcstrings` file is JSON, so its newlines and quotes are escaped and ours have to be too. */
    private fun String.escapedForJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        /** Unit tests run with `android/app` as the working directory. */
        val REPO_ROOT: File = File("../..").canonicalFile

        val LOCALES = listOf("values", "values-ko")

        val SHARED = listOf(
            "consent_question",
            "consent_body",
            "consent_confirm",
            "consent_suppress",
            "consent_link",
        )
    }
}
