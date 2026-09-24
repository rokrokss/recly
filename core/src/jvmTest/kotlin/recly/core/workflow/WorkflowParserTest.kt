package recly.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.model.Retry
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.model.Workflow

/** The docs/02 rules the fixed plan's settings are validated against. */
class WorkflowParserTest {
    private val base = Workflow(
        id = "01J9ABCDEF0123456789ABCDEF",
        name = "Recording",
        updatedAt = "2026-08-26T01:00:00.000Z",
        steps = listOf(upload(), transcribe()),
    )

    private fun ok(workflow: Workflow) {
        val errors = WorkflowParser.validate(workflow)
        assertTrue(errors.isEmpty(), "expected valid, was $errors")
    }

    private fun invalid(name: String, workflow: Workflow) {
        assertTrue(WorkflowParser.validate(workflow).isNotEmpty(), "$name: expected errors")
    }

    private fun invalidBecause(name: String, workflow: Workflow, reason: String) {
        val errors = WorkflowParser.validate(workflow)
        assertTrue(errors.any { reason in it }, "$name: expected an error mentioning '$reason', was $errors")
    }

    private fun withSteps(vararg steps: Step) = base.copy(steps = steps.toList())

    private fun upload(folder: String = "recly/{{yyyy}}/{{yyyy}}-{{MM}}", retry: Retry = Retry()) =
        Step.DriveUpload(id = "up", folder = folder, retry = retry)

    private fun transcribe(
        provider: String = "assemblyai",
        secretRef: String = "stt_key",
        invokeUrl: String? = null,
        speakers: Speakers = Speakers(),
    ) = Step.Transcribe(
        id = "stt",
        provider = provider,
        secretRef = secretRef,
        invokeUrl = invokeUrl,
        speakers = speakers,
    )

    @Test
    fun acceptsTheFixedPlanShapes() {
        ok(base)
        ok(withSteps(upload()))
        ok(withSteps(upload(), Step.LocalTranscribe("transcribe"), Step.TranscriptPublish("publish")))
    }

    @Test
    fun rejectsBadAndDuplicateStepIds() {
        invalid("bad step id", withSteps(upload().copy(id = "Up-1")))
        invalid("duplicate step id", withSteps(upload(), transcribe().copy(id = "up")))
    }

    @Test
    fun rejectsBadUlid() {
        invalid("bad ulid", base.copy(id = "not-a-ulid"))
    }

    @Test
    fun rejectsUnknownTemplateVariable() {
        invalid("unknown template var", withSteps(upload(folder = "recly/{{nope}}")))
    }

    @Test
    fun rejectsElevenSteps() {
        invalid("11 steps", withSteps(*(0..10).map { upload().copy(id = "s$it") }.toTypedArray()))
        invalid("no steps", withSteps())
    }

    @Test
    fun enforcesRfc3339Timestamps() {
        listOf("2026-08-26T01:00:00+09:00", "2026-08-26T01:00:00.123Z", "2026-08-26t01:00:00z").forEach {
            ok(base.copy(updatedAt = it))
        }
        listOf("2026-08-26T01:00:00+09", "2026-08-26 01:00:00Z", "2026-08-26T01:00:00", "20260826T010000Z", "2026-13-45").forEach {
            invalid("timestamp '$it'", base.copy(updatedAt = it))
        }
    }

    @Test
    fun rejectsWorkflowConstraintViolations() {
        invalid("name empty", base.copy(name = ""))
        invalid("name over 40", base.copy(name = "x".repeat(41)))
        invalid("minDurationSec < 0", base.copy(minDurationSec = -1))
    }

    @Test
    fun rejectsRetryConstraintViolations() {
        invalid("maxAttempts 0", withSteps(upload(retry = Retry(maxAttempts = 0))))
        invalid("maxAttempts 21", withSteps(upload(retry = Retry(maxAttempts = 21))))
        invalid("initialDelaySec 0", withSteps(upload(retry = Retry(initialDelaySec = 0))))
        invalid("maxDelaySec 0", withSteps(upload(retry = Retry(maxDelaySec = 0))))
    }

