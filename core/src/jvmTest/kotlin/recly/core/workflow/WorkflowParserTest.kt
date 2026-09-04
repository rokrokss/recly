package recly.core.workflow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.model.Language
import recly.core.model.Retry
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument

class WorkflowParserTest {
    private val example = File("../spec/examples/workflows.json").readText()
    private val base: WorkflowsDocument get() = ok(example)

    private fun ok(json: String): WorkflowsDocument {
        val result = WorkflowParser.parse(json)
        assertTrue(result is ParseResult.Ok, "expected Ok, was $result")
        return result.document
    }

    private fun invalid(name: String, json: String) {
        val result = WorkflowParser.parse(json)
        assertTrue(result is ParseResult.Invalid, "$name: expected Invalid, was Ok")
        assertTrue(result.errors.isNotEmpty(), "$name: Invalid with no errors")
    }

    private fun invalidBecause(name: String, json: String, reason: String) {
        val result = WorkflowParser.parse(json)
        assertTrue(result is ParseResult.Invalid, "$name: expected Invalid, was Ok")
        assertTrue(
            result.errors.any { reason in it },
            "$name: expected an error mentioning '$reason', was ${result.errors}",
        )
    }

    private fun mutatedInvalid(name: String, mutate: (WorkflowsDocument) -> WorkflowsDocument) =
        invalid(name, WorkflowParser.serialize(mutate(base)))

    private fun WorkflowsDocument.mapFirstWorkflow(f: (Workflow) -> Workflow) =
        copy(workflows = listOf(f(workflows[0])) + workflows.drop(1))

    private fun WorkflowsDocument.mapFirstStep(f: (Step) -> Step) =
        mapFirstWorkflow { it.copy(steps = listOf(f(it.steps[0])) + it.steps.drop(1)) }

    private fun WorkflowsDocument.mapUpload(f: (Step.DriveUpload) -> Step) =
        mapFirstStep { f(it as Step.DriveUpload) }

    private fun WorkflowsDocument.mapRetry(f: (Retry) -> Retry) =
        mapUpload { it.copy(retry = f(it.retry)) }

    private fun withSecretRef(ref: String?) = WorkflowParser.serialize(
        base.mapFirstWorkflow {
            it.copy(steps = listOf(it.steps[0], (it.steps[1] as Step.Webhook).copy(secretRef = ref)))
        },
    )

    private fun withWebhookUrl(url: String) = WorkflowParser.serialize(
        base.mapFirstWorkflow { it.copy(steps = listOf(it.steps[0], (it.steps[1] as Step.Webhook).copy(url = url))) },
    )

    @Test
    fun parsesExample() {
        val doc = ok(example)
        assertEquals(3, doc.schema)
        assertEquals(3, doc.workflows.size)

        val meeting = doc.workflows[0]
        assertEquals(2, meeting.steps.size)
        assertEquals(30, meeting.minDurationSec)
        assertEquals(0, doc.workflows[1].minDurationSec)

        val upload = meeting.steps[0] as Step.DriveUpload
        assertEquals("recly/{{yyyy}}/{{yyyy}}-{{MM}}", upload.folder)
        assertTrue(upload.includeMeta)
        assertEquals(8, upload.retry.maxAttempts)
        assertEquals(30, upload.retry.initialDelaySec)
        assertEquals(3600, upload.retry.maxDelaySec)

        val webhook = meeting.steps[1] as Step.Webhook
        assertEquals("hook_main", webhook.secretRef)
        assertEquals(10, webhook.retry.maxAttempts)
    }

    @Test
    fun roundTrips() {
        val doc = ok(example)
        assertEquals(doc, ok(WorkflowParser.serialize(doc)))
    }

    @Test
    fun validatesSecretRef() {
        ok(withSecretRef("hook_main"))
        ok(withSecretRef(null))
        invalid("empty secretRef", withSecretRef(""))
        invalid("uppercase/dashed secretRef", withSecretRef("Hook-Main"))
    }

    @Test
    fun rejectsUnknownStepType() {
        invalid("unknown step type", example.replaceFirst("\"drive.upload\"", "\"translate\""))
        // `summarize` is one of those unknown types now: a document still carrying one is invalid,
        // which is what keeps it on the freeze path instead of being rewritten without the step.
        invalid("summarize step", example.replaceFirst("\"drive.upload\"", "\"summarize\""))
    }

    @Test
    fun rejectsBadStepId() {
        invalid("bad step id", example.replaceFirst("\"id\": \"up\"", "\"id\": \"Up-1\""))
    }

