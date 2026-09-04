package recly.core.sync

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.testing.testWorkflow
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

/**
 * docs/05: the document is this device's, and moving it is 내보내기/가져오기. The serialized document
 * *is* the file format, so the round trip has nothing of its own to get wrong — these are the tests
 * that say so, and that an import replaces rather than merges.
 */
class WorkflowRepositoryTest {

    @Test
    fun `export writes the stored document and import reads it back`() = runBlocking {
        val source = WorkflowHarness(deviceId = "device-a")
        source.workflows.seed(WorkflowRepository.MEMO_ID)
        val mine = source.workflows.current().let { document ->
            document.copy(workflows = document.workflows + testWorkflow(id = WF_A, name = "Standup"))
        }
        assertIs<SaveResult.Saved>(source.workflows.save(mine))

        val file = source.workflows.exportJson()

        // The other device has a starter of its own, and the import replaces it outright.
        val target = WorkflowHarness(deviceId = "device-b")
        target.workflows.seed(WorkflowRepository.MEMO_ID)
        assertEquals(ImportResult.Imported(2), target.workflows.importJson(file))

        assertEquals(
            listOf("Memo", "Standup"),
            target.workflows.current().workflows.map { it.name },
        )
        // What the device holds now is what the file said, workflow for workflow.
        assertEquals(
            source.workflows.current().workflows,
            target.workflows.current().workflows,
        )
    }

    /** The pointer is the device's own (ADR-016): it is not in the file and an import cannot move it. */
    @Test
    fun `an export carries no device default and an import does not move one`() = runBlocking {
        val source = WorkflowHarness()
        source.workflows.seed(WorkflowRepository.MEMO_ID)
        val mine = source.workflows.current().let { document ->
            document.copy(workflows = document.workflows + testWorkflow(id = WF_A, name = "Standup"))
        }
        assertIs<SaveResult.Saved>(source.workflows.save(mine))
        source.workflows.setDeviceDefault(WF_A)

        val file = source.workflows.exportJson()

        val target = WorkflowHarness(deviceId = "device-b")
        target.workflows.seed(WorkflowRepository.MEMO_ID)
        target.workflows.importJson(file)

        assertEquals(WorkflowRepository.MEMO_ID, target.workflows.deviceDefault())
    }

    /** …and an import that replaces the default workflow is a delete of it: the pointer goes with
     * it ([WorkflowRepository.save]) and the shells ask for a new pick. */
    @Test
    fun `an import that drops the default workflow clears the pointer`() = runBlocking {
        val h = WorkflowHarness()
        h.workflows.seed(WorkflowRepository.MEMO_ID)
        val only = h.workflows.current().let { it.copy(workflows = listOf(testWorkflow(id = WF_A, name = "Standup"))) }

        assertIs<ImportResult.Imported>(h.workflows.importJson(WorkflowParser.serialize(only)))

        assertNull(h.workflows.deviceDefault())
        assertEquals(listOf(WF_A), h.workflows.current().workflows.map { it.id })
    }

    /**
     * A file an older build exported migrates on the way in exactly as a stored copy would
     * (docs/05 "가져오기"), and what is stored afterwards is at the current schema.
     */
    @Test
    fun `a file written at an older schema migrates on import`() = runBlocking {
        val h = WorkflowHarness()

        assertEquals(ImportResult.Imported(2), h.workflows.importJson(LEGACY_SCHEMA_2))

        val stored = h.workflows.current()
        assertEquals(WorkflowParser.SCHEMA, stored.schema)
        assertEquals(listOf("회의", "메모"), stored.workflows.map { it.name })
        assertEquals(listOf(45, 0), stored.workflows.map { it.minDurationSec })
        // The dropped ADR-016 fields are gone from the bytes, not just from the model.
        val exported = h.workflows.exportJson()
        listOf("enabled", "isDefault", "trigger").forEach {
            assertTrue(!exported.contains("\"$it\""), "'$it' survived the import: $exported")
        }
    }

