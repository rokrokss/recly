package app.recly.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import recly.core.message.CoreMessage

/**
 * docs/07 §5: the core writes keys and this app is the only thing that turns them into words. A key
 * with no string of its own would reach a screen as `MISSING_SECRET:webhook_secret`, so the whole
 * enum is checked rather than the handful this app happens to produce today.
 */
class CoreMessagesTest {

    @Test
    fun `every core message has a string of its own`() {
        val seen = mutableMapOf<Int, CoreMessage>()
        CoreMessage.entries.forEach { message ->
            val id = CoreMessages.resourceOf(message)
            assertNotEquals(0, id, "$message has no string")
            val clash = seen.put(id, message)
            assertTrue(clash == null, "$message and $clash share one string")
        }
        assertEquals(CoreMessage.entries.size, seen.size)
    }

    /** The argument-taking keys are exactly the ones whose sentence has a placeholder in it. */
    @Test
    fun `the keys that take an argument are the ones that carry one`() {
        val withArgument = CoreMessage.entries.filter { CoreMessages.takesArgument(it) }.toSet()

        assertEquals(
            setOf(
                CoreMessage.MISSING_SECRET,
                CoreMessage.INVALID_SECRET,
                CoreMessage.WEBHOOK_HTTP,
                CoreMessage.FOLDER_TEMPLATE,
                CoreMessage.RETRY_BUDGET_SPENT,
                CoreMessage.NO_RUNNER,
                CoreMessage.STEP_MISSING,
                CoreMessage.UNSUPPORTED_STEP,
                CoreMessage.STEP_FAILED,
                CoreMessage.UNSUPPORTED_SCHEMA,
            ),
            withArgument,
        )
    }
}
