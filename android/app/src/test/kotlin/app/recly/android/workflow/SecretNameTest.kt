package app.recly.android.workflow

import app.recly.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import recly.core.model.Step
import recly.core.model.WorkflowsDocument
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

/** Deliverable 5: the docs/05 secret name rule, and that it is the same one `secretRef` obeys. */
class SecretNameTest {

    @Test
    fun `the rule accepts what docs 05 describes`() {
        listOf("h", "hook_main", "h0", "a" + "b".repeat(31)).forEach {
            assertNull(SecretName.problem(it), "'$it' should be a usable name")
        }
    }

    @Test
    fun `the rule rejects everything else`() {
        listOf("", "Hook", "0hook", "_hook", "hook-main", "hook main", "훅", "a" + "b".repeat(32))
            .forEach { assertNotNull(SecretName.problem(it), "'$it' should be rejected") }
    }

    @Test
    fun `a name already in the store is rejected with its own reason`() {
        assertEquals(R.string.secret_name_taken, SecretName.problem("hook_main", listOf("hook_main")))
        assertNull(SecretName.problem("hook_alt", listOf("hook_main")))
    }

    @Test
    fun `an empty name says so rather than quoting the pattern`() {
        assertEquals(R.string.secret_name_empty, SecretName.problem(""))
    }

    /**
     * The name is only useful if a step can point at it: the parser applies the same pattern to
     * `secretRef` (docs/02), so a name this accepts must be one a document can carry.
     */
    @Test
    fun `a name the rule accepts is a name a webhook step can reference`() {
        val name = "hook_main2"
        assertNull(SecretName.problem(name))

        val document = WorkflowsDocument(
            // The schema this build writes: a document at an older one comes back migrated, and
            // what is under test here is the name, not the migration.
            schema = WorkflowParser.SCHEMA,
            revision = 1,
            updatedAt = "2026-08-01T00:00:00.000Z",
            updatedBy = "device-a",
            workflows = listOf(
                WorkflowEdit(
                    id = "01J9ABCDEF0123456789ABCDEF",
                    name = "Meeting",
                    minDurationSec = "0",
                    steps = listOf(StepEdit.Hook(id = "hook", url = "https://x.test", secretRef = name)),
                ).toWorkflow("2026-08-01T00:00:00.000Z"),
            ),
        )

        val parsed = WorkflowParser.parse(WorkflowParser.serialize(document))
        assertEquals(ParseResult.Ok(document), parsed)
        assertEquals(name, (document.workflows.first().steps.first() as Step.Webhook).secretRef)
    }
}
