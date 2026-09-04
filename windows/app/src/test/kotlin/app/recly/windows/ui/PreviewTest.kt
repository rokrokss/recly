package app.recly.windows.ui

import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.message
import app.recly.windows.jobs.RecentItem
import app.recly.windows.settings.AppTheme
import app.recly.windows.settings.RecordingMode
import app.recly.windows.settings.Settings
import app.recly.windows.ui.theme.ProcessingState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/lanes/UX-L3: the `--show-…` flags exist so the three dialogs can be photographed on a machine
 * whose tray icon is not reachable — and all three of them have a destructive answer on what is
 * otherwise a real install. `--show-consent` was `start(null)`, which is not a dialog at all when the
 * consent reminder is switched off: it is a recording.
 *
 * So a flag raises its dialog through a `preview…` entry, and in [DialogMode.PREVIEW] the whole of
 * either answer is to close it.
 */
class PreviewTest {

    /**
     * The regression, in the only place it can be seen without a display: the preview raises the
     * question rather than a capture, and its confirm neither starts one nor writes the setting the
     * live confirm writes.
     */
    @Test
    fun `the consent reminder is photographed rather than answered`() {
        val settings = RecordingSettings()
        val model = shell(settings)

        model.previewConsent()

        assertEquals(DialogMode.PREVIEW, model.dialogMode)
        assertNotNull(model.consentRequest, "the dialog the flag was asking for is not on screen")

        model.consentConfirmed(dontAskAgain = true)

        assertNull(model.consentRequest, "the only thing a photographed confirm does is close it")
        assertFalse(model.recording)
        // The live confirm would have written this one — `toggleConsentReminder(false)`.
        assertTrue(model.consentReminder)
        assertEquals(emptyList(), settings.writes, "a screenshot run wrote to the settings store")
        // `begin` never ran, so the line the tray was opened with is still the line it shows.
        assertEquals(Str.STATUS_OPENING.message(), model.status)
    }

    /** Cancelling is what it always was, and a photographed question is still a question. */
    @Test
    fun `cancelling the photographed consent closes it too`() {
        val model = shell(RecordingSettings())
        model.previewConsent()

        model.consentCancelled()

        assertNull(model.consentRequest)
    }

    /**
     * The disconnect warning's confirm is the one that revokes the developer's own Google grant, and
     * the phase machine is what would have recorded that it had: a preview leaves the store as it
     * found it.
     */
    @Test
    fun `the disconnect warning is photographed rather than answered`() {
        val settings = RecordingSettings()
        val model = shell(settings)

        model.previewDisconnect()
        model.disconnect(alsoDeleteRecordings = true)

        assertEquals(DialogMode.PREVIEW, model.dialogMode)
        assertNull(model.disconnectPrompt)
        assertEquals(DisconnectPhase.NONE, model.disconnectPhase, "a preview reached runDisconnect")
        assertEquals(emptyList(), settings.writes)
        assertEquals(ProcessingState.IDLE, model.action, "the button ran a disconnect")
    }

    /** And the delete dialog's, which is the one that takes a recording off the PC. */
    @Test
    fun `the delete dialog is photographed rather than answered`() {
        val settings = RecordingSettings()
        val model = shell(settings)

        model.previewDelete(item())
        model.delete(DeleteRequest("rec-1", Str.UNTITLED.message(), unuploaded = 2), deleteDrive = true)

        assertEquals(DialogMode.PREVIEW, model.dialogMode)
        assertNull(model.deleteRequest)
        assertEquals(emptyList(), settings.writes)
        assertEquals(Str.STATUS_OPENING.message(), model.status, "a preview reached the core's delete")
    }

    /**
     * The wiring itself, because `Main` cannot be composed in a test — an `application {}` needs a
     * display. What must not be in it is the live entry: `--show-consent` calling `start` is the
     * finding this preview mode answers, and the two dialogs beside it are as destructive.
     *
     * The working directory is `windows/app` (Gradle's default for a `Test` task), as in `ConsentTest`.
     */
    @Test
    fun `the dev flags reach the previews and never the live entries`() {
        val main = File("src/main/kotlin/app/recly/windows/Main.kt").readText()

        assertTrue(main.contains("model.previewConsent()"), "--show-consent does not preview")
        assertTrue(main.contains("model.previewDisconnect()"), "--show-disconnect does not preview")
        assertTrue(main.contains("model.previewDelete(it)"), "--show-delete does not preview")
        assertFalse(main.contains("model.start("), "a dev flag can start a capture")
        assertFalse(main.contains("model.askToDisconnect()"), "a dev flag can open the live warning")
        assertFalse(main.contains("model.askToDelete("), "a dev flag can open the live delete")
    }

    /** A shell that has never been loaded: no core, no recorder, and no disk under it. */
    private fun shell(settings: Settings) = ShellModel(
        localization = Localization(settings) { StringTable.BASE },
    )

    private fun item() = RecentItem(
        id = "rec-1",
        jobId = null,
        title = Str.UNTITLED.message(),
        startedAt = "2026-08-27T10:00:00.000Z",
        state = Str.STATUS_WAITING.message(),
        link = null,
    )

    /**
     * The shell's own switches, in memory and counting: a screenshot run may leave nothing behind,
     * and every one of these settings is a fact about the developer's own machine.
     */
    private class RecordingSettings : Settings {
        val writes = mutableListOf<String>()

        override var consentReminder: Boolean = true
            set(value) {
                writes += "consentReminder"
                field = value
            }

        override var recordingMode: RecordingMode = RecordingMode.MEETING
            set(value) {
                writes += "recordingMode"
                field = value
            }

        override var language: AppLanguage = AppLanguage.ENGLISH
            set(value) {
                writes += "language"
                field = value
            }

        override var theme: AppTheme = AppTheme.SYSTEM
            set(value) {
                writes += "theme"
                field = value
            }

        override var disconnectPhase: DisconnectPhase = DisconnectPhase.NONE
            set(value) {
                writes += "disconnectPhase"
                field = value
            }

        override var revokeDebt: Boolean = false
            set(value) {
                writes += "revokeDebt"
                field = value
            }
    }
}
