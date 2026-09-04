package recly.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import recly.core.db.RecDatabase
import recly.core.testing.inMemoryDatabase
import recly.core.testing.inMemoryDriver
import recly.core.testing.testDeps
import recly.core.testing.testWorkflow
import recly.core.workflow.WorkflowParser

/**
 * ADR-016 "이 기기의 기본 워크플로우": local, never exported, and it outlives everything — a
 * disconnect included (docs/03).
 */
class DeviceDefaultTest {

    @Test
    fun `the pointer survives a restart`() = runBlocking {
        val driver = inMemoryDriver()
        val deps = testDeps()

        DeviceDefaultStore(RecDatabase(driver), deps).write(WF_A)

        // A fresh store over the same database — what a process restart looks like.
        assertEquals(WF_A, DeviceDefaultStore(RecDatabase(driver), deps).read())
    }

    @Test
    fun `writing the pointer emits it, and null unsets it`() = runBlocking {
        val store = DeviceDefaultStore(inMemoryDatabase(), testDeps())

        assertNull(store.read())
        store.write(WF_A)
        assertEquals(WF_A, store.observe().first())
        store.write(WF_B)
        assertEquals(WF_B, store.read())
        store.write(null)
        assertNull(store.read())
    }

    /**
     * The startup race: a background path's `current()` plants the starters before the shell's own
     * `seed()` runs. The guess is owed either way — the pointer is written whenever there is none,
     * not only by the call that happened to insert the document.
     */
    @Test
    fun `the preferred default is applied whichever path seeded the document`() = runBlocking {
        val h = WorkflowHarness()

        h.workflows.current()
        assertNull(h.workflows.deviceDefault(), "current() seeds without guessing")

        h.workflows.seed(WorkflowRepository.MEMO_ID)

        assertEquals(WorkflowRepository.MEMO_ID, h.workflows.deviceDefault())
    }

    /** The two conditional writes the races go through: seeding never moves a choice, and a delete
     * only clears the pointer it actually observed. */
    @Test
    fun `writeIfNull respects a choice and clearIf clears only the expected id`() = runBlocking {
        val store = DeviceDefaultStore(inMemoryDatabase(), testDeps())

        store.writeIfNull(WF_A)
        assertEquals(WF_A, store.read(), "null pointer takes the seed's guess")
        store.writeIfNull(WF_B)
        assertEquals(WF_A, store.read(), "a set pointer is a choice, and the seed never moves one")

        store.clearIf(WF_B)
        assertEquals(WF_A, store.read(), "clearing what was not observed leaves the choice alone")
        store.clearIf(WF_A)
        assertNull(store.read())
    }

    /** Decision: the device that puts the starters there points its own default at one of them. */
    @Test
    fun `the seeding device points its default at the starter it asked for`() = runBlocking {
        val h = WorkflowHarness()

        val seeded = h.workflows.seed(WorkflowRepository.MEMO_ID)

        assertEquals(WorkflowRepository.MEMO_ID, h.workflows.deviceDefault())
        assertTrue(h.workflows.isDeviceDefault(WorkflowRepository.MEMO_ID))
        assertFalse(h.workflows.isDeviceDefault(WF_A))
        assertEquals(listOf("Memo"), seeded.workflows.map { it.name })
        // Nothing about the pointer is in the document: it is this device's, and an export of the
        // document carries no pick to another device.
        assertFalse(WorkflowParser.serialize(seeded).contains("default"))
    }

    /** A user who has already chosen is not overruled by a later seed call. */
    @Test
    fun `seeding never moves a pointer that is already set`() = runBlocking {
        val h = WorkflowHarness()
        h.workflows.setDeviceDefault(WF_A)

        h.workflows.seed(WorkflowRepository.MEMO_ID)

        assertEquals(WF_A, h.workflows.deviceDefault())
    }

    /**
     * Deleting this device's default is allowed (ADR-016 supersedes the undeletable rule) and takes
     * the pointer with it.
     */
    @Test
    fun `deleting the default clears the pointer, deleting something else does not`() = runBlocking {
        val h = WorkflowHarness()
        h.workflows.seed(WorkflowRepository.MEMO_ID)
        val seeded = h.workflows.current()
        val document = seeded.copy(workflows = seeded.workflows + testWorkflow(id = WF_A, name = "Standup"))
        assertTrue(h.workflows.save(document) is SaveResult.Saved)

        val kept = document.copy(workflows = document.workflows.filterNot { it.id == WF_A })
        assertTrue(h.workflows.save(kept) is SaveResult.Saved)
        assertEquals(WorkflowRepository.MEMO_ID, h.workflows.deviceDefault(), "a delete of something else")

        assertTrue(h.workflows.save(kept.copy(workflows = emptyList())) is SaveResult.Saved)
        assertNull(h.workflows.deviceDefault())
    }
}
