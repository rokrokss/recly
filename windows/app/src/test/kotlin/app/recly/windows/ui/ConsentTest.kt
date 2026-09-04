package app.recly.windows.ui

import app.recly.windows.i18n.StringTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6-L3 deliverable 2: the consent reminder is the Mac's, "같은 문구" — and since I18N-L2 the Mac
 * says it in two languages, so this holds both of ours against both of its. A user with two
 * machines is being told about the same law by the same product, and a rewording on either side
 * fails here, which is the only place it could be noticed.
 *
 * The Mac's own String Catalog is read rather than a copy of it. The test's working directory is
 * `windows/app` (Gradle's default for a `Test` task).
 */
class ConsentTest {

    private val catalog = File("../../apple/RecMac/RecMac/Localizable.xcstrings").readText()

    @Test
    fun `the question and the three jurisdictions are word for word the Mac's, in both languages`() {
        val shared = listOf(
            Consent.QUESTION,
            Consent.CONFIRM,
            Consent.CANCEL,
            Consent.SUPPRESS,
            Consent.BODY,
        )

        for (language in LANGUAGES) {
            val strings = StringTable.of(language)
            for (key in shared) {
                assertEquals(
                    true,
                    catalog.contains(strings[key].escapedForJson()),
                    "$language/${key.key} is not in the Mac's own wording: ${strings[key]}",
                )
            }
        }
    }

    /** The link is a link and not a button, on both, for the same reason: the question is still open. */
    @Test
    fun `the guidance link is the one the Mac points at`() {
        assertTrue(File("../../apple/RecMac/RecMac/MenuModel.swift").readText().contains(Consent.LINK))
        for (language in LANGUAGES) {
            assertTrue(catalog.contains(StringTable.of(language)[Consent.LINK_TEXT].escapedForJson()))
        }
    }

    /** A `.xcstrings` file is JSON, so its newlines and quotes are escaped and ours have to be too. */
    private fun String.escapedForJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        val LANGUAGES = listOf(StringTable.BASE, StringTable.KOREAN)
    }
}