    @Test
    fun rejectsDuplicateStepId() {
        invalid("duplicate step id", example.replaceFirst("\"id\": \"hook\"", "\"id\": \"up\""))
    }

    @Test
    fun rejectsBadUlid() {
        invalid("bad ulid", example.replaceFirst("\"01J9ABCDEF0123456789ABCDEF\"", "\"not-a-ulid\""))
    }

    /** docs/05 §스키마: only a *newer* document is read-only. */
    @Test
    fun reportsUnsupportedSchemaBeforeDecoding() {
        assertEquals(
            ParseResult.UnsupportedSchema(4),
            WorkflowParser.parse(example.replaceFirst("\"schema\": 3", "\"schema\": 4")),
        )
        // A future schema may carry step types this version cannot decode; that is still
        // UnsupportedSchema, not a validation failure.
        assertEquals(
            ParseResult.UnsupportedSchema(4),
            WorkflowParser.parse(
                example
                    .replaceFirst("\"schema\": 3", "\"schema\": 4")
                    .replaceFirst("\"drive.upload\"", "\"translate\""),
            ),
        )
    }

    /**
     * docs/05 §스키마 "Outdated": a document an older client wrote is read under the current rules,
     * comes back stamped at the current schema, and says where it came from so the caller knows a
     * write is owed.
     */
    @Test
    fun readsAnOutdatedSchemaAndStampsItCurrent() {
        val result = WorkflowParser.parse(example.replaceFirst("\"schema\": 3", "\"schema\": 1"))

        assertTrue(result is ParseResult.Ok, "expected Ok, was $result")
        assertEquals(1, result.migratedFrom)
        assertEquals(WorkflowParser.SCHEMA, result.document.schema)
        assertEquals(base, result.document, "the migration is the schema field and nothing else")
        // A document already at the current schema owes nothing.
        assertNull((WorkflowParser.parse(example) as ParseResult.Ok).migratedFrom)
        // Below the oldest readable schema there is nothing to migrate from.
        invalidBecause("schema 0", example.replaceFirst("\"schema\": 3", "\"schema\": 0"), "schema must be")
    }

    /**
     * ADR-016: a schema-2 workflow's `enabled`, `isDefault` and `trigger` are fields this build
     * knows it is dropping, so dropping them is the migration and not a reason to freeze. The one
     * part that survives is `trigger.minDurationSec`, which moves up to the workflow.
     */
    @Test
    fun migratesTheLegacyWorkflowFieldsAwayAndKeepsMinDurationSec() {
        val legacy = LEGACY_SCHEMA_2

        val result = WorkflowParser.parse(legacy)

        assertTrue(result is ParseResult.Ok, "expected Ok, was $result")
        assertEquals(2, result.migratedFrom)
        assertEquals(WorkflowParser.SCHEMA, result.document.schema)
        assertEquals(listOf(45, 0), result.document.workflows.map { it.minDurationSec })

        // What the next push writes: the current schema, and not one of the dropped fields.
        val rewritten = WorkflowParser.serialize(result.document)
        listOf("enabled", "isDefault", "trigger", "sources").forEach {
            assertFalse(rewritten.contains("\"$it\""), "'$it' survived the migration: $rewritten")
        }
        assertTrue(rewritten.contains("\"schema\":3"), rewritten)
        assertEquals(result.document, ok(rewritten))
    }

    /**
     * The limit of that migration: this build would write the document back through the typed
     * models, so a field it has no model for would be migrated away. Read-only instead — and the
     * fields it drops on purpose are not an excuse to keep going.
     */
    @Test
    fun refusesToMigrateADocumentWithFieldsItWouldDrop() {
        val extras = example
            .replaceFirst("\"schema\": 3", "\"schema\": 1,\n  \"tags\": [\"pinned\"]")
            .replaceFirst("\"name\": \"회의\"", "\"name\": \"회의\", \"colour\": \"red\"")
            .replaceFirst("\"type\": \"drive.upload\"", "\"type\": \"drive.upload\", \"compress\": true")

        assertEquals(
            ParseResult.MigrationBlocked(
                schema = 1,
                fields = listOf("tags", "workflows[0].colour", "workflows[0].steps[0].compress"),
            ),
            WorkflowParser.parse(extras),
        )
        // A legacy document is not blocked by its own legacy fields, but one unknown field among
        // them still blocks it.
        assertEquals(
            ParseResult.MigrationBlocked(schema = 2, fields = listOf("workflows[0].colour")),
            WorkflowParser.parse(LEGACY_SCHEMA_2.replaceFirst("\"enabled\": true", "\"colour\": \"red\", \"enabled\": true")),
        )
        // Only the migration is blocked: at the current schema nothing is written back on our own
        // account, so the same document reads exactly as it did before.
        assertTrue(WorkflowParser.parse(extras.replaceFirst("\"schema\": 1", "\"schema\": 3")) is ParseResult.Ok)
        // A legacy step's `tracks` is a field this build drops on purpose, like the workflow's own.
        val tracks = LEGACY_SCHEMA_2.replaceFirst("\"type\": \"drive.upload\"", "\"type\": \"drive.upload\", \"tracks\": [\"mono\"]")
        assertTrue(WorkflowParser.parse(tracks) is ParseResult.Ok, "a legacy tracks list is dropped, not blocked")
    }

