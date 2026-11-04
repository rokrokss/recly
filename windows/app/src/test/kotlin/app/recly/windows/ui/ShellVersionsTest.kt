package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import recly.core.chatgpt.AskState
import recly.core.message.CoreMessage
import recly.core.model.Track
import recly.core.transcribe.EditResult
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptVersionKind

/**
 * docs/08 "Versions" · "Ask" through the shell, over a real core: Version history lists what this PC kept and a row's
 * Restore brings it back and closes the dialog; a version the PC does not keep is said under the list; and a request in
 * the Ask composer says over it what stood in its way — here the ChatGPT sign-in — until it is closed.
 */
class ShellVersionsTest {

    @Test
    fun `Restore brings a version back and closes the dialog, and a refusal is said under the list`() = withRecording { model, id ->
        assertIs<EditResult.Edited>(model.editTranscript(id, TranscriptEdit.SetText(0, "Thanks for coming.")))
        val before = model.transcriptVersions(id)
        assertEquals(listOf(TranscriptVersionKind.EDITED, TranscriptVersionKind.TRANSCRIBED), before.map { it.kind })
        assertEquals(listOf(true, false), before.map { it.current })

        model.openVersions()
        assertEquals(id, model.versionsAbout)
        // A version this PC does not keep: the dialog stays, and says it could not.
        assertEquals(Str.VERSIONS_RESTORE_FAILED, model.restoreVersion(id, "999"))
        assertEquals(id, model.versionsAbout)

        assertNull(model.restoreVersion(id, before[1].id))
        assertNull(model.versionsAbout, "a restore closes the dialog")
        until { model.detail?.transcript?.segments?.first()?.text == "Thanks for joining." }
        assertEquals(
            listOf(TranscriptVersionKind.RESTORED, TranscriptVersionKind.EDITED, TranscriptVersionKind.TRANSCRIBED),
            model.transcriptVersions(id).map { it.kind },
        )
    }

    @Test
    fun `a request says what stood in its way over the composer, and closing it forgets that`() = withRecording { model, id ->
        // Not signed in to ChatGPT here: nothing is sent, and the answer's place says so.
        model.ask(id, null, "Mina is Minna")
        until { model.detail?.ask is AskState.Failed }
        val failed = assertIs<AskState.Failed>(model.detail?.ask)
        assertTrue(failed.reason.startsWith(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()), failed.reason)
        // Retry asks the same words again.
        assertEquals("Mina is Minna", failed.question)
        assertNull(model.detail?.askChanges, "nothing was saved, so nothing is tinted")
        // Nothing to undo: Undo does nothing, and says nothing.
        model.undoAsk(id)
        assertNull(model.detail?.notice)
        assertEquals(false, model.detail?.askUndoing)

        model.closeAsk(id)
        until { model.detail?.ask == AskState.None }
        assertEquals("Thanks for joining.", model.detail?.transcript?.segments?.first()?.text, "the transcript is as it was")
    }

    /**
     * A recording shorter than the minimum (so no job of it is in the way of an edit) with a transcript of two lines,
     * open in the detail as the window opens it.
     */
    private fun withRecording(body: suspend (ShellModel, String) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("recly-versions-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = ShellModel(scope = scope, localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE })
        val observers = mutableListOf<Job>()
        try {
            model.load(directory.absolutePath.toPath(), FakeHelperCommand.command("parts=1", "sec=2.0", "write"))
            val processing = assertNotNull(model.processing)
            until { processing.draft != null }
            processing.edit { it.minimumSeconds = "60" }
            processing.save().join()
            model.start()
            until { model.recording && model.recents.isNotEmpty() }
            model.stop()
            until { !model.recording }
            val id = model.recents.first().id
            val folder = File(directory, "recordings").listFiles()!!.single { it.isDirectory }
            val base = folder.listFiles()!!.first { it.name.endsWith(".meta.json") }.name.removeSuffix(".meta.json")
            val transcript = Transcript(
                recordingId = id,
                track = Track.MIX,
                language = "en",
                provider = TranscriptProvider("assemblyai"),
                createdAt = "2026-11-04T00:00:00Z",
                durationSec = 2.0,
                speakers = emptyList(),
                segments = listOf(TranscriptSegment(0.0, 1.0, "", "Thanks for joining."), TranscriptSegment(1.0, 2.0, "", "Let's start.")),
            )
            File(folder, "$base.transcript.json").writeText(Json.encodeToString(Transcript.serializer(), transcript))
            model.openDetail(model.recents.first())
            until { model.detail?.transcript != null }
            observers += launch { model.followDetailResults(id) }
            observers += launch { model.followDetailState(id) }
            body(model, id)
        } finally {
            observers.forEach { it.cancelAndJoin() }
            model.shutdown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private suspend fun until(ready: () -> Boolean) = withTimeout(60_000) {
        while (!ready()) delay(20)
    }
}
