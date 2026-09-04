@file:OptIn(ExperimentalTime::class)

package app.recly.android.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.model.OnError
import recly.core.model.Retry
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

/** Deliverable 5: the editor state ↔ `WorkflowsDocument` mapping. */
class WorkflowEditTest {

    private val now = Instant.parse("2026-08-27T09:00:00Z")
    private val earlier = "2026-08-01T00:00:00.000Z"

    private val meeting = Workflow(
        id = "01J9ABCDEF0123456789ABCDEF",
        name = "회의",
        updatedAt = earlier,
        minDurationSec = 30,
        steps = listOf(
            Step.DriveUpload(id = "upload", folder = "recly/{{yyyy}}"),
            Step.Webhook(
                id = "hook",
                onError = OnError.CONTINUE,
                retry = Retry(maxAttempts = 3, initialDelaySec = 10, maxDelaySec = 60),
                url = "https://example.com/rec",
                secretRef = "hook_main",
            ),
        ),
    )

    private val memo = meeting.copy(
        id = "01J9ABCDEF0123456789ABCDEG",
        name = "메모",
        steps = listOf(Step.DriveUpload(id = "upload")),
    )

    private fun document(vararg workflows: Workflow) = WorkflowsDocument(
        schema = 2,
        revision = 4,
        updatedAt = earlier,
        updatedBy = "device-a",
        workflows = workflows.toList(),
    )

    @Test
    fun `an untouched workflow survives the round trip`() {
        assertEquals(meeting, meeting.toEdit().toWorkflow(meeting.updatedAt))
    }

    @Test
    fun `saving keeps the id and stamps only the edited workflow`() {
        val doc = document(meeting, memo)

        val saved = doc.with(meeting.toEdit().copy(name = "회의 2"), now)

        val edited = saved.workflows.first { it.id == meeting.id }
        assertEquals(meeting.id, edited.id, "the id is identity, not a name")
        assertEquals("회의 2", edited.name)
        assertEquals("2026-08-27T09:00:00.000Z", edited.updatedAt)
        // docs/05 merges per workflow: restamping a workflow this edit never touched would let this
        // device win a last-write-wins race it never entered.
        assertEquals(earlier, saved.workflows.first { it.id == memo.id }.updatedAt)
        assertEquals(doc.revision, saved.revision, "the push stamps the document, not the editor")
        assertEquals(doc.updatedAt, saved.updatedAt)
    }

    @Test
    fun `a workflow the document does not have is appended`() {
        val fresh = WorkflowEdit(
            id = "01J9ABCDEF0123456789ABCDEH",
            name = "새로",
            minDurationSec = "0",
            steps = listOf(StepEdit.Drive(id = "upload")),
        )

        val saved = document(meeting).with(fresh, now)

        assertEquals(listOf(meeting.id, fresh.id), saved.workflows.map { it.id })
        assertEquals("2026-08-27T09:00:00.000Z", saved.workflows.last().updatedAt)
    }

    @Test
    fun `the mapped document is what the parser accepts`() {
        val saved = document(meeting).with(meeting.toEdit().copy(name = " 여백 "), now)

        val parsed = WorkflowParser.parse(WorkflowParser.serialize(saved))

        val ok = parsed as? ParseResult.Ok ?: fail("expected Ok, was $parsed")
        assertEquals("여백", ok.document.workflows.first().name, "the name is trimmed on the way in")
    }

    @Test
    fun `text that is not a number becomes a value the parser rejects`() {
        val broken = meeting.toEdit().copy(
            minDurationSec = "삼십",
            steps = listOf(StepEdit.Hook(id = "hook", retry = RetryEdit(maxAttempts = ""), url = "https://x.test")),
        )

        val saved = document(meeting).with(broken, now)
        val parsed = WorkflowParser.parse(WorkflowParser.serialize(saved))

        val invalid = parsed as? ParseResult.Invalid ?: fail("expected Invalid, was $parsed")
        assertTrue(
            invalid.errors.any { it.contains("minDurationSec") } &&
                invalid.errors.any { it.contains("retry.maxAttempts") },
            "both bad fields are reported: ${invalid.errors}",
        )
    }

    @Test
    fun `a blank minimum length is zero, not an error`() {
        val saved = document(meeting).with(meeting.toEdit().copy(minDurationSec = ""), now)

        assertEquals(0, saved.workflows.first().minDurationSec)
    }

    @Test
    fun `deleting removes only that workflow`() {
        val left = document(meeting, memo).without(meeting.id)

        assertEquals(listOf(memo.id), left.workflows.map { it.id })
    }

