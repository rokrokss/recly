@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import recly.core.drive.ScriptedTokenProvider
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.platform.AuthRequiredException
import recly.core.platform.TokenProvider
import recly.core.testing.CoreFixture

/**
 * docs/10 "Failures the user can fix, and their notices": a new recording on a device whose Drive is not
 * connected waits in `NEEDS_AUTH` — it spends no attempt, never turns `FAILED`, however long it waits, and
 * goes on by itself once Drive is connected.
 */
class NoDriveAccountTest {

    /** The Apple and Windows shells' provider with nobody signed in: no token, and sign-in is what it takes. */
    private class SignedOut : TokenProvider {
        var signedIn = false

        override suspend fun accessToken(): String =
            if (signedIn) ScriptedTokenProvider.FIRST else throw AuthRequiredException(CoreMessage.NEEDS_AUTH)

        override suspend fun invalidate() = Unit
    }

    @Test
    fun `a recording on a device that never connected Drive waits for Drive, and uploads once it is connected`() = runBlocking {
        val tokens = SignedOut()
        val f = CoreFixture(tokenProvider = tokens)
        f.record()
        val jobId = (f.core.enqueue(CoreFixture.ID) as EnqueueResult.Enqueued).jobId

        waitsForDrive(f, jobId, CoreMessage.NEEDS_AUTH)

        tokens.signedIn = true
        assertEquals(1, f.core.reconnectDrive())
        f.drain()
        assertEquals(JobStatus.DONE, f.core.jobs.list().single().status)
    }

    @Test
    fun `a recording made after Drive was disconnected waits for Drive, and uploads once it is connected again`() = runBlocking {
        val f = CoreFixture()
        f.core.disconnect(alsoDeleteRecordings = false)
        f.record()
        val jobId = (f.core.enqueue(CoreFixture.ID) as EnqueueResult.Enqueued).jobId

        waitsForDrive(f, jobId, CoreMessage.DRIVE_REAUTH)

        assertEquals(1, f.core.reconnectDrive())
        f.drain()
        assertEquals(JobStatus.DONE, f.core.jobs.list().single().status)
    }

    /** Passes over days change nothing: the upload waits in `NEEDS_AUTH` with the code that says Drive is the fix. */
    private suspend fun waitsForDrive(f: CoreFixture, jobId: String, code: CoreMessage) {
        repeat(5) {
            f.core.runDueJobs(f.clock.now())
            f.clock.advance(1.days)
        }
        assertEquals(JobStatus.NEEDS_AUTH, f.core.jobs.list().single { it.id == jobId }.status)
        val upload = f.core.jobs.steps(jobId).first()
        assertEquals(StepStatus.NEEDS_AUTH, upload.status)
        assertEquals(0, upload.attempts, "waiting for Drive spends no attempt")
        assertEquals(code, CoreMessageRef.parse(upload.lastError!!)?.message)
        assertEquals(
            listOf(StepStatus.PENDING),
            f.core.jobs.steps(jobId).drop(1).map { it.status }.distinct(),
            "nothing after the upload has run",
        )
        assertEquals(0, f.drive.requests.count { it.path.startsWith("/upload/") })
    }
}
