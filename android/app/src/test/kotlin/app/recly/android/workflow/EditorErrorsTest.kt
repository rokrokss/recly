@file:OptIn(ExperimentalTime::class)

package app.recly.android.workflow

import app.recly.android.R
import app.recly.android.core.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.model.WorkflowsDocument
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

/**
 * Deliverable 5: `SaveResult.Invalid` → fields. The inputs are not hand-written strings but what the
 * parser actually says about a broken editor state, so the mapping cannot drift from docs/02
 * silently — if the parser rephrases a rule, one of these fails.
 */
class EditorErrorsTest {

    private val id = "01J9ABCDEF0123456789ABCDEF"
    private val now = Instant.parse("2026-08-27T09:00:00Z")

    private val good = WorkflowEdit(
        id = id,
        name = "Meeting",
        minDurationSec = "0",
        steps = listOf(StepEdit.Drive(id = "upload"), StepEdit.Hook(id = "hook", url = "https://x.test")),
    )

    /** What the editor's Save does: build the document, run it through the parser, sort the errors. */
    private fun errorsOf(edit: WorkflowEdit): EditorErrors {
        val document = WorkflowsDocument(
            schema = 2,
            revision = 1,
            updatedAt = "2026-08-01T00:00:00.000Z",
            updatedBy = "device-a",
            workflows = emptyList(),
        ).with(edit, now)
        val parsed = WorkflowParser.parse(WorkflowParser.serialize(document))
        val invalid = parsed as? ParseResult.Invalid ?: fail("expected Invalid, was $parsed")
        return EditorErrors.of(invalid.errors, edit.id)
    }

    @Test
    fun `a valid workflow produces no errors at all`() {
        val document = WorkflowsDocument(2, 1, "2026-08-01T00:00:00.000Z", "device-a", emptyList())
            .with(good, now)

        assertTrue(WorkflowParser.parse(WorkflowParser.serialize(document)) is ParseResult.Ok)
        assertTrue(EditorErrors.of(emptyList(), id).isEmpty)
    }

    @Test
    fun `an empty name lands on the name field`() {
        val errors = errorsOf(good.copy(name = ""))

        assertNotNull(errors.name)
        assertTrue(errors.banner.isEmpty(), "nothing spills into the banner: ${errors.banner}")
    }

    @Test
    fun `a minimum length that is not a number lands on its field`() {
        val errors = errorsOf(good.copy(minDurationSec = "-"))

        assertNotNull(errors.minDuration)
    }

    @Test
    fun `step errors are keyed by step id and by field`() {
        val errors = errorsOf(
            good.copy(
                steps = listOf(
                    StepEdit.Drive(id = "upload", folder = "recly/{{year}}"),
                    StepEdit.Hook(
                        id = "hook",
                        url = "ftp://example.com",
                        secretRef = "Hook Main",
                        retry = RetryEdit(maxAttempts = "99"),
                    ),
                ),
            ),
        )

        val drive = errors.steps.getValue("upload")
        assertEquals(UiMessage.Res(R.string.error_folder_variable, listOf("{{year}}")), drive.folder)

        val hook = errors.steps.getValue("hook")
        assertNotNull(hook.url)
        assertNotNull(hook.secretRef)
        assertNotNull(hook.maxAttempts)
        assertTrue(errors.banner.isEmpty(), "every one of these has a field: ${errors.banner}")
    }

    @Test
    fun `a workflow with no steps is a banner, not a field`() {
        val errors = errorsOf(good.copy(steps = emptyList()))

        assertEquals(listOf(UiMessage.Res(R.string.error_steps_count)), errors.banner)
    }

    @Test
    fun `a complaint about another workflow stays in the banner`() {
        val errors = EditorErrors.of(listOf("workflow 01OTHER: name must be 1..40 characters, was 0"), id)

        assertNull(errors.name)
        assertEquals(1, errors.banner.size)
    }

    @Test
    fun `an unrecognised complaint is shown rather than dropped`() {
        val errors = EditorErrors.of(listOf("workflow $id: something new the parser says"), id)

        assertEquals(listOf(UiMessage.Text("something new the parser says")), errors.banner)
    }

    @Test
    fun `a step complaint the mapping does not know still reaches that step`() {
        val errors = EditorErrors.of(listOf("workflow $id: step 'upload' is haunted"), id)

        assertEquals(listOf(UiMessage.Text("step 'upload' is haunted")), errors.steps.getValue("upload").other)
    }

}
