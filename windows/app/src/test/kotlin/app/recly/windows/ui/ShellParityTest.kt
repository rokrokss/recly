package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.detect.MicAccess
import app.recly.windows.detect.MicrophoneAccess
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.message
import app.recly.windows.record.Completion
import app.recly.windows.record.LiveWaveform
import app.recly.windows.ui.component.BadgeTone
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.job.EnqueueResult
import recly.core.job.JobStatus

/**
 * The cross-shell decisions of 2026-10-15 as this shell keeps them: what a stop's line says, a recording the helper's
 * death ended, the microphone read at every start, the consent box on Cancel, and the small rules under them.
 */
class ShellParityTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One directory for each test: Windows does not delete the database a model left open, and a later test that
    // loaded the same directory found the earlier recordings' jobs in it (2026-10-27, the helper-death test saw a job
    // row before its own recording had even started).
    private val dir = File("build/shell-parity/${UUID.randomUUID()}")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a saved recording's line says what the queue did with it`() {
        assertEquals(Str.STATUS_WAITING, completionLine(false, Completion(EnqueueResult.Enqueued("j"))))
        assertEquals(Str.ENQUEUE_SKIPPED_SHORT, completionLine(false, Completion(EnqueueResult.SkippedShort("j"))))
        assertEquals(Str.ENQUEUE_NO_WORKFLOW, completionLine(false, Completion(EnqueueResult.NoWorkflow)))
        assertEquals(Str.ENQUEUE_PARTS_PURGED, completionLine(false, Completion(EnqueueResult.PartsPurged)))
        assertEquals(Str.ENQUEUE_ALREADY_DONE, completionLine(false, Completion(EnqueueResult.AlreadySynced)))
        assertEquals(Str.ENQUEUE_ALREADY_DONE, completionLine(false, Completion(EnqueueResult.AlreadyDone("j"))))
        // The name that came too late first, as the phone says it before the queue's line.
        assertEquals(
            Str.RECORDING_TITLE_TOO_LATE,
            completionLine(false, Completion(EnqueueResult.SkippedShort("j"), titleTooLate = true)),
        )
        // An enqueue that threw, and a recording the helper's death ended.
        assertEquals(Str.ENQUEUE_FAILED, completionLine(false, null))
        assertEquals(Str.ENQUEUE_FAILED, completionLine(true, null))
        assertEquals(Str.RECORDING_FAILED, completionLine(true, Completion(EnqueueResult.SkippedShort("j"))))
    }

    /** D1 · D2: a job pass that finds the wait for Drive does not say it over what the stop had to report. */
    @Test
    fun `a stop's report keeps the line from the wait for Drive`() {
        listOf(
            Str.RECORDING_FAILED, Str.ENQUEUE_FAILED, Str.RECORDING_TITLE_TOO_LATE, Str.ENQUEUE_NO_WORKFLOW,
            Str.ENQUEUE_PARTS_PURGED, Str.ENQUEUE_SKIPPED_SHORT, Str.ENQUEUE_ALREADY_DONE,
        ).forEach { assertTrue(isStopReport(it.message()), "$it") }
        listOf(Str.STATUS_WAITING, Str.STATUS_SAVING).forEach { assertFalse(isStopReport(it.message()), "$it") }
    }

    @Test
    fun `retry on a row waiting for Drive connects Drive instead`() {
        assertTrue(retriesBySigningIn(JobStatus.NEEDS_AUTH))
        listOf(JobStatus.FAILED, JobStatus.NEEDS_SPACE, JobStatus.WAITING, null).forEach { assertFalse(retriesBySigningIn(it), "$it") }
    }

    @Test
    fun `an import starts when the file was created, else when it was last written`() {
        assertEquals(1_000L, importStartMillis(1_000L, 2_000L))
        assertEquals(2_000L, importStartMillis(null, 2_000L))
        assertEquals(2_000L, importStartMillis(0L, 2_000L))
    }

    @Test
    fun `a disconnect's outcome is said under the Drive row in its tone`() {
        assertEquals(BadgeTone.DANGER, disconnectNote(Str.DISCONNECT_CLEANUP_FAILED.message("x"))?.tone)
        assertEquals(BadgeTone.DANGER, disconnectNote(Str.DISCONNECT_REVOKE_FAILED.message("x"))?.tone)
        assertEquals(BadgeTone.WARNING, disconnectNote(Str.DISCONNECT_BUSY.message(1))?.tone)
        assertEquals(BadgeTone.WARNING, disconnectNote(Str.DISCONNECT_STOP_RECORDING.message())?.tone)
        assertEquals(BadgeTone.NEUTRAL, disconnectNote(Str.DISCONNECT_DONE.message())?.tone)
        // The revoke-debt rows under the Drive row already say this one.
        assertNull(disconnectNote(Str.DISCONNECT_STILL_LISTED.message()))
    }

    @Test
    fun `the privacy policy is the page the phones open`() {
        assertEquals("https://recly.dev/policy/privacy-policy", privacyPolicyUrl("en"))
        assertEquals("https://recly.dev/policy/privacy-policy.ko", privacyPolicyUrl("ko"))
        assertEquals("https://recly.dev/policy/privacy-policy", privacyPolicyUrl("ja"))
    }

    /** The written audio's clock: a level per tenth of a second that reached the file. */
    @Test
    fun `the written clock counts every level the helper sent`() {
        val live = LiveWaveform(capacity = 3)
        assertEquals(0.0, live.writtenSec)
        live.add(List(25) { 0.5f })
        live.add(List(5) { 0.1f })
        // Thirty tenths, though the ring only keeps the last three of them.
        assertEquals(3.0, live.writtenSec)
        assertEquals(3, live.peaks.size)
    }

    /** D5: the switch is read again at the start, and a start with it off is refused with the way to turn it on. */
    @Test
    fun `a start with the microphone off is refused and says why`() = runBlocking {
        var access = MicAccess.ALLOWED
        val model = shell(consentReminder = true)
        model.load(
            dataDirectory = dir.absolutePath.toPath(),
            helperCommand = FakeHelperCommand.command(),
            microphoneAccess = { object : MicrophoneAccess { override fun state() = access } },
        )
        // Turned off after the launch read it.
        access = MicAccess.DENIED

        model.start()

        assertTrue(model.micPrompt, "no dialog for the refusal")
        assertEquals(Str.STATUS_MIC_DENIED.message(), model.status)
        assertNull(model.consentRequest, "the consent question was asked for a recording that cannot start")
        assertFalse(model.recording)
        assertEquals(MicAccess.DENIED, model.micAccess)

        model.closeMicPrompt()
        assertFalse(model.micPrompt)
        model.shutdown()
    }

    /** D4: Do not ask again counts on Cancel too. */
    @Test
    fun `cancelling the consent question with the box ticked turns the reminder off`() = runBlocking {
        val settings = FakeSettings(consentReminder = true, language = AppLanguage.ENGLISH)
        val model = ShellModel(scope = scope, localization = Localization(settings) { StringTable.BASE })
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = FakeHelperCommand.command())

        model.start()
        assertNotNull(model.consentRequest)
        model.consentCancelled(dontAskAgain = true)

        assertNull(model.consentRequest)
        assertFalse(model.consentReminder)
        assertFalse(settings.consentReminder)
        assertFalse(model.recording, "Cancel started a recording")
        model.shutdown()
    }

    /**
     * docs/08 "Organizing": signed in to ChatGPT and not agreed — a start from the app asks that first, ahead of the
     * consent reminder, and goes on whatever the answer: Sign out, then the reminder and the recording.
     */
    @Test
    fun `a start asks the organizing consent first and records whatever the answer`() = runBlocking {
        val model = shell(consentReminder = true)
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = FakeHelperCommand.command())
        // No ChatGPT sign-in here: the question is asked as if there were one.
        model.asksOrganizeConsent = { true }

        model.start()
        assertTrue(model.chatGpt!!.organizeConsent, "no organizing question")
        assertNotNull(model.organizeConsentStart)
        assertNull(model.consentRequest, "the reminder came before the organizing question")
        assertFalse(model.recording)
        // A second press while it stands asks nothing more.
        model.start()
        assertNull(model.consentRequest)

        model.answerOrganizeConsent(false)
        assertFalse(model.chatGpt!!.organizeConsent)
        assertNull(model.organizeConsentStart)
        assertNotNull(model.consentRequest, "the start did not go on after Sign out")
        model.consentConfirmed(dontAskAgain = false)
        withTimeout(TIMEOUT_MS) { while (!model.recording) delay(POLL_MS) }
        model.shutdown()
    }

    /** And Continue, or closing it, starts the recording at once when the reminder is off. */
    @Test
    fun `Continue starts the recording the question stood in front of`() = runBlocking {
        val model = shell(consentReminder = false)
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = FakeHelperCommand.command())
        model.asksOrganizeConsent = { true }

        model.start()
        assertTrue(model.chatGpt!!.organizeConsent)
        model.answerOrganizeConsent(true)
        assertFalse(model.chatGpt!!.organizeConsent)
        withTimeout(TIMEOUT_MS) { while (!model.recording) delay(POLL_MS) }
        model.shutdown()
    }

    /** D1: a recording the helper's death ended is not named — it is queued untitled, and the line says it failed. */
    @Test
    fun `a recording the helper ended is queued untitled and said to have failed`() = runBlocking {
        val model = shell(consentReminder = false)
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = FakeHelperCommand.command("die"))

        model.start()
        withTimeout(TIMEOUT_MS) { while (model.recents.none { it.jobId != null }) delay(POLL_MS) }

        assertNull(model.titlePrompt, "the take that failed asked for a name")
        assertEquals(Str.RECORDING_FAILED.message(), model.status)
        val row = model.recents.single { it.jobId != null }
        assertEquals(Str.UNTITLED.message(), row.title)
        model.shutdown()
    }

    /** D8 · D9: a retry the queue refuses says so over the list and on the tray's line, and the button wears no check. */
    @Test
    fun `a refused retry says it cannot be retried now`() = runBlocking {
        val model = shell(consentReminder = false)
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = FakeHelperCommand.command())
        val gone = app.recly.windows.jobs.RecentItem(
            id = "rec-gone", jobId = "job-gone", jobStatus = JobStatus.FAILED, title = Str.UNTITLED.message(),
            startedAt = "2026-10-15T10:00:00Z", state = Str.STATE_FAILED.message(), link = null,
        )

        model.retry(gone)
        withTimeout(TIMEOUT_MS) { while (model.listMessage == null) delay(POLL_MS) }

        assertEquals(ShellNotice(Str.RECENT_RETRY_UNAVAILABLE.message(), BadgeTone.WARNING), model.listMessage)
        assertEquals(Str.RECENT_RETRY_UNAVAILABLE.message(), model.status)
        assertEquals(app.recly.windows.ui.theme.ProcessingState.FAILED, model.action)
        model.dismissListMessage()
        assertNull(model.listMessage)
        model.shutdown()
    }

    /** D11: the Details header, by the phones' counting rule. */
    @Test
    fun `the list header counts what waits and what failed`() {
        fun row(state: Str) = app.recly.windows.jobs.RecentItem(
            id = state.name, jobId = null, title = Str.UNTITLED.message(), startedAt = "2026-10-15T10:00:00Z",
            state = state.message(), link = null,
        )
        assertEquals("0", ledgerCounts(emptyList(), { "$it waiting" }, { "$it failed" }))
        assertEquals("2", ledgerCounts(listOf(row(Str.STATE_DONE), row(Str.STATE_UPLOADING)), { "$it waiting" }, { "$it failed" }))
        val rows = listOf(
            row(Str.STATE_DONE), row(Str.STATUS_SIGN_IN_NEEDED), row(Str.STATE_RETRY_WAIT), row(Str.PROCESSING_LOCAL_RUNNING),
            row(Str.STATE_FAILED), row(Str.STATE_NO_SPACE), row(Str.STATE_TOO_SHORT), row(Str.STATE_UPLOADING),
        )
        assertEquals("8 · 3 waiting · 3 failed", ledgerCounts(rows, { "$it waiting" }, { "$it failed" }))
        assertEquals("1 · 1 failed", ledgerCounts(listOf(row(Str.STATE_FAILED)), { "$it waiting" }, { "$it failed" }))
    }

    private fun shell(consentReminder: Boolean) = ShellModel(
        scope = scope,
        localization = Localization(FakeSettings(consentReminder = consentReminder, language = AppLanguage.ENGLISH)) { StringTable.BASE },
    )

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
    }
}
