package app.recly.android.core

import app.recly.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.message.CoreMessage

/**
 * docs/07 rule 3: a line that outlives the screen — a ViewModel's, the language setting recreates
 * the activity under it — has to stay a name until something draws it, or it keeps the language it
 * was built in. The two string tables here stand in for the resources of the activity before and
 * after that change; the message is rendered through both without being rebuilt.
 */
class UiMessageTest {

    /** Copied from `values/strings.xml` and `values-ko/strings.xml` — the real sentences. */
    private val english = mapOf(
        R.string.auth_sign_in_failed to "Sign-in failed: %1\$s",
        R.string.core_sign_in_cancelled to "The sign-in was cancelled",
        R.string.core_retry_budget_spent to "Out of retries: %1\$s",
        R.string.core_webhook_http to "The webhook answered HTTP %1\$s",
    )

    private val korean = mapOf(
        R.string.auth_sign_in_failed to "로그인 실패: %1\$s",
        R.string.core_sign_in_cancelled to "로그인이 취소되었습니다",
        R.string.core_retry_budget_spent to "재시도 횟수를 다 썼습니다: %1\$s",
        R.string.core_webhook_http to "웹훅이 HTTP %1\$s로 응답했습니다",
    )

    private fun strings(table: Map<Int, String>): (Int, List<Any>) -> String =
        { id, args -> table.getValue(id).format(*args.toTypedArray()) }

    /** The shape `MainViewModel.reason` builds: a sentence of ours with a core code inside it. */
    @Test
    fun `a message held past a language change renders in the new language`() {
        val message = UiMessage.Res(
            R.string.auth_sign_in_failed,
            listOf(coreMessage(CoreMessage.SIGN_IN_CANCELLED.code())),
        )

        assertEquals("Sign-in failed: The sign-in was cancelled", message.text(strings(english)))
        assertEquals("로그인 실패: 로그인이 취소되었습니다", message.text(strings(korean)))
    }

    /** The core nests too: the argument of `RETRY_BUDGET_SPENT` is the code that spent the last try. */
    @Test
    fun `a core code nested in a core code is resolved all the way down`() {
        val code = CoreMessage.RETRY_BUDGET_SPENT.code(CoreMessage.WEBHOOK_HTTP.code("503"))

        assertEquals(
            "Out of retries: The webhook answered HTTP 503",
            coreMessage(code).text(strings(english)),
        )
        assertEquals(
            "재시도 횟수를 다 썼습니다: 웹훅이 HTTP 503로 응답했습니다",
            coreMessage(code).text(strings(korean)),
        )
    }

    /** docs/07 §5: what an older build wrote is a sentence already, and no language changes it. */
    @Test
    fun `prose from an older build is shown as it stands`() {
        val message = coreMessage("the upload failed")

        assertEquals(UiMessage.Text("the upload failed"), message)
        assertEquals("the upload failed", message.text(strings(korean)))
    }
}
