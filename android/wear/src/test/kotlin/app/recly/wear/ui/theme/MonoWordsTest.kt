package app.recly.wear.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** N3 (UX decisions of 2026-10-11): the status line's CJK words go to the sans, its times stay monospace. */
class MonoWordsTest {

    private fun sans(text: String): List<String> = cjkRuns(text).map { text.substring(it) }

    @Test
    fun `a Korean line is one stretch, a time beside it stays monospace`() {
        assertEquals(listOf("전송 대기"), sans("전송 대기"))
        assertEquals(listOf("하이라이트"), sans("하이라이트 · 00:12"))
        assertEquals(listOf("送信待ち"), sans("送信待ち"))
        assertEquals(emptyList(), sans("Waiting to send"))
    }
}