    @Test
    fun `a blank secretRef is no secret at all`() {
        val edit = meeting.toEdit().copy(
            steps = listOf(StepEdit.Hook(id = "hook", url = "https://x.test", secretRef = "  ")),
        )

        val step = edit.toWorkflow(earlier).steps.first() as Step.Webhook

        assertNull(step.secretRef, "an empty pick is 'no signature', not a name of ''")
    }

    /** docs/08: a provider that never reads an invoke URL is one whose form hides the field. */
    @Test
    fun `switching a transcribe step to a provider with no invoke URL drops it`() {
        val edit = StepEdit.Transcribe(
            id = "stt",
            provider = "rtzr",
            secretRef = "rtzr_key",
            invokeUrl = "https://clovaspeech-gw.example.com/external/v1/1/a",
        )

        val step = edit.toStep() as Step.Transcribe

        assertNull(step.invokeUrl, "a URL no form shows is a validation error nobody can fix")
    }

    /** docs/08: a provider that *may* be addressed by one still shows the field, so it is kept. */
    @Test
    fun `a provider that only accepts an invoke URL keeps the one that was typed`() {
        val edit = StepEdit.Transcribe(
            id = "stt",
            provider = "openai",
            secretRef = "openai_key",
            invokeUrl = "https://llm.example.com/v1",
        )

        val step = edit.toStep() as Step.Transcribe

        assertEquals("https://llm.example.com/v1", step.invokeUrl)
    }

    /**
     * The editor holds every optional string as text, so the risk is not that a save fails — it is
     * that opening a step and saving it back quietly changes it. Whitespace nobody typed here is
     * whitespace the parser accepts (docs/02 "검증 규칙" only refuses an empty one).
     */
    @Test
    fun `an untouched model survives open and save byte for byte`() {
        val steps = listOf(
            Step.DriveUpload(id = "upload"),
            Step.Transcribe(id = "stt", provider = "rtzr", secretRef = "rtzr_key", model = " x "),
        )

        val saved = steps.map { it.toEdit().toStep() }

        assertEquals(steps, saved, "every step came back as it went in")
    }

    @Test
    fun `an empty model means the provider's default, not a model named nothing`() {
        val step = StepEdit.Transcribe(id = "stt", secretRef = "rtzr_key").toStep() as Step.Transcribe

        assertNull(step.model)
    }

    @Test
    fun `a speaker count that is not a number becomes one the parser rejects`() {
        val edit = meeting.toEdit().copy(
            steps = listOf(
                StepEdit.Drive(id = "upload"),
                StepEdit.Transcribe(id = "stt", secretRef = "rtzr_key", speakersMin = "둘"),
            ),
        )

        val saved = document(meeting).with(edit, now)
        val parsed = WorkflowParser.parse(WorkflowParser.serialize(saved))

        val invalid = parsed as? ParseResult.Invalid ?: fail("expected Invalid, was $parsed")
        assertTrue(invalid.errors.any { it.contains("speakers") }, "the count is reported: ${invalid.errors}")
    }

    /** docs/08: a new transcribe step arrives on the provider table's first row. */
    @Test
    fun `a new transcribe step is prefilled with the default provider`() {
        val step = StepKind.TRANSCRIBE.newStep(taken = emptySet())

        val transcribe = step as StepEdit.Transcribe
        assertEquals("stt", transcribe.id)
        assertEquals(DEFAULT_STT_PROVIDER, transcribe.provider)
    }

    @Test
    fun `a new step id never collides with one already in the workflow`() {
        assertEquals("hook", nextStepId("hook", emptySet()))
        assertEquals("hook2", nextStepId("hook", setOf("hook")))
        assertEquals("hook3", nextStepId("hook", setOf("hook", "hook2")))
    }

    /** One Drive upload per workflow: the menu offers a second one greyed out, a webhook always. */
    @Test
    fun `a second drive upload is not offered`() {
        val none = emptyList<StepEdit>()
        val uploaded = listOf(StepKind.DRIVE.newStep(emptySet()))

        assertTrue(StepKind.DRIVE.canAdd(none))
        assertFalse(StepKind.DRIVE.canAdd(uploaded))
        assertTrue(StepKind.HOOK.canAdd(uploaded))
        assertTrue(StepKind.TRANSCRIBE.canAdd(uploaded))
        assertTrue(StepKind.HOOK.canAdd(uploaded + StepKind.HOOK.newStep(setOf("upload"))))
    }
}