    @Test
    fun rejectsDriveUploadConstraintViolations() {
        invalid("folder empty", withSteps(upload(folder = "")))
        invalid("folder over 200", withSteps(upload(folder = "a".repeat(201))))
    }

    @Test
    fun rejectsProvidersTheSpecDoesNotDefine() {
        invalidBecause("stt provider", withSteps(upload(), transcribe(provider = "whisper")), WorkflowParser.UNKNOWN_PROVIDER)
    }

    @Test
    fun invokeUrlFollowsTheProviderRule() {
        // docs/08: required where the provider is addressed by an app- or resource-specific URL.
        ok(withSteps(upload(), transcribe(provider = "clova", invokeUrl = "https://gw.example.com/v1/1234/abcd")))
        ok(withSteps(upload(), transcribe(provider = "azure", invokeUrl = "https://r.cognitiveservices.azure.com")))
        invalidBecause("clova without url", withSteps(upload(), transcribe(provider = "clova")), "requires invokeUrl")
        invalidBecause("azure without url", withSteps(upload(), transcribe(provider = "azure")), "requires invokeUrl")
        // Optional where a public default endpoint exists that the URL may replace.
        ok(withSteps(upload(), transcribe(provider = "openai")))
        ok(withSteps(upload(), transcribe(provider = "openai", invokeUrl = "https://whisper.local/v1")))
        // Forbidden where the provider never reads it.
        invalidBecause(
            "assemblyai with url",
            withSteps(upload(), transcribe(invokeUrl = "https://gw.example.com/x")),
            "not allowed for provider 'assemblyai'",
        )
        assertEquals(InvokeUrlUse.REQUIRED, WorkflowParser.invokeUrlUse("clova"))
        assertEquals(InvokeUrlUse.OPTIONAL, WorkflowParser.invokeUrlUse("speechmatics"))
        assertEquals(InvokeUrlUse.NONE, WorkflowParser.invokeUrlUse("elevenlabs"))
    }

    /** docs/08: every provider whose URL is required has a template, and the template as-is is refused. */
    @Test
    fun invokeUrlTemplatesAreForRequiredProvidersAndNotAccepted() {
        for (provider in WorkflowParser.STT_PROVIDERS) {
            val template = WorkflowParser.invokeUrlTemplate(provider)
            if (WorkflowParser.invokeUrlUse(provider) == InvokeUrlUse.REQUIRED) {
                assertNotNull(template, provider)
                assertTrue(template.startsWith("https://"), template)
                invalidBecause(
                    "$provider template unedited",
                    withSteps(upload(), transcribe(provider = provider, invokeUrl = template)),
                    WorkflowParser.INVOKE_URL_PLACEHOLDER,
                )
            } else {
                assertNull(template, provider)
            }
        }
        assertEquals(
            "https://clovaspeech-gw.ncloud.com/external/v1/{appId}/{invokeKey}",
            WorkflowParser.invokeUrlTemplate("clova"),
        )
        assertEquals("https://{resourceName}.cognitiveservices.azure.com", WorkflowParser.invokeUrlTemplate("azure"))
    }

    @Test
    fun rejectsSpeakerCountsOutsideTheHint() {
        invalid("speakers.min 0", withSteps(upload(), transcribe(speakers = Speakers(min = 0, max = 4))))
        invalid("speakers.max 11", withSteps(upload(), transcribe(speakers = Speakers(min = 1, max = 11))))
        invalid("min over max", withSteps(upload(), transcribe(speakers = Speakers(min = 5, max = 2))))
    }

    @Test
    fun rejectsSecretRefsThatAreNotNames() {
        invalid("empty", withSteps(upload(), transcribe(secretRef = "")))
        invalid("dashed", withSteps(upload(), transcribe(secretRef = "Stt-Key")))
    }

    @Test
    fun rejectsAnEmptyModel() {
        invalid("empty model", withSteps(upload(), transcribe().copy(model = "")))
    }
}
