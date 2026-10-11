package app.recly.windows.ui.theme

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 2026-10-11 (N3, W33): a word in a monospace style — a badge, a node's value — written in Hangul, Kana or Han takes
 * the sans face, so Korean is not set as `업로드  중`; anything else stays monospace.
 */
class WordFaceTest {
    private val style = monoType(1f).small

    @Test
    fun `korean, japanese and chinese words take the sans face`() {
        // Korean as the table hands it over, with a WORD JOINER between the syllables.
        assertEquals(FontFamily.Default, style.forWord("업⁠로⁠드 중").fontFamily)
        assertEquals(FontFamily.Default, style.forWord("Drive 연결 대기").fontFamily)
        assertEquals(FontFamily.Default, style.forWord("アップロード中").fontFamily)
        assertEquals(FontFamily.Default, style.forWord("上传中").fontFamily)
        // The size and line height are the badge's own, so a badge is as tall in either face.
        assertEquals(style.fontSize, style.forWord("완료").fontSize)
        assertEquals(style.lineHeight, style.forWord("완료").lineHeight)
    }

    @Test
    fun `other words stay monospace`() {
        assertEquals(FontFamily.Monospace, style.forWord("Wird hochgeladen").fontFamily)
        assertEquals(FontFamily.Monospace, style.forWord("Загрузка").fontFamily)
        assertEquals(FontFamily.Monospace, style.forWord("قيد الرفع").fontFamily)
    }
}
