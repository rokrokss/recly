package app.recly.windows

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 6, the rule the Mac's popover was given in e9838fb: the tray popup goes away on a
 * click in another application, and stays where it is on a click in one of this app's own windows —
 * Details, Settings, Workflows, or a dialog it raised itself. Before it, the first click in the window
 * the popup had just opened took the popup with it.
 *
 * The decision is [popupClosesOnFocusLoss]'s and the windows are compared by identity alone, so a
 * plain object stands in for one here: `java.awt.Window` cannot be constructed without a display.
 */
class PopupFocusTest {

    private val popup = Any()

    /** Focus gone from this JVM altogether: another application has it, and that is "elsewhere". */
    @Test
    fun `focus that left the application closes the popup`() {
        assertTrue(popupClosesOnFocusLoss(active = null, popup = popup))
    }

    /** The regression: the window the popup opened is not another application. */
    @Test
    fun `focus that went to one of this app's own windows keeps it open`() {
        assertFalse(popupClosesOnFocusLoss(active = Any(), popup = popup))
    }

    /**
     * And the popup itself is not one of those: a handoff that ends with the popup still the active
     * window but no longer focused is a focus that went nowhere, which closes it.
     */
    @Test
    fun `the popup being the active window is not another window`() {
        assertTrue(popupClosesOnFocusLoss(active = popup, popup = popup))
    }
}
