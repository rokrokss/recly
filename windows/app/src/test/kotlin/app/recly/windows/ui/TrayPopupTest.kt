package app.recly.windows.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.text
import app.recly.windows.plain
import app.recly.windows.ui.theme.ReclyDesktopTheme
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.model.Track
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment

/**
 * A row's Retry is an offer to fix something, not a way to hurry a job along: [retryable]
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

    /**
     * 2026-10-11 (N1, W26): the Details list's open row offers the fixes — the download, Retry, Check the key and, since
     * a tray row opens it (2026-10-28), Drive's storage page — and a row with nothing to offer has no actions line at all.
     * 2026-10-15: Delete is not one of them (it is the detail's More), so a finished row has none.
     */
    @Test
    fun `the open row of the Details list offers the fixes`() {
        fun row(status: JobStatus?, state: Str, lastError: String? = null) =
            app.recly.windows.jobs.RecentItem("r", "j", status, UiMessage.Text("Weekly sync"), "2026-10-11T09:00:00Z", 60.0, state.message(),
                link = null, lastError = lastError)
        val failed = rowActions(row(JobStatus.FAILED, Str.STATE_FAILED, recly.core.message.CoreMessage.AUTH_REJECTED.code(detail = "401")))
        assertEquals(RowActions(download = false, retry = true, checkKey = true, storage = false), failed)
        assertEquals(
            RowActions(download = true, retry = false, checkKey = false, storage = false),
            rowActions(row(JobStatus.NEEDS_MODEL, Str.STATE_NEEDS_MODEL)),
        )
        // docs/10 "Drive out of space": the storage page, and Retry for once the space is freed.
        assertEquals(RowActions(download = false, retry = true, checkKey = false, storage = true), rowActions(row(JobStatus.NEEDS_SPACE, Str.STATE_NO_SPACE)))
        // Finished, or still being written: nothing to say or fix, so no line under it.
        assertEquals(RowActions.NONE, rowActions(row(JobStatus.DONE, Str.STATE_DONE)))
        assertEquals(RowActions.NONE, rowActions(row(null, Str.STATUS_RECORDING)))
    }

    /**
     * 2026-10-15: picking the recording the Details window already shows — its row, a search hit on it, its tray row —
     * neither reloads it nor asks about an editor's changes; any other one goes through the leave.
     */
    @Test
    fun `picking the open recording again does nothing`() {
        var asked = 0
        var opened = 0
        val leave: (() -> Unit) -> Unit = { then -> asked++; then() }
        pickRecording("rec-1", "rec-1", leave) { opened++ }
        assertEquals(0, asked)
        assertEquals(0, opened)
        pickRecording("rec-1", "rec-2", leave) { opened++ }
        pickRecording(null, "rec-1", leave) { opened++ }
        assertEquals(2, asked)
        assertEquals(2, opened)
    }

    /**
     * 2026-10-28: a tray row is the recording, as the Details list's row is — the transcript's first words under its
     * title and in its sentence, one element a screen reader hears — and a click on it opens it in the Details window.
     * Nothing opens under it any more: its Details and Delete are gone from the popup.
     */
    @Test
    fun `a tray row says its first words and opens its recording in the Details window`() = runBlocking {
        val directory = Files.createTempDirectory("recly-tray-row-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = ShellModel(scope = scope, localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE })
        suspend fun until(ready: () -> Boolean) = withTimeout(60_000) {
            while (!ready()) delay(20)
        }
        try {
            model.load(directory.absolutePath.toPath(), FakeHelperCommand.command("parts=1", "sec=2.0", "write"))
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
                createdAt = "2026-10-28T00:00:00Z",
                durationSec = 2.0,
                speakers = emptyList(),
                segments = listOf(TranscriptSegment(0.0, 2.0, "S1", "Thanks for joining.")),
            )
            File(folder, "$base.transcript.json").writeText(Json.encodeToString(Transcript.serializer(), transcript))
            // Any change to the rows reads their first words again: a rename written to the meta is one, even to the name it has.
            model.rename(RenameRequest(id, ""), "")
            until { model.previews[id] != null }

            val strings = model.localization.current
            val scene = ImageComposeScene(520, 900, Density(1f)) {
                ReclyDesktopTheme(dark = false, highContrast = false) { TrayPopup(model, strings) {} }
            }
            try {
                repeat(3) { scene.render() }
                val title = model.recents.first { it.id == id }.title.text(strings)
                val row = scene.nodes().single { it.description()?.startsWith("$title · Thanks for joining. · ") == true }
                assertEquals(Role.Button, row.config.getOrNull(SemanticsProperties.Role))
                // Drawn as well as heard: the line under the title.
                assertTrue(scene.unmergedTexts().contains("Thanks for joining."), "the first words are not drawn under the title")

                val at = row.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                repeat(3) { scene.render() }
                assertTrue(model.recordingsOpen, "the click did not bring the Details window up")
                assertEquals(id, model.detail?.recordingId)
                assertFalse(strings[Str.DELETE] in scene.unmergedTexts(), "${strings[Str.DELETE]} opened under the row")
            } finally {
                scene.close()
            }
        } finally {
            model.shutdown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
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
            "NO_HELPER" to ("Can’t record" to "녹음 불가"),
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

    /**
     * 2026-10-10: the ledger's last line is not the place for what a line above already says — the banner counts the
     * recordings waiting for Drive.
     */
    @Test
    fun `the status line is left out where a line above says the same`() {
        val drive = listOf(JobAlert(AlertReason.NEEDS_AUTH, 2))
        assertFalse(statusLineShown(Str.STATUS_SIGN_IN_NEEDED.message(), drive))
        assertTrue(statusLineShown(Str.STATUS_SIGN_IN_NEEDED.message(), emptyList()))
        assertTrue(statusLineShown(Str.STATUS_RECORDING.message(), drive))
        assertTrue(statusLineShown(UiMessage.Text("a diagnostic"), drive))
    }

    /** Every node of the scene's merged tree — one per thing a screen reader stops on. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.nodes(): List<SemanticsNode> =
        semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.withDescendants() }

    /** Every text drawn in the scene, read off the unmerged tree — which keeps what a merged row clears for its sentence. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.unmergedTexts(): List<String> =
        semanticsOwners.flatMap { owner -> owner.unmergedRootSemanticsNode.withDescendants() }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun SemanticsNode.withDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.withDescendants() }

    private fun SemanticsNode.description(): String? = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
}
