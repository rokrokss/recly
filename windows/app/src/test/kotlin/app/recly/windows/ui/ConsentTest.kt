package app.recly.windows.ui

import app.recly.windows.plain
import app.recly.windows.i18n.StringTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6-L3 deliverable 2: the consent reminder is the Mac's, "the same wording" — and since I18N-L2 the Mac
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
    fun `the question and the reminder are word for word the Mac's, in both languages`() {
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
                    catalog.contains(strings[key].plain().escapedForJson()),
                    "$language/${key.key} is not in the Mac's own wording: ${strings[key]}",
                )
            }
        }
    }

    /**
     * The UX decisions of 2026-10-08: the dialog says the reminder and nothing else — one paragraph — and the
     * jurisdictions are behind the link.
     */
    @Test
    fun `the body is the reminder alone`() {
        for (language in LANGUAGES) {
            val body = StringTable.of(language)[Consent.BODY].plain()
            assertTrue('\n' !in body, "$language: the body is more than the reminder: $body")
            assertTrue("GDPR" !in body && "CA·CT" !in body, "$language: a jurisdiction is still in the dialog")
        }
        assertEquals(
            "This recording leaves no sign of itself for the other people in the meeting. Telling them is the responsibility of whoever records.",
            StringTable.of(StringTable.BASE)[Consent.BODY],
        )
        assertEquals(
            "이 녹음은 회의 상대에게 아무 표시도 남기지 않습니다. 고지 책임은 녹음하는 사람에게 있습니다.",
            StringTable.of(StringTable.KOREAN)[Consent.BODY].plain(),
        )
    }

    /** The link is a link and not a button, on both, for the same reason: the question is still open. */
    @Test
    fun `the guidance link is the one the Mac points at`() {
        assertTrue(File("../../apple/RecMac/RecMac/MenuModel.swift").readText().contains(Consent.LINK))
        for (language in LANGUAGES) {
            assertTrue(catalog.contains(StringTable.of(language)[Consent.LINK_TEXT].plain().escapedForJson()))
        }
    }

    /** A `.xcstrings` file is JSON, so its newlines and quotes are escaped and ours have to be too. */
    private fun String.escapedForJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        val LANGUAGES = listOf(StringTable.BASE, StringTable.KOREAN)
    }
}
