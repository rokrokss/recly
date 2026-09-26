package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.processing.TranscriptionMode
import recly.core.transcribe.LocalEngineStatus

/**
 * The Record tab's first-run card is for a model that is actually missing, on a device that would
 * use it, for someone who has not put it away — and never over a recording.
 */
class ModelPromptTest {

    @Test
    fun `the card shows only when every condition holds`() {
        assertTrue(visible())
        assertFalse(visible(mode = TranscriptionMode.EXTERNAL))
        assertFalse(visible(mode = TranscriptionMode.OFF))
        assertFalse(visible(mode = null), "settings not read yet")
        assertFalse(visible(installed = false), "the unavailable placeholder")
        assertFalse(visible(dismissed = true), "Not now")
        assertFalse(visible(capturing = true))
    }

    /** A recording already waits for the model: the list's banner, with its count, is the one prompt. */
    @Test
    fun `no card while a recording waits for the model`() {
        assertFalse(visible(waiting = true))
    }

    @Test
    fun `a model that is here, or an engine that cannot run, shows no card`() {
        assertFalse(visible(status = LocalEngineStatus.READY))
        assertFalse(visible(status = LocalEngineStatus.WAITING), "downloaded, only waiting on heat or Battery Saver")
        assertFalse(visible(status = LocalEngineStatus.UNSUPPORTED))
        assertFalse(visible(status = null))
    }

    private fun visible(
        mode: TranscriptionMode? = TranscriptionMode.LOCAL,
        installed: Boolean = true,
        status: LocalEngineStatus? = LocalEngineStatus.MODEL_REQUIRED,
        dismissed: Boolean = false,
        capturing: Boolean = false,
        waiting: Boolean = false,
    ) = modelPromptVisible(mode, installed, status, dismissed, capturing, waiting)
}
