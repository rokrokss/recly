package app.recly.android.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The widths [FillRow] hands out, which are the whole of what it decides. */
class FillRowTest {

    /**
     * The Korean `시스템 기본` / `밝게` / `어둡게` (System default / Light / Dark) in a phone's line:
     * short enough for an even split, so they get one.
     */
    @Test
    fun `chips that fit an even split share the line equally`() {
        assertEquals(listOf(listOf(120, 120, 120)), fillLines(listOf(100, 48, 60), width = 376, gap = 8))
    }

    /** `System default` is too long for a third of the line; every chip keeps its own width and more. */
    @Test
    fun `a label too long for an even split keeps its width and the rest is shared`() {
        val line = fillLines(listOf(150, 48, 48), width = 376, gap = 8).single()
        assertEquals(listOf(150 + 38, 48 + 38, 48 + 38), line)
        assertTrue(line.zip(listOf(150, 48, 48)).all { (given, natural) -> given >= natural })
    }

    /** A line that cannot hold them all wraps, and each line fills on its own. */
    @Test
    fun `a line that does not hold them all wraps and every line fills`() {
        assertEquals(listOf(listOf(150, 150), listOf(308)), fillLines(listOf(140, 120, 90), width = 308, gap = 8))
    }

    /** The pixels an uneven division leaves go to the last chip, so the line ends on the edge. */
    @Test
    fun `every line ends on the edge`() {
        val line = fillLines(listOf(20, 20, 20), width = 100, gap = 8).single()
        assertEquals(100 - 2 * 8, line.sum())
        assertEquals(listOf(28, 28, 28), line)
        assertEquals(listOf(28, 28, 29), fillLines(listOf(20, 20, 20), width = 101, gap = 8).single())
    }

    /** A chip wider than the whole line gets the line, not more. */
    @Test
    fun `a chip wider than the line is held to it`() {
        assertEquals(listOf(listOf(200)), fillLines(listOf(260), width = 200, gap = 8))
    }
}
