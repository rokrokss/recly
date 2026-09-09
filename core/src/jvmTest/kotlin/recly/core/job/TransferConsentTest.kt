@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.job

import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.model.*
import recly.core.platform.*
import recly.core.privacy.*
import recly.core.testing.*
import recly.core.transcribe.Reasons
import recly.core.DriverFactory
import recly.core.ReclyCore

class TransferConsentTest {
    private val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
    private val hook = webhookStep("hook", onError = OnError.CONTINUE)
    private fun target(step: Step) = assertNotNull(TransferTargets.forStep(step))

    @Test
    fun `permission parks even onError continue and resumes without repeating the upload`() = runBlocking {
        val send = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(upload, send), requireTransferConsent = true)
        val id = f.enqueue(f.seed(), driveStep("up"), hook)
        f.service.runDueJobs()
        assertEquals(JobStatus.NEEDS_CONSENT, f.store.get(id)!!.status)
        assertEquals(0, send.calls)
        assertEquals(1, upload.calls)
        val before = f.store.stepsOf(id)
        assertEquals(StepStatus.SUCCEEDED, before[0].status)
        assertEquals(StepStatus.NEEDS_CONSENT, before[1].status)
        assertEquals(0, before[1].attempts)

        f.consents.grant(listOf(target(hook)))
        f.store.resumeConsent(id, f.clock.now())
        f.executorWith(listOf(upload, send)).runDueJobs()
        assertEquals(JobStatus.DONE, f.store.get(id)!!.status)
        assertEquals(1, upload.calls)
        assertEquals(before[0], f.store.stepsOf(id)[0])
        assertEquals(1, send.calls)
    }

    @Test
    fun `revoking between requests prevents the next request and preserves newly saved provider state`() = runBlocking {
        var requests = 0
        val transport = object : Transport {
            override suspend fun execute(plan: HttpPlan): HttpResult {
                requests++
                return HttpResult(200, emptyMap(), byteArrayOf())
            }
        }
        lateinit var f: Fixture
        val state = buildJsonObject { put("providerJob", "already-submitted") }
        val stt = transcribeStep("stt")
        val runner = ScriptedRunner("transcribe") { ctx, call ->
            if (call == 1) {
                ctx.deps.transport.execute(HttpPlan("POST", "https://api.assemblyai.com/v2/upload"))
                ctx.saveState(state)
                f.consents.revoke(target(stt).id)
            } else {
                assertEquals(state, ctx.state)
            }
            // All providers use Reasons.send: it must preserve the permission exception.
            Reasons.send(ctx.deps, "consent test", HttpPlan("GET", "https://api.assemblyai.com/v2/transcript/job"))
            output("text" to "done")
        }
        f = Fixture(listOf(upload, runner), requireTransferConsent = true, transport = transport)
        f.consents.grant(listOf(target(stt)))
        val id = f.enqueue(f.seed(), driveStep("up"), stt)
        f.service.runDueJobs()
        assertEquals(1, requests)
        assertEquals(JobStatus.NEEDS_CONSENT, f.store.get(id)!!.status)
        assertEquals(state, f.store.stepsOf(id)[1].state)
        assertEquals(0, f.store.stepsOf(id)[1].attempts)
        f.consents.grant(listOf(target(stt)))
        f.store.resumeConsent(id, f.clock.now())
        f.executorWith(listOf(upload, runner)).runDueJobs()
        assertEquals(JobStatus.DONE, f.store.get(id)!!.status)
        assertEquals(2, requests)
        assertEquals(1, upload.calls)
    }

    @Test
    fun `revoked consent blocks polling after restart without losing its state or retry count`() = runBlocking {
        val state = buildJsonObject { put("jobId", "pending") }
        val stt = transcribeStep("stt")
        val runner = ScriptedRunner("transcribe") { _, _ -> StepOutcome.Waiting(retryAfterSec = 1, state = state) }
        val f = Fixture(listOf(upload, runner), requireTransferConsent = true)
        f.consents.grant(listOf(target(stt)))
        val id = f.enqueue(f.seed(), driveStep("up"), stt)
        f.service.runDueJobs()
        f.consents.revoke(target(stt).id)
        f.clock.advance(2.seconds)
        f.executorWith(listOf(upload, runner)).runDueJobs()
        assertEquals(1, runner.calls)
        assertEquals(JobStatus.NEEDS_CONSENT, f.store.get(id)!!.status)
        assertEquals(state, f.store.stepsOf(id)[1].state)
        assertEquals(0, f.store.stepsOf(id)[1].attempts)
    }

    @Test
    fun `queued job checks the current edited endpoint rather than its approved snapshot`() = runBlocking {
        val old = hook as Step.Webhook
        val changed = old.copy(url = "https://example.com/new")
        val doc = testDocument(testWorkflow(steps = listOf(driveStep("up"), changed)))
        val send = ScriptedRunner("webhook") { ctx, _ ->
            assertEquals(changed, ctx.step)
            output("status" to "200")
        }
        val f = Fixture(listOf(upload, send), requireTransferConsent = true, live = { doc })
        f.consents.grant(listOf(target(old)))
        val id = f.enqueue(f.seed(), driveStep("up"), old)
        f.service.runDueJobs()
        assertEquals(0, send.calls)
        assertEquals(JobStatus.NEEDS_CONSENT, f.store.get(id)!!.status)
        f.consents.grant(listOf(target(changed)))
        f.store.resumeConsent(id, f.clock.now())
        f.service.runDueJobs()
        assertEquals(1, send.calls)
    }

    @Test
    fun `grants survive reopening and key changes but not endpoint provider or purpose changes`() = runBlocking {
        val f = Fixture(emptyList(), requireTransferConsent = true)
        val stt = (transcribeStep("stt") as Step.Transcribe).copy(provider = "openai")
        f.consents.grant(listOf(target(stt)))
        val reopened = TransferConsents(f.db, f.deps)
        assertTrue(reopened.missing(listOf(target(stt.copy(id = "renamed", secretRef = "new_key")))).isEmpty())
        assertEquals(1, reopened.missing(listOf(target(stt.copy(provider = "groq")))).size)
        assertEquals(1, reopened.missing(listOf(target(stt.copy(invokeUrl = "https://custom.example/v2")))).size)
        assertEquals(1, reopened.missing(listOf(target(stt).copy(disclosureVersion = 2))).size)
        assertEquals(target(stt), target(stt.copy(invokeUrl = target(stt).endpoint + "/")))
        val wf = testWorkflow(steps = listOf(driveStep("up"), stt))
        assertEquals(TransferTargets.forWorkflow(wf), TransferTargets.forWorkflow(wf.copy(name = "renamed")))
        assertEquals(1, TransferTargets.forWorkflow(wf.copy(steps = listOf(stt, stt.copy(id = "other")))).size)
    }

    @Test
    fun `ignored endpoint overrides cannot disguise the actual fixed provider destination`() {
        for (provider in listOf("assemblyai", "rtzr", "deepgram", "elevenlabs", "daglo", "rev", "gladia")) {
            val step = (transcribeStep("stt") as Step.Transcribe).copy(provider = provider)
            assertEquals(target(step), target(step.copy(invokeUrl = "https://unrelated.example")), provider)
        }
    }

    @Test
    fun `restoring the database without the original device key cannot restore permission`() = runBlocking {
        val f = Fixture(emptyList(), requireTransferConsent = true)
        val t = target(hook)
        f.consents.grant(listOf(t))
        assertTrue(f.consents.missing(listOf(t)).isEmpty())
        f.deps.secureStore.delete("privacy", "transfer-device")
        assertEquals(listOf(t), TransferConsents(f.db, f.deps).missing(listOf(t)))
        f.consents.grant(listOf(t))
        assertTrue(f.consents.missing(listOf(t)).isEmpty())
    }

    @Test
    fun `facade groups imported destinations and resumes only the blocked step after explicit permission`() = runBlocking {
        val send = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(upload, send), requireTransferConsent = true)
        val core = ReclyCore(f.deps, object : DriverFactory { override fun create() = f.driver })
        val next = (hook as Step.Webhook).copy(id = "other", url = "https://example.com/other")
        val workflow = testWorkflow(steps = listOf(driveStep("up"), hook, next))
        val json = recJson.encodeToString(testDocument(workflow))
        core.workflows.importJson(json)
        assertTrue(core.transferConsents.approved().isEmpty())
        val id = f.enqueue(f.seed(), *workflow.steps.toTypedArray())
        f.service.runDueJobs()
        assertEquals(listOf(target(hook), target(next)), core.pendingTransferTargets())
        core.resumeConsentedJobs()
        assertEquals(JobStatus.NEEDS_CONSENT, f.store.get(id)!!.status)
        assertFalse(core.jobs.retry(id), "normal retry must not bypass permission")
        core.transferConsents.grant(core.pendingTransferTargets())
        assertFalse(core.workflows.exportJson().contains("disclosureVersion"))
        core.resumeConsentedJobs()
        assertEquals(StepStatus.SUCCEEDED, f.store.stepsOf(id)[0].status)
        f.service.runDueJobs()
        assertEquals(JobStatus.DONE, f.store.get(id)!!.status)
        assertEquals(1, upload.calls)
        assertEquals(2, send.calls)
    }

    @Test
    fun `missing and corrupt grant records fail closed and a failed batch grants nothing`() = runBlocking {
        val f = Fixture(emptyList(), requireTransferConsent = true)
        val t = target(hook)
        f.db.recQueries.kvSet("privacy/transfer/" + t.id, "not-json")
        assertEquals(listOf(t), f.consents.missing(listOf(t)))
        assertFailsWith<IllegalArgumentException> { f.consents.grant(listOf(t, t.copy(kind = "unknown"))) }
        assertEquals(listOf(t), f.consents.missing(listOf(t)))
        // Inject an actual SQLite write failure after the first row; the transaction must roll back.
        f.driver.execute(null, "CREATE TRIGGER refuse_consent BEFORE INSERT ON kv WHEN NEW.key = 'privacy/transfer/${target(transcribeStep("stt")).id}' BEGIN SELECT RAISE(ABORT, 'test storage failure'); END", 0)
        assertFails { f.consents.grant(listOf(t, target(transcribeStep("stt")))) }
        assertEquals(listOf(t), f.consents.missing(listOf(t)))
    }

    @Test
    fun `invalid destination cannot reach the transport and other shells retain their existing behavior`() = runBlocking {
        val send = ScriptedRunner("webhook") { _, _ -> error("Must not execute") }
        val f = Fixture(listOf(send), requireTransferConsent = true)
        val invalid = (hook as Step.Webhook).copy(url = "file:///tmp/audio", onError = OnError.ABORT)
        val id = f.enqueue(f.seed(), invalid)
        f.service.runDueJobs()
        assertEquals(0, send.calls)
        assertEquals(JobStatus.FAILED, f.store.get(id)!!.status)
        assertTrue(Fixture(emptyList()).consents.missing(listOf(target(hook))).isEmpty())
    }
}
