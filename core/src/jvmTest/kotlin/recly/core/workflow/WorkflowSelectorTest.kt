package recly.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.model.Workflow
import recly.core.testing.testDocument
import recly.core.testing.testWorkflow

class WorkflowSelectorTest {
    private fun workflow(id: String): Workflow = testWorkflow(id = id)

    private val chosen = workflow("01AAAAAAAAAAAAAAAAAAAAAAAA")
    private val deviceDefault = workflow("01BBBBBBBBBBBBBBBBBBBBBBBB")
    private val other = workflow("01CCCCCCCCCCCCCCCCCCCCCCCC")
    private val gone = "01ZZZZZZZZZZZZZZZZZZZZZZZZ"

    @Test
    fun rule1TheRecordTimePickWins() {
        val doc = testDocument(chosen, deviceDefault, other)
        assertEquals(chosen.id, WorkflowSelector.select(doc, chosen.id, deviceDefault.id)?.id)
    }

    @Test
    fun rule2ThisDevicesDefaultWhenNothingWasPicked() {
        val doc = testDocument(chosen, deviceDefault, other)
        assertEquals(deviceDefault.id, WorkflowSelector.select(doc, null, deviceDefault.id)?.id)
    }

    @Test
    fun rule3NothingWhenNeitherResolves() {
        assertNull(WorkflowSelector.select(testDocument(chosen, other), null, null))
        assertNull(WorkflowSelector.select(testDocument(), null, null))
    }

    /** A pick that no longer exists falls through; it does not drop the recording. */
    @Test
    fun anUnknownChosenIdFallsThroughToTheDeviceDefault() {
        val doc = testDocument(deviceDefault, other)
        assertEquals(deviceDefault.id, WorkflowSelector.select(doc, gone, deviceDefault.id)?.id)
    }

    /**
     * ADR-016: the pointer is local and the document is shared, so another device deleting the
     * workflow it points at leaves it stale. That selects nothing — the shells ask for a new pick
     * rather than running something the user never chose.
     */
    @Test
    fun aStaleDeviceDefaultSelectsNothing() {
        val doc = testDocument(chosen, other)
        assertNull(WorkflowSelector.select(doc, null, gone))
        assertNull(WorkflowSelector.select(doc, gone, gone))
        // …and a pick that does resolve is unaffected by it.
        assertEquals(chosen.id, WorkflowSelector.select(doc, chosen.id, gone)?.id)
    }
}
