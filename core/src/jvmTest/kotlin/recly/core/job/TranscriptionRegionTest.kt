@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.job

import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.platform.*
import recly.core.testing.*
import recly.core.transcribe.*

class TranscriptionRegionTest {
    private fun sttStep() = Step.Transcribe(id = "stt", provider = "assemblyai", secretRef = "stt_key")
    private class Region(var code: String?) : AppStoreRegion {
        override suspend fun countryCode(): String? = code
    }

    @Test
    fun `China blocks a saved OpenAI step before consent or any runner request`() = runBlocking<Unit> {
        val runner = ScriptedRunner("transcribe") { _, _ -> output("text" to "must not run") }
        val f = Fixture(listOf(runner), requireTransferConsent = true,
            transcriptionPolicy = TranscriptionPolicy(Region("CHN")))
        val id = f.enqueue(f.seed(), sttStep().copy(provider = "openai"))
        f.service.runDueJobs()
        assertEquals(JobStatus.FAILED, f.store.get(id)!!.status)
        assertEquals(CoreMessage.PROVIDER_REGION_RESTRICTED.code(), f.store.stepsOf(id).single().lastError)
        assertEquals(0, runner.calls)
    }

    @Test
    fun `unknown region waits without spending retries and resumes without repeating Drive`() = runBlocking<Unit> {
        val region = Region(null)
        val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val stt = ScriptedRunner("transcribe") { _, _ -> output("text" to "done") }
        val f = Fixture(listOf(upload, stt), transcriptionPolicy = TranscriptionPolicy(region))
        val id = f.enqueue(f.seed(), driveStep("up"), sttStep().copy(provider = "openai"))
        repeat(12) {
            f.service.runDueJobs()
            assertEquals(JobStatus.WAITING, f.store.get(id)!!.status)
            assertEquals(0, f.store.stepsOf(id)[1].attempts)
            assertEquals(CoreMessage.STOREFRONT_UNAVAILABLE.code(), f.store.stepsOf(id)[1].lastError)
            f.clock.advance(61.seconds)
        }
        region.code = "USA"
        f.executorWith(listOf(upload, stt)).runDueJobs()
        assertEquals(JobStatus.DONE, f.store.get(id)!!.status)
        assertEquals(1, upload.calls)
        assertEquals(1, stt.calls)
    }

    @Test
    fun `each request rechecks region and retains state saved before the lookup stopped working`() = runBlocking<Unit> {
        val region = Region("USA")
        var requests = 0
        val transport = object : Transport {
            override suspend fun execute(plan: HttpPlan): HttpResult {
                requests++
                region.code = null
                return HttpResult(200, emptyMap(), byteArrayOf())
            }
        }
        val state = buildJsonObject { put("submitted", true) }
        val runner = ScriptedRunner("transcribe") { ctx, _ ->
            Reasons.send(ctx.deps, "first", HttpPlan("POST", "https://api.openai.com/v1/audio/transcriptions"))
            ctx.saveState(state)
            Reasons.send(ctx.deps, "second", HttpPlan("GET", "https://api.openai.com/v1/result"))
            output("text" to "must not finish")
        }
        val f = Fixture(listOf(runner), transport = transport, transcriptionPolicy = TranscriptionPolicy(region))
        val id = f.enqueue(f.seed(), sttStep().copy(provider = "openai"))
        f.service.runDueJobs()
        assertEquals(1, requests)
        assertEquals(JobStatus.WAITING, f.store.get(id)!!.status)
        assertEquals(0, f.store.stepsOf(id).single().attempts)
        assertEquals(state, f.store.stepsOf(id).single().state)
        region.code = "CHN"
        f.clock.advance(61.seconds)
        f.executorWith(listOf(runner)).runDueJobs()
        assertEquals(1, requests)
        assertEquals(JobStatus.FAILED, f.store.get(id)!!.status)
    }

    @Test
    fun `alternate provider names cannot send to an OpenAI endpoint`() = runBlocking<Unit> {
        val policy = TranscriptionPolicy(Region("CHN"))
        val step = sttStep().copy(provider = "groq")
        for (endpoint in listOf("https://api.openai.com/v1", "https://API.OPENAI.COM./v1", "https://chatgpt.com/api")) {
            val failure = assertFailsWith<StepFailure> { policy.requireAllowed(step.copy(invokeUrl = endpoint)) }
            assertEquals(CoreMessage.PROVIDER_REGION_RESTRICTED.code(), failure.reason)
        }
        policy.requireAllowed(step.copy(invokeUrl = "https://openai.com.example.org/v1"))
        policy.requireAllowed(step.copy(invokeUrl = "https://api.groq.com/openai/v1"))
        val guarded = policy.guardedDeps(step, testDeps())
        assertFailsWith<StepFailure> {
            guarded.transport.execute(HttpPlan("POST", "https://api.openai.com/v1/audio/transcriptions"))
        }
    }

    @Test
    fun `China and unknown regions retain other providers but never follow unchecked redirects`() = runBlocking<Unit> {
        for (code in listOf("CHN", null)) {
            var sent: HttpPlan? = null
            val transport = object : Transport {
                override suspend fun execute(plan: HttpPlan): HttpResult {
                    sent = plan
                    return HttpResult(307, mapOf("Location" to listOf("https://api.openai.com/v1")), byteArrayOf())
                }
            }
            val policy = TranscriptionPolicy(Region(code))
            val step = sttStep().copy(provider = "groq")
            policy.requireAllowed(step)
            val result = policy.guardedDeps(step, testDeps(transport = transport)).transport
                .execute(HttpPlan("POST", "https://api.groq.com/openai/v1"))
            assertEquals(307, result.status)
            assertFalse(assertNotNull(sent).followRedirects)
        }
    }

    @Test
    fun `other storefronts and shells without a storefront policy retain OpenAI`() = runBlocking<Unit> {
        for (code in listOf("USA", "KOR", "HKG", "MAC", "TWN")) {
            val policy = TranscriptionPolicy(Region(code))
            policy.requireAllowed(sttStep().copy(provider = "openai"))
            assertTrue(policy.providerAvailable("openai"))
        }
        TranscriptionPolicy().requireAllowed(sttStep().copy(provider = "openai"))
        val region = Region("USA")
        val policy = TranscriptionPolicy(region)
        policy.refresh()
        region.code = null
        assertFailsWith<StorefrontUnavailableException> {
            policy.requireAllowed(sttStep().copy(provider = "openai"))
        }
        assertFalse(policy.providerAvailable("openai"))
    }
}
