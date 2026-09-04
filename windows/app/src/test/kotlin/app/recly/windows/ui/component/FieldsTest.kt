package app.recly.windows.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * docs/09 "접근성": a [BlueprintTextField] names itself.
 *
 * The label is a `Text` node above the box rather than something inside the field (which is what
 * made it stop animating, see the component's own note), and Compose Desktop has no `labelledBy` to
 * tie the two nodes together — so a screen reader landing on the field would find an edit box with
 * nothing to call it. This is the part of the answer that can be asserted without a display: what
 * the editable node says about itself. That the node keeps its *editable* semantics is `BasicTextField`'s
 * own doing — the modifier here is merged into that node rather than replacing it — and asserting it
 * would need `compose.desktop.uiTestJUnit4` and a window, which this module does not have.
 */
class FieldsTest {

    /** Every field has a label: it is a required parameter, so the compiler asks for all eleven. */
    @Test
    fun `the field carries the label the box above it is written with`() {
        assertEquals("Model", fieldSemantics("Model", hint = null).description)
    }

    /**
     * The hint is what the field means, or what an empty one falls back to — a *state*, announced
     * after the value, and not a second name for the field.
     */
    @Test
    fun `the hint under the box is the field's state`() {
        val spoken = fieldSemantics("Model", "Blank runs the workflow's own default")

        assertEquals("Model", spoken.description)
        assertEquals("Blank runs the workflow's own default", spoken.state)
    }

    /** A field with nothing under it has no state to announce, and neither has a blank one. */
    @Test
    fun `no hint is no state at all`() {
        assertNull(fieldSemantics("URL", hint = null).state)
        assertNull(fieldSemantics("URL", hint = "   ").state)
    }
}
