package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lane P1 deliverable 6 · acceptance 11: the editor's provider disclosure says the same thing on all
 * three shells, so this holds the desktop's lines against the phone's — the same way `ConsentTest`
 * holds the consent reminder against the Mac's. A rewording on either side fails here, which is the
 * only place it could be noticed.
 *
 * The phone's `strings.xml` is read rather than a copy of it. The test's working directory is
 * `windows/app` (Gradle's default for a `Test` task).
 */
class ProviderDisclosureTest {

    @Test
    fun `every disclosure is word for word the phone's, in both languages`() {
        for ((language, resources) in LANGUAGES) {
            val xml = File(resources).readText()
            val strings = StringTable.of(language)
            for (key in DISCLOSURES) {
                assertTrue(
                    xml.contains(strings[key].escapedForXml()),
                    "$language/${key.key} is not the phone's wording: ${strings[key]}",
                )
            }
        }
    }

    /**
     * docs/15 §3 "작성 규칙": the app does not say how long a provider keeps anything, because it
     * does not know and cannot control it. A number in these three sentences would be exactly that
     * claim — "kept for 30 days", "30일 보관" — so there are none.
     */
    @Test
    fun `no disclosure makes a retention claim`() {
        for ((language, _) in LANGUAGES) {
            val strings = StringTable.of(language)
            for (key in DISCLOSURES) {
                assertEquals(
                    "",
                    strings[key].filter { it.isDigit() },
                    "$language/${key.key} names a number, which docs/15 §3 forbids",
                )
            }
        }
    }

    /**
     * docs/15 §3: the provider policy URLs are not settled, so the disclosure carries none — an
     * invented link is worse than no link.
     */
    @Test
    fun `no disclosure carries a link`() {
        for ((language, _) in LANGUAGES) {
            val strings = StringTable.of(language)
            for (key in DISCLOSURES) {
                assertTrue(!strings[key].contains("http"), "$language/${key.key} carries a URL")
            }
        }
    }

    /** docs/15 §3: three sentences — what is sent, whose policy, and go and read it. */
    @Test
    fun `each disclosure is the three lines docs 15 asks for`() {
        for ((language, _) in LANGUAGES) {
            val strings = StringTable.of(language)
            for (key in DISCLOSURES) {
                assertEquals(3, strings[key].lines().size, "$language/${key.key} is not three lines")
            }
        }
    }

    /** An Android string resource escapes its apostrophes and writes its newlines as `\n`. */
    private fun String.escapedForXml(): String = replace("'", "\\'").replace("\n", "\\n")

    private companion object {
        val DISCLOSURES = listOf(Str.PROVIDER_DISCLOSURE_TRANSCRIBE)

        val LANGUAGES = listOf(
            StringTable.BASE to "../../android/app/src/main/res/values/strings.xml",
            StringTable.KOREAN to "../../android/app/src/main/res/values-ko/strings.xml",
        )
    }
}