    /**
     * The legacy `trigger` is dropped whole, so an unknown member *inside* it would go with it
     * silently — exactly what MigrationBlocked exists to prevent. Only `sources` and
     * `minDurationSec` are fields this build knows it is dropping.
     */
    @Test
    fun refusesToMigrateAnUnknownMemberRidingInsideALegacyTrigger() {
        val schedule = LEGACY_SCHEMA_2.replaceFirst(
            "\"minDurationSec\": 45 }",
            "\"minDurationSec\": 45, \"schedule\": \"daily\" }",
        )

        assertEquals(
            ParseResult.MigrationBlocked(schema = 2, fields = listOf("workflows[0].trigger.schedule")),
            WorkflowParser.parse(schedule),
        )
    }

    @Test
    fun rejectsMissingOrNonIntegerSchema() {
        val tail = """"revision":3,"updatedAt":"2026-08-26T01:00:00.000Z","updatedBy":"dev","workflows":[]"""
        invalid("missing schema", "{$tail}")
        invalid("string schema", """{"schema":"1",$tail}""")
        invalid("malformed json", "{not json")
    }

    @Test
    fun rejectsMissingWorkflows() {
        invalid(
            "missing workflows",
            """{"schema":3,"revision":3,"updatedAt":"2026-08-26T01:00:00.000Z","updatedBy":"dev"}""",
        )
    }

    @Test
    fun rejectsUnknownTemplateVariable() {
        invalid("unknown template var", example.replaceFirst("recly/{{yyyy}}/{{yyyy}}-{{MM}}", "recly/{{nope}}"))
    }

    @Test
    fun rejectsElevenSteps() {
        val meeting = base.workflows[0]
        val eleven = base.copy(
            workflows = listOf(
                meeting.copy(steps = meeting.steps + (0..8).map { Step.Webhook(id = "s$it", url = "https://x.y/") }),
            ),
        )
        assertEquals(11, eleven.workflows[0].steps.size)
        invalid("11 steps", WorkflowParser.serialize(eleven))
    }

    @Test
    fun rejectsExplicitNulls() {
        invalidBecause("secretRef null", example.replaceFirst("\"secretRef\": \"hook_main\"", "\"secretRef\": null"), "null")
        invalidBecause("minDurationSec null", example.replaceFirst("\"minDurationSec\": 30", "\"minDurationSec\": null"), "null")
        // A future schema may allow nulls: the schema gate must still win.
        assertEquals(
            ParseResult.UnsupportedSchema(4),
            WorkflowParser.parse(
                example.replaceFirst("\"schema\": 3", "\"schema\": 4").replaceFirst("\"secretRef\": \"hook_main\"", "\"secretRef\": null"),
            ),
        )
    }

    @Test
    fun enforcesRfc3339Timestamps() {
        listOf("2026-08-26T01:00:00+09:00", "2026-08-26T01:00:00.123Z", "2026-08-26t01:00:00z").forEach {
            ok(WorkflowParser.serialize(base.copy(updatedAt = it)))
        }
        listOf("2026-08-26T01:00:00+09", "2026-08-26 01:00:00Z", "2026-08-26T01:00:00", "20260826T010000Z").forEach {
            mutatedInvalid("timestamp '$it'") { d -> d.copy(updatedAt = it) }
            mutatedInvalid("workflow timestamp '$it'") { d -> d.mapFirstWorkflow { w -> w.copy(updatedAt = it) } }
        }
    }

    @Test
    fun rejectsDocumentConstraintViolations() {
        mutatedInvalid("revision < 0") { it.copy(revision = -1) }
        mutatedInvalid("updatedAt not an instant") { it.copy(updatedAt = "yesterday") }
        mutatedInvalid("updatedBy empty") { it.copy(updatedBy = "") }
    }

