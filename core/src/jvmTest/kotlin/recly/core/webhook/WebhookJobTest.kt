@file:OptIn(ExperimentalTime::class)

package recly.core.webhook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import recly.core.drive.DriveHarness
import recly.core.job.Executor
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.StepStatus
import recly.core.job.defaultRunners
import recly.core.model.Step
import recly.core.platform.SecureStore
import recly.core.testing.FakeWebhook
import recly.core.testing.START

/** `drive.upload` then `webhook`, through the real executor: the payload must carry Drive's ids. */
class WebhookJobTest {
    @Test
    fun `the webhook step reports the ids the upload step just created`() = runBlocking {
        val hook = FakeWebhook()
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES, webhook = hook)
        h.register()
        h.secrets.put(SecureStore.SECRETS, "n8n", "whsec_${"A".repeat(43)}=".encodeToByteArray())
        val workflow = h.workflow.copy(
            steps = listOf(
                Step.DriveUpload(id = "up"),
                Step.Webhook(id = "notify", url = hook.url, secretRef = "n8n"),
            ),
        )
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, workflow, START))

        executor.runDueJobs(START)

        assertEquals(JobStatus.DONE, assertNotNull(store.get(job.id)).status)
        val steps = store.stepsOf(job.id)
        assertTrue(steps.all { it.status == StepStatus.SUCCEEDED }, steps.map { it.status }.toString())

        val sent = hook.received.single()
        assertEquals(steps.last().id, sent.header("webhook-id"))
        assertNotNull(sent.header("webhook-signature"))

        val payload = Json.decodeFromString<WebhookPayload>(sent.text)
        val folderId = assertNotNull(h.drive.idOf(h.base))
        assertEquals(folderId, payload.data.folder?.drive?.folderId)
        assertEquals("recly/2026/2026-08/${h.base}", payload.data.folder?.path)
        // Two parts plus meta.json, each with the id Drive handed back during the upload step.
        assertEquals(3, payload.data.files.size)
        payload.data.files.forEach { file ->
            assertEquals(h.drive.idOf(file.name), assertNotNull(file.drive).fileId, file.name)
        }
        assertEquals(listOf("mono", "mono", "meta"), payload.data.files.map { it.track })
    }
}
