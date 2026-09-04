package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR-016: the delete confirmation is about one row, and the row is what the list says *now* — the
 * device pointer the warning is about may move while the question is on screen.
 *
 * The [WorkflowsViewModel] around this cannot be built off-device (`AndroidViewModel` + the real
 * graph — the same reason `WorkflowMutatorTest` tests the mutator), so the rule is tested where the
 * ViewModel keeps it: every rebuild of the list goes through [showing]. The desktop twins are
 * Apple's `WorkflowsModel.show` and the PC's, and this is the same regression they hold.
 */
class WorkflowDeleteConfirmTest {

    /** docs/02 ULIDs, and docs/05's fixed seed ids: an import brings the same id back. */
    private val memo = "01J9ABCDEF0123456789ABCDEF"
    private val meeting = "01J9ABCDEF0123456789ABCDEG"

    /**
     * A question about a row that is gone is over for good: an import can bring the same fixed id
     * back, and a stale confirmation re-arming against the new workflow would be a delete nobody
     * asked of it.
     */
    @Test
    fun `a confirmation whose row disappeared does not re-arm when the id comes back`() {
        val listed = WorkflowsUiState(loading = false).showing(listOf(item(memo), item(meeting)))
        val asked = listed.copy(confirmDelete = listed.items.single { it.id == meeting })

        // An import replaces the document without the meeting workflow.
        val gone = asked.showing(listOf(item(memo)))
        assertNull(gone.confirmDelete, "the row is gone, so the question is too")

        // The same fixed id returns with the next import: nothing may reopen the old question.
        val back = gone.showing(listOf(item(memo), item(meeting)))
        assertNull(back.confirmDelete, "a returned id is a new workflow, not an old answer")
    }

    /**
     * While the row does exist the question follows it: ADR-016 lets this device's pointer move
     * under the dialog, and that pointer is exactly what the dialog's warning is about.
     */
    @Test
    fun `a confirmation is the row as it is now`() {
        val listed = WorkflowsUiState(loading = false).showing(listOf(item(memo), item(meeting)))
        val asked = listed.copy(confirmDelete = listed.items.single { it.id == meeting })

        val marked = asked.showing(listOf(item(memo), item(meeting, isDeviceDefault = true)))

        assertEquals(true, marked.confirmDelete?.isDeviceDefault, "the warning is about the pointer")
    }

    private fun item(id: String, isDeviceDefault: Boolean = false) = WorkflowItem(
        id = id,
        name = id,
        isDeviceDefault = isDeviceDefault,
        steps = emptyList(),
        missingSecrets = emptyList(),
    )
}
