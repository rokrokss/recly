package app.recly.android.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * N3 (UX decisions of 2026-10-11): which stretches of a monospace word are drawn in the sans — Korean,
 * Japanese and Chinese words with the spaces between them, and nothing else.
 */
class MonoWordsTest {

    private fun sans(text: String): List<String> = cjkRuns(text).map { text.substring(it) }

    @Test
    fun `a Korean state is one stretch, spaces between its words included`() {
        assertEquals(listOf("전사 안 함"), sans("전사 안 함"))
        assertEquals(listOf("재시도 대기"), sans("재시도 대기"))
    }

    @Test
    fun `counts, times and Latin stay monospace`() {
        assertEquals(listOf("대기", "실패"), sans("8 · 대기 2 · 실패 1"))
        assertEquals(listOf("녹음"), sans("녹음 · 00:12"))
        assertEquals(listOf("연결 대기"), sans("Drive 연결 대기"))
        assertEquals(emptyList(), sans("Uploading"))
        assertEquals(emptyList(), sans("2 · 1 failed"))
    }

    @Test
    fun `Kana and Han, long vowel marks included`() {
        assertEquals(listOf("アップロード中"), sans("アップロード中"))
        assertEquals(listOf("待ち"), sans("Drive待ち"))
        assertEquals(listOf("转写中"), sans("转写中"))
    }
}