    @Test
    fun `an unreadable file changes nothing and comes back as the parser's errors`() = runBlocking {
        val h = WorkflowHarness()
        val before = h.workflows.seed(WorkflowRepository.MEMO_ID)

        val malformed = assertIs<ImportResult.Invalid>(h.workflows.importJson("{"))
        assertTrue(malformed.errors.isNotEmpty())

        val newer = assertIs<ImportResult.Invalid>(
            h.workflows.importJson(WorkflowParser.serialize(before).replaceFirst("\"schema\":3", "\"schema\":9")),
        )
        assertContains(newer.errors.single(), "schema 9")

        val invalid = assertIs<ImportResult.Invalid>(
            h.workflows.importJson(WorkflowParser.serialize(before).replaceFirst("\"Memo\"", "\"\"")),
        )
        assertTrue(invalid.errors.isNotEmpty())

        assertEquals(before.workflows, h.workflows.current().workflows, "nothing was written")
    }

    /** A save stamps the envelope, so an exported file says which device wrote it and when. */
    @Test
    fun `a save stamps the document envelope`() = runBlocking {
        val h = WorkflowHarness(deviceId = "device-a")
        val seeded = h.workflows.seed(WorkflowRepository.MEMO_ID)
        assertEquals(0, seeded.revision)

        val saved = assertIs<SaveResult.Saved>(h.workflows.save(seeded)).document

        assertEquals(1, saved.revision)
        assertEquals("device-a", saved.updatedBy)
        assertEquals("2026-08-26T01:00:00.000Z", saved.updatedAt)
    }

    @Test
    fun `an invalid document is refused and the stored one is untouched`() = runBlocking {
        val h = WorkflowHarness()
        val seeded = h.workflows.seed(WorkflowRepository.MEMO_ID)

        val result = h.workflows.save(seeded.copy(workflows = seeded.workflows.map { it.copy(name = "") }))

        assertIs<SaveResult.Invalid>(result)
        assertEquals(seeded.workflows, h.workflows.current().workflows)
    }

    /**
     * A stored copy this build cannot read is not an absent one: seeding over it would destroy the
     * user's bytes (docs/02). The caller still gets the starters to run with.
     */
    @Test
    fun `unreadable stored bytes are never seeded over`() = runBlocking {
        val h = WorkflowHarness()
        h.db.recQueries.syncSet(WorkflowStore.LOCAL_DOC, "{\"schema\":9}")

        assertEquals(listOf("Memo"), h.workflows.current().workflows.map { it.name })

        assertNull(h.store.read())
        assertEquals("{\"schema\":9}", h.db.recQueries.syncGet(WorkflowStore.LOCAL_DOC).executeAsOneOrNull())
    }

    @Test
    fun `the summary is the id and the name and nothing else`() = runBlocking {
        val h = WorkflowHarness()
        h.workflows.seed(WorkflowRepository.MEMO_ID)

        assertEquals(
            listOf(WorkflowSummary(WorkflowRepository.MEMO_ID, "Memo")),
            h.workflows.summary(),
        )
    }

    private companion object {
        /** A file a build before ADR-016 would have exported: `enabled`, `isDefault`, `trigger`. */
        val LEGACY_SCHEMA_2 = """
            {
              "schema": 2,
              "revision": 4,
              "updatedAt": "2026-08-26T01:00:00.000Z",
              "updatedBy": "7c1e4b2a",
              "workflows": [
                {
                  "id": "01J9ABCDEF0123456789ABCDEF",
                  "name": "회의",
                  "enabled": true,
                  "isDefault": true,
                  "updatedAt": "2026-08-26T01:00:00.000Z",
                  "trigger": { "sources": ["watch", "phone", "desktop"], "minDurationSec": 45 },
                  "steps": [{ "id": "up", "type": "drive.upload" }]
                },
                {
                  "id": "01J9ABCDEF0123456789ABCDEG",
                  "name": "메모",
                  "enabled": false,
                  "isDefault": false,
                  "updatedAt": "2026-08-20T09:30:00.000Z",
                  "trigger": { "sources": ["phone"] },
                  "steps": [{ "id": "up", "type": "drive.upload" }]
                }
              ]
            }
        """.trimIndent()
    }
}