    @Test
    fun rejectsWorkflowConstraintViolations() {
        mutatedInvalid("name empty") { d -> d.mapFirstWorkflow { it.copy(name = "") } }
        mutatedInvalid("name over 40") { d -> d.mapFirstWorkflow { it.copy(name = "x".repeat(41)) } }
        mutatedInvalid("workflow updatedAt not an instant") { d ->
            d.mapFirstWorkflow { it.copy(updatedAt = "2026-13-45") }
        }
        mutatedInvalid("minDurationSec < 0") { d -> d.mapFirstWorkflow { it.copy(minDurationSec = -1) } }
    }

    @Test
    fun rejectsRetryConstraintViolations() {
        mutatedInvalid("maxAttempts 0") { d -> d.mapRetry { it.copy(maxAttempts = 0) } }
        mutatedInvalid("maxAttempts 21") { d -> d.mapRetry { it.copy(maxAttempts = 21) } }
        mutatedInvalid("initialDelaySec 0") { d -> d.mapRetry { it.copy(initialDelaySec = 0) } }
        mutatedInvalid("maxDelaySec 0") { d -> d.mapRetry { it.copy(maxDelaySec = 0) } }
    }

    @Test
    fun rejectsDriveUploadConstraintViolations() {
        mutatedInvalid("folder empty") { d -> d.mapUpload { it.copy(folder = "") } }
        mutatedInvalid("folder over 200") { d -> d.mapUpload { it.copy(folder = "a".repeat(201)) } }
    }

    private fun withSteps(vararg steps: Step) = WorkflowParser.serialize(
        base.copy(workflows = listOf(base.workflows[0].copy(steps = steps.toList()))),
    )

    private fun upload() = Step.DriveUpload(id = "up")

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
    fun parsesTheTranscribeWorkflowOfTheExample() {
        val minutes = base.workflows[2]
        assertEquals("회의록", minutes.name)

        val stt = minutes.steps[1] as Step.Transcribe
        assertEquals("assemblyai", stt.provider)
        assertEquals("assemblyai_key", stt.secretRef)
        assertEquals(Language.KO, stt.language)
        assertTrue(stt.diarize)
        assertEquals(Speakers(2, 6), stt.speakers)
        assertEquals(null, stt.invokeUrl)
    }

    @Test
    fun defaultsMatchTheSpecTable() {
        val doc = ok(withSteps(upload(), transcribe()))
        val stt = doc.workflows[0].steps[1] as Step.Transcribe

        assertEquals(Language.KO, stt.language)
        assertTrue(stt.diarize)
        assertEquals(Speakers(min = 1, max = 10), stt.speakers)
    }

    @Test
    fun rejectsProvidersTheSpecDoesNotDefine() {
        invalidBecause("stt provider", withSteps(upload(), transcribe(provider = "whisper")), "UnknownProvider")
    }

    @Test
    fun enforcesTheStepOrderTranscribeNeeds() {
        invalidBecause("no upload", withSteps(transcribe()), "TranscribeNeedsUpload")
        invalidBecause("upload after", withSteps(transcribe(), upload()), "TranscribeNeedsUpload")
        ok(withSteps(upload(), transcribe()))
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
    fun acceptsAllowedWebhookUrls() {
        listOf(
            "https://x/y",
            "https://n8n.example.com/webhook/rec?a=1",
            "https://example.com/x?y=1&z=%20",
            "https://example.com/a%20b",
            "https://example.com/p?q=a+b#frag",
            "https://example.com/~u/(x)*,;=!$'",
            "https://example.com/",
            "http://localhost:5678/w",
            "http://localhost/w",
            "http://127.0.0.1/w",
            "http://127.0.0.1:8080/w",
        ).forEach { ok(withWebhookUrl(it)) }
    }

    @Test
    fun rejectsDisallowedWebhookUrls() {
        listOf(
            "http://example.com/x",
            "http://localhost:80@evil.example/rec",
            "http://127.0.0.1.evil.example/",
            "http://localhost.evil.example/",
            "http://user:pass@localhost/w",
            "ftp://example.com/x",
            "not a url",
            "localhost:5678/rec",
            "",
            "https://exa mple.com/x",
            "HTTPS://example.com/x",
            "Http://localhost/x",
            "https://ex\u00e4mple.com/x",
            "https://",
            "https://example.com/\u0001",
            "https://example.com/x\n",
            "https://example.com/{token}",
            "https://example.com/a|b",
            "https://example.com/a\\b",
            "https://example.com/%zz",
            "https://example.com/%2",
            "https://example.com/\"q",
        ).forEach { invalidBecause("url '$it'", withWebhookUrl(it), "url must be https") }
    }

    private companion object {
        /** A document as a build before ADR-016 wrote it: `enabled`, `isDefault`, `trigger`. */
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
