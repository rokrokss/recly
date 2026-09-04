package app.recly.windows.ui.component

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 5 · §유동 타이포: the answers to a dialog sit in a row while they fit and stack
 * when they do not — and what decides it is the language and the type scale rather than the width of
 * the card. "Cancel · Delete" fits at any size; "취소 · 연결 해제" at a large font scale does not, and
 * a confirm button clipped off the card is a question nobody can answer.
 */
class BlueprintDialogTest {

    @Test
    fun `two answers that fit stay in a row`() {
        assertFalse(stackActions(available = 400, widths = listOf(100, 140), spacing = 8))
    }

    @Test
    fun `answers wider than the card stack`() {
        assertTrue(stackActions(available = 400, widths = listOf(220, 240), spacing = 8))
    }

    /** The gaps count: two answers that fit edge to edge do not fit with a gap between them. */
    @Test
    fun `the gap between them is part of the row`() {
        assertFalse(stackActions(available = 400, widths = listOf(200, 200), spacing = 0))
        assertTrue(stackActions(available = 400, widths = listOf(200, 200), spacing = 8))
    }

    @Test
    fun `one answer never stacks unless it is itself too wide`() {
        assertFalse(stackActions(available = 400, widths = listOf(400), spacing = 8))
        assertTrue(stackActions(available = 400, widths = listOf(401), spacing = 8))
        assertFalse(stackActions(available = 400, widths = emptyList(), spacing = 8))
    }
}
