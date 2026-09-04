package app.recly.wear.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The other end of `WearJson.workflows` in :android:app, whose own test pins the exact bytes. These
 * are the same shapes read back — and the malformed ones, because a watch that cannot parse the
 * summary still has to be able to record.
 */
class WearWorkflowsTest {

    @Test
    fun `the phone's summary comes back as workflows`() {
        val parsed = WearWorkflows.parse(
            """{"workflows":[{"id":"a","name":"회의"},{"id":"b","name":"메모"}]}""",
        )

        assertEquals(listOf("a", "b"), parsed.map { it.id })
        assertEquals(listOf("회의", "메모"), parsed.map { it.name })
    }

    /**
     * ADR-016 deleted `enabled` and `sources`; an older phone still sends them, and the picker
     * offering nothing until that phone updates would be the worse of the two failures.
     */
    @Test
    fun `a summary from before the fields were dropped is still a list of workflows`() {
        val parsed = WearWorkflows.parse(
            """{"workflows":[""" +
                """{"id":"a","name":"회의","enabled":true,"sources":["watch","phone"]},""" +
                """{"id":"b","name":"메모","enabled":false,"sources":["desktop"]}""" +
                """]}""",
        )

        assertEquals(listOf("a", "b"), parsed.map { it.id })
    }

    @Test
    fun `an empty list is an empty list`() {
        assertEquals(emptyList(), WearWorkflows.parse("""{"workflows":[]}"""))
    }

    @Test
    fun `a workflow with no id or no name is not a workflow`() {
        val parsed = WearWorkflows.parse(
            """{"workflows":[{"name":"이름만"},{"id":"b"},{"id":"c","name":"온전함"}]}""",
        )

        assertEquals(listOf("c"), parsed.map { it.id })
    }

    @Test
    fun `nothing readable is an empty picker, never an exception`() {
        assertEquals(emptyList(), WearWorkflows.parse(null as String?))
        assertEquals(emptyList(), WearWorkflows.parse(""))
        assertEquals(emptyList(), WearWorkflows.parse("not json"))
        assertEquals(emptyList(), WearWorkflows.parse("""{"workflows":"nope"}"""))
        assertEquals(emptyList(), WearWorkflows.parse("""{}"""))
        assertEquals(emptyList(), WearWorkflows.parse(null as ByteArray?))
    }

    @Test
    fun `bytes off the data item are read as UTF-8`() {
        val parsed = WearWorkflows.parse("""{"workflows":[{"id":"a","name":"회의"}]}""".encodeToByteArray())

        assertEquals("회의", parsed.single().name)
    }
}
