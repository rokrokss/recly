package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import recly.core.testing.FakeLogger
import recly.core.transcribe.RepetitionGuard.Action

/** docs/05 "Fixed processing settings": the loop guard the Android and Windows engines run on each decoded piece. */
class RepetitionGuardTest {
    private val korean = "회의를 시작하겠습니다 " + "감사합니다 ".repeat(15).trim()
    private val sentence = "Let me check the numbers again and get back to you. "

    @Test
    fun `a loop the decoder stopped by itself is cut back to its first copy, at a word boundary`() {
        // "니다" ends the opening sentence too, so the repeating tail starts two syllables before the copies do.
        val outcome = RepetitionGuard.check(korean, budgetHit = false)

        assertEquals(Action.TRIMMED, outcome.action)
        assertEquals("회의를 시작하겠습니다 감사합니다", outcome.text)
        assertEquals(85, outcome.units)
        assertEquals(70, outcome.removed)
    }

    @Test
    fun `a decode cut off by its budget needs fewer copies, and its last unfinished copy goes too`() {
        val looped = "So, " + sentence.repeat(4) + "Let me check"

        val trimmed = RepetitionGuard.check(looped, budgetHit = true)
        assertEquals(Action.TRIMMED, trimmed.action)
        assertEquals("So, Let me check the numbers again and get back to you.", trimmed.text)
        assertEquals(36, trimmed.removed)

        // Four copies of an 11-word sentence are not enough when the decoder stopped by itself (it needs 8).
        assertEquals(Action.KEPT, RepetitionGuard.check(looped, budgetHit = false).action)
        // Nor are two copies at the budget: it needs three, over 32 units.
        assertEquals(Action.KEPT, RepetitionGuard.check("So, " + sentence.repeat(2) + "Let me", budgetHit = true).action)
    }

    @Test
    fun `Chinese and Japanese loops are found per character, and cut where the punctuation is`() {
        val chinese = RepetitionGuard.check("我们开始吧。" + "谢谢大家。".repeat(17), budgetHit = false)
        assertEquals(Action.TRIMMED, chinese.action)
        assertEquals("我们开始吧。谢谢大家。", chinese.text)

        val japanese = RepetitionGuard.check("それでは始めます。" + "よろしくお願いします。".repeat(8), budgetHit = false)
        assertEquals(Action.TRIMMED, japanese.action)
        assertEquals("それでは始めます。よろしくお願いします。", japanese.text)
    }

    @Test
    fun `people repeating themselves are kept as they said it`() {
        listOf(
            "네 네 네", "no, no, no", "No, no, no, no, no!", "좋아요 좋아요", "정말 정말 정말 좋아요", "하하하하하",
            "very very very very good", "はいはいはい", "对对对，没错", "ครับ ครับ ครับ", "라".repeat(15),
            "감사합니다. 감사합니다. 감사합니다. 정말 감사합니다.",
        ).forEach { text ->
            val outcome = RepetitionGuard.check(text, budgetHit = false)
            assertEquals(Action.KEPT, outcome.action, text)
            assertEquals(text, outcome.text)
        }
    }

    @Test
    fun `a piece that is one unit over and over is dropped`() {
        listOf(
            "라".repeat(40),
            ("La " + "la ".repeat(17)).trim(),
            "아 ".repeat(20) + "어 " + "아 ".repeat(5),
        ).forEach { text ->
            val outcome = RepetitionGuard.check(text, budgetHit = false)
            assertEquals(Action.DROPPED, outcome.action, text)
            assertEquals("", outcome.text)
            assertEquals(outcome.units, outcome.removed)
        }
    }

    @Test
    fun `no word is removed for being that word`() {
        val text = "오늘 뉴스 구독자 이야기를 했습니다. 구독 얘기는 뉴스 끝나고 하죠."

        assertEquals(text, RepetitionGuard.check(text, budgetHit = true).text)
    }

    @Test
    fun `a short block needs many copies, a long one four`() {
        assertEquals(64, RepetitionGuard.repeatsNeeded(1))
        assertEquals(32, RepetitionGuard.repeatsNeeded(2))
        assertEquals(8, RepetitionGuard.repeatsNeeded(8))
        assertEquals(8, RepetitionGuard.repeatsNeeded(27))
        assertEquals(7, RepetitionGuard.repeatsNeeded(28))
        assertEquals(4, RepetitionGuard.repeatsNeeded(48))
        assertEquals(4, RepetitionGuard.repeatsNeeded(128))
    }

    @Test
    fun `the budget counts the tokens Qwen's opening left out`() {
        assertTrue(RepetitionGuard.budgetHit(tokens = 256, maxNewTokens = 256))
        assertTrue(RepetitionGuard.budgetHit(tokens = 240, maxNewTokens = 256))
        assertEquals(false, RepetitionGuard.budgetHit(tokens = 239, maxNewTokens = 256))
    }

    @Test
    fun `a change is logged with counts and never the text`() {
        val logger = FakeLogger()

        assertEquals("네 네 네", RepetitionGuard.apply("네 네 네", budgetHit = false, logger))
        assertTrue(logger.events.isEmpty())

        assertEquals("회의를 시작하겠습니다 감사합니다", RepetitionGuard.apply(korean, budgetHit = false, logger))
        assertEquals("", RepetitionGuard.apply("라".repeat(40), budgetHit = true, logger))
        assertEquals(
            listOf(
                mapOf("action" to "trimmed", "units" to 85, "removed" to 70, "budgetHit" to false),
                mapOf("action" to "dropped", "units" to 40, "removed" to 40, "budgetHit" to true),
            ),
            logger.fieldsOf(RepetitionGuard.EVENT),
        )
        assertEquals("local.repetition.removed", RepetitionGuard.EVENT)
    }
}
