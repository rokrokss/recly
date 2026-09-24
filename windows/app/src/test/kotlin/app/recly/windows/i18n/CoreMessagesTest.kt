package app.recly.windows.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.message.CoreMessage

/**
 * docs/07 §5: the core says `NEEDS_AUTH`, this shell says the sentence — in whichever language it
 * is in at the moment something draws it.
 */
class CoreMessagesTest {

    @Test
    fun `every core message has a sentence in both languages`() {
        for (message in CoreMessage.entries) {
            val key = CoreMessages.keyOf(message)
            for (language in LANGUAGES) {
                val text = StringTable.of(language)[key]
                assertTrue(text.isNotBlank(), "$language/${key.key} is blank")
                assertEquals(
                    CoreMessages.takesArgument(message),
                    "%1\$s" in text,
                    "$language/${key.key}: the argument and the sentence disagree",
                )
            }
        }
    }

    @Test
    fun `a code with an argument reads it back into the sentence`() {
        val message = coreMessage(CoreMessage.MISSING_SECRET.code("webhook_secret"))

        assertEquals("This device has no value for the secret ‘webhook_secret’", message.text(en))
        assertEquals("이 기기에 시크릿 ‘webhook_secret’ 값이 없습니다", message.text(ko))
    }

    /** The one key whose argument is itself a code, resolved with the sentence around it. */
    @Test
    fun `a nested code is translated inside the sentence that carries it`() {
        val inner = CoreMessage.STEP_FAILED.code("HTTP 503")
        val message = coreMessage(CoreMessage.RETRY_BUDGET_SPENT.code(inner))

        assertEquals("Out of retries: Failed: HTTP 503", message.text(en))
        assertEquals("재시도 횟수를 다 썼습니다: 실패: HTTP 503", message.text(ko))
    }

    /** docs/07 §5 compatibility: a row written before the keys existed is a sentence, shown as it is. */
    @Test
    fun `legacy prose is shown exactly as it was stored`() {
        val stored = "업로드에 실패했습니다"

        assertEquals(UiMessage.Text(stored), coreMessage(stored))
        assertEquals(stored, coreMessage(stored).text(en))
        assertEquals(stored, coreMessage(stored).text(ko))
        assertNull(coreMessageDetail(stored))
    }

    /**
     * The key that names a secret is only a key when the argument really is a `secretRef` —
     * older builds wrote a bare `MISSING_SECRET` into the same column (`CoreMessageRef.parse`).
     */
    @Test
    fun `a bare secret code from an older build is not read as a key`() {
        assertEquals(UiMessage.Text("MISSING_SECRET"), coreMessage("MISSING_SECRET"))
    }

    /** The diagnostic is never translated and never part of the sentence. */
    @Test
    fun `a detail comes back beside the sentence, not inside it`() {
        val code = CoreMessage.AUTH_REJECTED.code(detail = "transcribe 401")

        assertEquals("The provider rejected the key.", coreMessage(code).text(en))
        assertEquals("제공자가 키를 거부했습니다.", coreMessage(code).text(ko))
        assertEquals("transcribe 401", coreMessageDetail(code))
    }

    private companion object {
        val LANGUAGES = listOf(StringTable.BASE, StringTable.KOREAN)

        val en = StringTable.of(StringTable.BASE)
        val ko = StringTable.of(StringTable.KOREAN)
    }
}
