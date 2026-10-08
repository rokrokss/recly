package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.job.JobStatus

/**
 * The expanded row's Retry is an offer to fix something, not a way to hurry a job along: [retryable]
 * is the seam that says which rows have anything to fix. A row that is still on its way — `PENDING`,
 * `RUNNING` — would have been asked to start over, and a recording with no job at all has no upload
 * to ask for now that "Upload now" is gone.
 */
class TrayPopupTest {

    @Test
    fun `the failures offer a retry`() {
        assertTrue(retryable(JobStatus.FAILED, transcribing = false))
        // Parked rather than failed, both of them: the user signs in, or frees the space, and asks
        // for the job again from the row it stopped on.
        assertTrue(retryable(JobStatus.NEEDS_AUTH, transcribing = false))
        assertTrue(retryable(JobStatus.NEEDS_SPACE, transcribing = false))
    }

    @Test
    fun `a job on its way does not`() {
        assertFalse(retryable(JobStatus.PENDING, transcribing = false))
        assertFalse(retryable(JobStatus.RUNNING, transcribing = false))
    }

    /**
     * docs/09 screen principle 2 (2026-09-04): a `RETRY` row is a job waiting out its own `next_run_at` after
     * a failed attempt, and the user need not wait the timer out — it offers the same retry the
     * failures do. A wait on a provider transcribing is somebody else's work, and offers nothing.
     */
    @Test
    fun `a job waiting on its retry timer offers one, and one waiting on a provider does not`() {
        assertTrue(retryable(JobStatus.WAITING, transcribing = false))
        assertFalse(retryable(JobStatus.WAITING, transcribing = true))
    }

    @Test
    fun `and neither does a job with nothing left to do`() {
        assertFalse(retryable(JobStatus.DONE, transcribing = false))
        // docs/03: too short to be worth a workflow, which a retry would not change.
        assertFalse(retryable(JobStatus.SKIPPED_SHORT, transcribing = false))
        // Waiting for the speech model: the row's action is the download, never a Retry.
        assertFalse(retryable(JobStatus.NEEDS_MODEL, transcribing = false))
        // The recording never got a job, so there is nothing to retry. docs/03: and a
        // recording another device made has no job here either, whatever it is doing over there.
        assertFalse(retryable(null, transcribing = false))
        assertFalse(retryable(null, transcribing = true))
    }

    /** The UX decisions of 2026-10-08: the State and Device nodes say words in the app's language, not codes. */
    @Test
    fun `the State node says words, not codes`() {
        val en = StringTable.of(StringTable.BASE)
        val ko = StringTable.of(StringTable.KOREAN)
        val words = mapOf(
            "IDLE" to ("Ready" to "준비"),
            "STARTING" to ("Starting" to "시작 중"),
            "REC" to ("Recording" to "녹음 중"),
            "STOPPING" to ("Saving" to "저장 중"),
            "OPENING" to ("Opening" to "여는 중"),
            "NO_HELPER" to ("Helper missing" to "도우미 없음"),
            "NAMING" to ("Naming" to "제목 입력"),
        )
        words.forEach { (code, word) ->
            assertEquals(word.first, en[stateWord(code)], code)
            assertEquals(word.second, ko[stateWord(code)].plain(), code)
        }
        assertEquals("Uploading", en[Str.STATE_UPLOADING])
        assertEquals("업로드 중", ko[Str.STATE_UPLOADING].plain())
        assertEquals("Desktop", en[Str.NODE_DESKTOP])
        assertEquals("데스크톱", ko[Str.NODE_DESKTOP].plain())
    }
}
