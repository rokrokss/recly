@file:OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.recly.windows.FakeSettings
import app.recly.windows.TitleSheet
import app.recly.windows.helper.CaptureDevices
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.ui.component.DialogCard
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.theme.blueprint
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import recly.core.chatgpt.AskAnswer
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.model.Track
import recly.core.processing.TranscriptionMode
import recly.core.processing.selectTranscriptionMode
import recly.core.storage.StorageKind
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/**
 * Opt-in pictures of the windows, rendered off screen over a shell of their own (a temp data folder, the
 * fake capture helper, real ffmpeg for the import and the waveform). Run with `-Drecly.shots=<dir>`.
 */
class ShotsTest {
    private val out = System.getProperty("recly.shots")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test
    fun `the windows as they look`() = runBlocking {
        val out = out ?: return@runBlocking println("SHOTS skipped — no -Drecly.shots")
        out.mkdirs()
        val dir = File("build/shots-data").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val folder = File(dir, "folder").apply { mkdirs() }
        val settings = FakeSettings(localFolder = folder.path, consentReminder = false)
        val model = ShellModel(localization = Localization(settings) { "en" })
        model.load(dataDirectory = File(dir, "data").path.toPath(), helperCommand = FakeHelperCommand.command())
        until {
            model.selectStorage(StorageKind.FOLDER)
            model.storage == StorageKind.FOLDER
        }
        model.useLocalFolder(folder.path)
        val processing = model.processing!!
        until { processing.draft != null }
        delay(500)
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.OFF, "en"); it.vocabulary = listOf("Recly", "Hyungrok", "AssemblyAI") }
        processing.save()
        runCatching { until { processing.summary.mode == TranscriptionMode.OFF } }.onFailure { error("save: ${processing.message}") }

        val audio = File(dir, "Weekly sync.wav")
        run(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=300:duration=40:sample_rate=16000",
            "-af", "volume='if(between(t,12,19),0,1)':eval=frame", audio.path,
        )
        model.importFiles(listOf(audio))
        until { model.recents.isNotEmpty() && model.recents.first().jobStatus?.name == "DONE" }
        val recordingDir = File(dir, "data/recordings").listFiles()!!.single()
        val base = recordingDir.listFiles()!!.first { it.name.endsWith(".meta.json") }.name.removeSuffix(".meta.json")
        File(recordingDir, "$base.transcript.json").writeText(Json.encodeToString(Transcript.serializer(), sample(model.recents.first().id)))
        // On-device again for the menus and Settings; no recording made after this gets a job that would use it.
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.LOCAL, "en") }
        processing.save()
        until { processing.summary.mode == TranscriptionMode.LOCAL }

        model.openDetail(model.recents.first())
        until { model.detail?.transcript != null }

        for (ko in listOf(false, true)) {
            model.selectLanguage(if (ko) AppLanguage.KOREAN else AppLanguage.ENGLISH)
            val lang = if (ko) "ko" else "en"
            val strings = model.localization.current
            val window: @Composable () -> Unit = { RecordingsWindow(model, strings) { it() } }
            shot("recordings-$lang", 1000, 680, ko) { window() }
            shot("recordings-export-$lang", 1000, 680, ko, clicks = listOf(EXPORT)) { window() }
            shot("recordings-more-$lang", 1000, 680, ko, clicks = listOf(MORE)) { window() }
            shot("recordings-speed-$lang", 1000, 680, ko, clicks = listOf(SPEED)) { window() }
            shot("recordings-speaker-$lang", 1000, 680, ko, clicks = listOf(BADGE)) { window() }
            shot("recordings-edit-$lang", 1000, 680, ko, clicks = listOf(MORE, MORE_EDIT)) { window() }
            shot("recordings-edit-changed-$lang", 1000, 680, ko, clicks = listOf(MORE, MORE_EDIT, EDIT_FIELD), typed = "!") { window() }
            shot("recordings-speaker-change-$lang", 1000, 680, ko, clicks = listOf(BADGE, BADGE_CHANGE)) { window() }
            shot("recordings-search-$lang", 1000, 680, ko, clicks = listOf(SEARCH), typed = "release") { window() }
            shot("recordings-search-none-$lang", 1000, 680, ko, clicks = listOf(SEARCH), typed = "zzzz") { window() }
            val hit = model.search("release").first()
            model.openSearchHit(hit, "release")
            until { model.detail?.transcript != null }
            shot("recordings-find-$lang", 1000, 680, ko) { window() }
            shot("recordings-find-next-$lang", 1000, 680, ko, clicks = listOf(FIND_NEXT)) { window() }
            model.openDetail(model.recents.first())
            until { model.detail?.transcript != null && model.detail?.find == null }
            shot("settings-$lang", 640, SETTINGS_HEIGHT, ko) { SettingsWindow(model, strings) }
            shot("popup-$lang", 520, 560, ko) { TrayPopup(model, strings) {} }
            // 2026-11-04: the microphone and the output a start would capture, named before it.
            model.readDevices = { CaptureDevices("Microphone Array (Realtek(R) Audio)", "Speakers (Realtek(R) Audio)") }
            shot("popup-devices-$lang", 520, 560, ko) { TrayPopup(model, strings) {} }
            model.readDevices = { CaptureDevices.NONE }
        }

        // Right to left (Arabic), as `Main.Themed` lays it out: the Details window, Settings and the tray.
        model.selectLanguage(AppLanguage.ARABIC)
        model.localization.current.let { ar ->
            shot("recordings-ar", 1000, 680, false, rtl = true) { RecordingsWindow(model, ar) { it() } }
            shot("recordings-more-ar", 1000, 680, false, rtl = true, clicks = listOf(Offset(1000f - MORE.x, MORE.y))) { RecordingsWindow(model, ar) { it() } }
            shot("settings-ar", 640, SETTINGS_HEIGHT, false, rtl = true) { SettingsWindow(model, ar) }
            shot("popup-ar", 520, 560, false, rtl = true) { TrayPopup(model, ar) {} }
        }
        // D9: a list action's outcome over the Details list — a retry the queue refuses.
        model.selectLanguage(AppLanguage.ENGLISH)
        model.retry(model.recents.first().copy(jobId = "job-gone", jobStatus = recly.core.job.JobStatus.FAILED))
        until { model.listMessage != null }
        for (ko in listOf(false, true)) {
            model.selectLanguage(if (ko) AppLanguage.KOREAN else AppLanguage.ENGLISH)
            val strings = model.localization.current
            shot("recordings-list-message-${if (ko) "ko" else "en"}", 1000, 680, ko) { RecordingsWindow(model, strings) { it() } }
        }
        model.dismissListMessage()
        model.selectLanguage(AppLanguage.ENGLISH)

        // docs/09 "Notes view": notes made earlier — the overview, every group and a section of the user's own, with their
        // times — the More menu and Share over them, and Edit notes. The detail is opened again, and reads them once the
        // window is drawn and follows it.
        File(recordingDir, "$base.notes.v1.json").writeText(notesFile(model.detail!!.recordingId))
        model.openDetail(model.recents.first())
        until { model.detail?.transcript != null }
        for (ko in listOf(false, true)) {
            model.selectLanguage(if (ko) AppLanguage.KOREAN else AppLanguage.ENGLISH)
            val lang = if (ko) "ko" else "en"
            val strings = model.localization.current
            model.showNotes(true)
            val notesWindow: @Composable () -> Unit = { RecordingsWindow(model, strings) { it() } }
            shot("recordings-notes-$lang", 1000, 680, ko) { notesWindow() }
            // All of it in a taller window: the user's own section last, and the model with the copy icon under the notes.
            shot("recordings-notes-tall-$lang", 1000, 1100, ko) { notesWindow() }
            shot("recordings-notes-more-$lang", 1000, 680, ko, clicks = listOf(MORE)) { notesWindow() }
            shot("recordings-notes-export-$lang", 1000, 680, ko, clicks = listOf(EXPORT)) { notesWindow() }
            shot("recordings-notes-edit-$lang", 1000, 680, ko, clicks = listOf(MORE, MORE_EDIT_NOTES)) { notesWindow() }
            shot("recordings-notes-edit-changed-$lang", 1000, 680, ko, clicks = listOf(MORE, MORE_EDIT_NOTES, NOTES_FIELD), typed = "!") {
                notesWindow()
            }
            // docs/10 "Search": a word only the notes have — its line under the title, and the hit opening on them.
            model.showNotes(false)
            shot("recordings-search-notes-$lang", 1000, 680, ko, clicks = listOf(SEARCH), typed = "Thursday") { notesWindow() }
            val notesHit = model.search("Thursday").first()
            check(opensOnNotes(notesHit)) { "the hit is not the notes' alone: $notesHit" }
            model.openSearchHit(notesHit, "Thursday")
            until { model.detail?.loading == false && model.detail?.showingNotes == true }
            shot("recordings-search-notes-open-$lang", 1000, 680, ko) { notesWindow() }
            model.openDetail(model.recents.first())
            until { model.detail?.transcript != null && model.detail?.find == null }
        }
        model.selectLanguage(AppLanguage.ENGLISH)

        // docs/08 "Memo": a memo of the recording — the Memo chip after Transcript and Notes and its view, and a word only
        // the memo has, its line under the title and the hit opening on it. Emptied after, so the shots below are as before.
        val memoOf = model.detail!!.recordingId
        model.saveMemo(memoOf, "Agenda: the release date, the speed controls\nMina, Joon")
        model.observeMemo(memoOf).first { it.isNotEmpty() }
        for (ko in listOf(false, true)) {
            model.selectLanguage(if (ko) AppLanguage.KOREAN else AppLanguage.ENGLISH)
            val lang = if (ko) "ko" else "en"
            val strings = model.localization.current
            val window: @Composable () -> Unit = { RecordingsWindow(model, strings) { it() } }
            model.showMemo()
            shot("recordings-memo-$lang", 1000, 680, ko) { window() }
            model.showNotes(false)
            shot("recordings-search-memo-$lang", 1000, 680, ko, clicks = listOf(SEARCH), typed = "Joon") { window() }
            val memoHit = model.search("Joon").first()
            check(opensOnMemo(memoHit)) { "the hit is not the memo's alone: $memoHit" }
            model.openSearchHit(memoHit, "Joon")
            until { model.detail?.loading == false && model.detail?.showingMemo == true }
            shot("recordings-search-memo-open-$lang", 1000, 680, ko) { window() }
            model.openDetail(model.recents.first())
            until { model.detail?.transcript != null && model.detail?.find == null }
        }
        model.saveMemo(memoOf, "")
        model.observeMemo(memoOf).first { it.isEmpty() }
        model.selectLanguage(AppLanguage.ENGLISH)

        // Signed in, drawn without signing in (nothing here talks to OpenAI): the menu's Organize again enabled, and
        // Settings with the model, and Notes with instructions and a section of the user's own.
        val chatGpt = model.chatGpt!!
        chatGpt.connection = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5.4", "GPT-5.4")), "gpt-5.4")
        chatGpt.saveNotesInstructions("Short lines. Name who owns each to-do.")
        chatGpt.endSection(chatGpt.addSection()!!, "Risks", "What could go wrong, and who is watching it.")
        until { chatGpt.sections.lastOrNull { !it.builtIn }?.id?.isNotEmpty() == true }
        for (ko in listOf(false, true)) {
            model.selectLanguage(if (ko) AppLanguage.KOREAN else AppLanguage.ENGLISH)
            val lang = if (ko) "ko" else "en"
            val strings = model.localization.current
            val window: @Composable () -> Unit = { RecordingsWindow(model, strings) { it() } }
            model.showNotes(true)
            shot("recordings-notes-more-signed-in-$lang", 1000, 680, ko, clicks = listOf(MORE)) { window() }
            // docs/08 "Titles and speaker names": the title dialog's card, signed in and transcribing, its field saying a
            // title left empty is filled in automatically.
            shot("title-sheet-$lang", 460, 520, ko) {
                TitleSheet(model, strings) { actions, content ->
                    DialogCard(strings[Str.RECORDING_TITLE], androidx.compose.ui.Modifier, actions, content, fill = false)
                }
            }
            // More → Fill in title with a title to fill, Fill in speaker names with every speaker named, and what a
            // failure says: the plan is drawn signed in, but nothing here is, so the core answers that a sign-in is needed.
            val named = (model.detail!!.title as app.recly.windows.i18n.UiMessage.Text).value
            model.rename(RenameRequest(model.detail!!.recordingId, named), "")
            until { model.detail?.title !is app.recly.windows.i18n.UiMessage.Text }
            shot("recordings-fill-more-$lang", 1000, 680, ko, clicks = listOf(MORE)) { window() }
            model.fill(FillHalf.TITLE)
            until { model.detail?.fillFailure != null }
            shot("recordings-fill-failed-$lang", 1000, 680, ko) { window() }
            until { model.detail?.fillFailure == null }
            // A fill running, drawn without asking ChatGPT: the row's `Filling in`, the detail's two lines, both items
            // waiting for it, and the tray's row.
            model.fills = listOf(recly.core.chatgpt.FillRun(model.detail!!.recordingId, title = true, names = true))
            shot("recordings-filling-$lang", 1000, 680, ko) { window() }
            shot("recordings-filling-more-$lang", 1000, 680, ko, clicks = listOf(MORE)) { window() }
            shot("popup-filling-$lang", 520, 560, ko) { TrayPopup(model, strings) {} }
            model.fills = emptyList()
            model.rename(RenameRequest(model.detail!!.recordingId, ""), named)
            until { (model.detail?.title as? app.recly.windows.i18n.UiMessage.Text)?.value == named }
            shot("settings-signed-in-$lang", 640, SETTINGS_HEIGHT, ko) { SettingsWindow(model, strings) }
            // docs/09 "Ask": the foot of the detail — the composer, then over it a request running, answered with what it
            // changed, changed only, changing nothing and failed — states drawn, not asked.
            val presets = AskPreset.entries
            val bar: @Composable (AskState, String) -> Unit = { state, typed ->
                Column(
                    androidx.compose.ui.Modifier.fillMaxSize().border(1.dp, blueprint.grid).background(blueprint.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // As [AskBar] has them in the 680 of the recordings window: a third of it for the answer, no chips under one.
                    if (state != AskState.None) {
                        AskResult(state, { _, _ -> "GPT-5.4" }, undoing = false, onUndo = {}, onAsk = { _, _ -> }, onClose = {}, onManageUsage = {},
                            canPlay = true, onPlay = {}, strings = strings, answerHeight = answerHeight(680.dp))
                    }
                    AskComposer(TextFieldValue(typed), {}, presets.takeIf { state == AskState.None }.orEmpty(), null, state is AskState.Running,
                        { _, _ -> }, strings)
                }
            }
            val recordingId = model.detail!!.recordingId
            val changes = recly.core.chatgpt.AskChanges(listOf(1, 3), names = 0, items = listOf("d1"), removed = 0, overview = false)
            shot("ask-$lang", 460, 140, ko) { bar(AskState.None, "") }
            // The field with the cursor and nothing in it: the presets over it, at the status badge's size (2026-11-04).
            shot("ask-presets-$lang", 460, 180, ko, clicks = listOf(ASK_FIELD)) { bar(AskState.None, "") }
            shot("ask-typed-$lang", 460, 160, ko) { bar(AskState.None, ASK_REQUEST) }
            shot("ask-running-$lang", 460, 200, ko) { bar(AskState.Running(null, ASK_REQUEST), "") }
            // The next request typed while one runs: the field takes it, Send waits (2026-11-04).
            shot("ask-running-typed-$lang", 460, 200, ko) { bar(AskState.Running(null, ASK_REQUEST), ASK_REQUEST) }
            shot("ask-ready-$lang", 460, 360, ko) {
                bar(AskState.Ready(AskAnswer(recordingId, null, ASK_REQUEST, ANSWER, "gpt-5.4", changes = changes)), "")
            }
            shot("ask-changed-$lang", 460, 200, ko) { bar(AskState.Ready(AskAnswer(recordingId, null, ASK_REQUEST, "", "gpt-5.4", changes = changes)), "") }
            shot("ask-nothing-$lang", 460, 200, ko) { bar(AskState.Ready(AskAnswer(recordingId, null, ASK_REQUEST, "", "gpt-5.4")), "") }
            shot("ask-failed-$lang", 460, 300, ko) {
                bar(AskState.Failed(recly.core.message.CoreMessage.CHATGPT_USAGE_LIMIT.code(), null, ASK_REQUEST), "")
            }
            // docs/09 "Version history": the transcript shown first with no Restore, a restore running, and one refused.
            val history: @Composable (String?, Str?) -> Unit = { restoring, refused ->
                DialogCard(
                    strings[Str.VERSIONS_TITLE], androidx.compose.ui.Modifier, null,
                    { VersionsPanel(VERSIONS, restoring, refused, {}, strings) }, fill = false, closeLabel = strings[Str.CLOSE],
                )
            }
            shot("versions-$lang", 460, 420, ko) { history(null, null) }
            shot("versions-restoring-$lang", 460, 420, ko) { history(VERSIONS[1].id, null) }
            shot("versions-refused-$lang", 460, 440, ko) { history(null, Str.VERSIONS_RESTORE_FAILED) }
            // docs/09 "Ask": the reader with the lines an answer changed tinted.
            val sampled = model.detail!!.transcript!!
            val document = recly.core.transcribe.TranscriptDocument(sampled)
            shot("reader-changed-$lang", 700, 420, ko) {
                TranscriptReader(
                    sampled, document, canSeek = true, onSeek = {}, positionSec = 0.0, playing = false,
                    matches = emptyList(), currentMatch = null, marked = document.segmentRanges(changes.segments),
                    onChangeSpeaker = { _, _, _ -> }, onRenameSpeaker = { _, _ -> }, saving = emptyMap(),
                    strings = strings, modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                )
            }
            // And the summary with the overview it rewrote and an item it changed tinted.
            val notes = model.detail!!.notes!!
            shot("notes-changed-$lang", 700, 620, ko) {
                Column(androidx.compose.ui.Modifier.fillMaxSize().background(blueprint.background).padding(16.dp)) {
                    NotesBlock(
                        notes, canSeek = true, onSeek = {}, linesAround = { emptyList() }, onDelete = {}, onDone = { _, _ -> }, strings = strings,
                        changed = setOf(notes.decisions.first().id), overviewChanged = true,
                    )
                }
            }
        }
        chatGpt.connection = ChatGptConnection.SignedOut
        // The notes go with the file they were seeded as, and the detail opened again is the transcript's alone.
        File(recordingDir, "$base.notes.v1.json").delete()
        model.selectLanguage(AppLanguage.ENGLISH)
        model.openDetail(model.recents.first())
        until { model.detail?.transcript != null }

        // Skip silence on, in the dark; and the reader following playback, then scrolled away from it.
        model.selectLanguage(AppLanguage.ENGLISH)
        model.toggleSkipSilence(true)
        shot("recordings-dark-skip-en", 1000, 680, false, dark = true) { RecordingsWindow(model, model.localization.current) { it() } }
        model.toggleSkipSilence(false)
        val transcript = model.detail!!.transcript!!.let { short ->
            short.copy(segments = (0 until 10).flatMap { k -> short.segments.map { it.copy(start = it.start + k * 40, end = it.end + k * 40) } })
        }
        val reader: @Composable () -> Unit = {
            TranscriptReader(
                transcript, recly.core.transcribe.TranscriptDocument(transcript), canSeek = true, onSeek = {}, positionSec = 182.0, playing = true,
                matches = emptyList(), currentMatch = null,
                onChangeSpeaker = { _, _, _ -> }, onRenameSpeaker = { _, _ -> }, saving = mapOf(2 to InlineSave.SAVING),
                strings = model.localization.current, modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            )
        }
        shot("reader-follow-en", 700, 420, false) { reader() }
        shot("reader-scrolled-en", 700, 420, false, scroll = Offset(300f, 300f)) { reader() }

        // An import ffmpeg cannot read leaves a notice; a long one is IMPORTING while it runs.
        model.selectLanguage(AppLanguage.ENGLISH)
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.OFF, "en") }
        processing.save()
        until { processing.summary.mode == TranscriptionMode.OFF }
        val strings = model.localization.current
        model.importFiles(listOf(File(dir, "notes.pdf").apply { writeText("not media") }))
        until { model.importFailure != null }
        shot("recordings-import-failed-en", 1000, 680, false) { RecordingsWindow(model, strings) { it() } }
        val long = File(dir, "Long interview.wav")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "anoisesrc=duration=2400:sample_rate=16000:amplitude=0.2", long.path)
        model.importFiles(listOf(long))
        until { model.recents.any { it.state.let { s -> (s as? app.recly.windows.i18n.UiMessage.Res)?.key == app.recly.windows.i18n.Str.STATE_IMPORTING } } }
        shot("recordings-import-en", 1000, 680, false) { RecordingsWindow(model, strings) { it() } }
        shot("popup-importing-en", 520, 560, false) { TrayPopup(model, strings) {} }

        // The tray popup while a recording runs.
        val before = model.recents.map { it.id }.toSet()
        model.start()
        until { model.recording }
        delay(1_500)
        shot("popup-recording-en", 520, 560, false) { TrayPopup(model, strings) {} }
        // docs/08 "Memo": the take's row with the first line of a memo typed into it.
        val live = model.liveRecordingId!!
        model.saveMemo(live, "Agenda: the release date\nMina, Joon")
        model.observeMemo(live).first { it.isNotEmpty() }
        shot("popup-recording-memo-en", 520, 560, false) { TrayPopup(model, strings) {} }
        // Its row clicked: the field open under it, which another click on the row folds.
        shot("popup-recording-memo-open-en", 520, 560, false, clicks = listOf(TRAY_MEMO)) { TrayPopup(model, strings) {} }
        // 2026-10-15: More over the take still being written — every item waits, Delete too, each saying why.
        until { model.recents.any { it.id !in before } }
        model.openDetail(model.recents.first { it.id !in before })
        until { model.detail?.loading == false }
        shot("recordings-more-recording-en", 1000, 680, false, clicks = listOf(MORE)) { RecordingsWindow(model, strings) { it() } }
        model.stop()
        until { !model.recording }
        // The fake helper writes no audio here, so nothing of this take reached storage: Transcribe again cannot run.
        val short = model.recents.first { it.id !in before }

        // The speaker models missing on a PC that has the speech model: files of the right sizes stand in for it.
        val models = File(dir, "data/models/${recly.core.transcribe.Qwen3Asr.DIRECTORY}")
        recly.core.transcribe.Qwen3Asr.files.forEach { file ->
            File(models, file.name).apply { parentFile.mkdirs() }.let { java.io.RandomAccessFile(it, "rw").use { raf -> raf.setLength(file.bytes) } }
        }
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.LOCAL, "en") }
        processing.save()
        until { processing.summary.mode == TranscriptionMode.LOCAL }
        processing.refreshLocal()
        model.modelDownload?.refresh()
        delay(500)
        shot("settings-speaker-download-en", 640, 2700, false) { SettingsWindow(model, strings) }
        model.openDetail(short)
        until { model.detail?.loading == false }
        shot("recordings-more-not-uploaded-en", 1000, 680, false, clicks = listOf(MORE)) { RecordingsWindow(model, strings) { it() } }
        model.retranscribe(RetranscribeRequest(short.id, provider = null, language = recly.core.model.Language.EN, replacesEdits = false))
        until { model.detail?.notice != null }
        shot("recordings-retranscribe-refused-en", 1000, 680, false) { RecordingsWindow(model, strings) { it() } }
        // 2026-10-15: a failed recording on show — Retry and Check the key under its row, and no Delete, which is More's.
        // A provider with no key stored fails the transcription before it calls anyone.
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.EXTERNAL, "en") }
        processing.save()
        runCatching { until { processing.summary.mode == TranscriptionMode.EXTERNAL } }.onFailure { error("save: ${processing.message}") }
        val call = File(dir, "Call.wav")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=10:sample_rate=16000", call.path)
        val known = model.recents.map { it.id }.toSet()
        model.importFiles(listOf(call))
        until { model.recents.any { it.id !in known && it.jobStatus == recly.core.job.JobStatus.FAILED } }
        model.openDetail(model.recents.first { it.id !in known })
        until { model.detail?.loading == false }
        for (ko in listOf(false, true)) {
            model.selectLanguage(if (ko) AppLanguage.KOREAN else AppLanguage.ENGLISH)
            shot("recordings-failed-${if (ko) "ko" else "en"}", 1000, 680, ko) { RecordingsWindow(model, model.localization.current) { it() } }
        }
        model.selectLanguage(AppLanguage.ENGLISH)
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.LOCAL, "en") }
        processing.save()
        until { processing.summary.mode == TranscriptionMode.LOCAL }
        // The folder choice is a local folder's alone (2026-10-09): with Google Drive chosen, the panel has none.
        model.selectStorage(StorageKind.DRIVE)
        until { model.storage == StorageKind.DRIVE && !showsFolderChoice(processing.stored) }
        shot("settings-drive-en", 640, 2700, false) { SettingsWindow(model, strings) }
        model.shutdown()
    }

    /**
     * docs/09 "To-dos view", over a shell of its own with three recordings: the list's button, the view with open, held and
     * done rows, a group opening its recording, a to-do checked in the detail, and the view going back by itself. The boxes
     * and the buttons are pressed, so what is on disk is what is drawn.
     */
    @Test
    fun `the to-dos view as it looks`() = runBlocking {
        val out = out ?: return@runBlocking println("SHOTS skipped — no -Drecly.shots")
        out.mkdirs()
        val dir = File("build/shots-todos-data").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val folder = File(dir, "folder").apply { mkdirs() }
        val model = ShellModel(localization = Localization(FakeSettings(localFolder = folder.path, consentReminder = false)) { "en" })
        model.load(dataDirectory = File(dir, "data").path.toPath(), helperCommand = FakeHelperCommand.command())
        until {
            model.selectStorage(StorageKind.FOLDER)
            model.storage == StorageKind.FOLDER
        }
        model.useLocalFolder(folder.path)
        val processing = model.processing!!
        until { processing.draft != null }
        delay(500)
        processing.edit { it.selectTranscriptionMode(TranscriptionMode.OFF, "en") }
        processing.save()
        runCatching { until { processing.summary.mode == TranscriptionMode.OFF } }.onFailure { error("save: ${processing.message}") }

        // Oldest first, so that the list reads newest first as the view does.
        val now = System.currentTimeMillis()
        val names = listOf("Share", "Customer call about the Hanbit renewal and the pricing follow-up", "Weekly sync")
        names.forEachIndexed { index, name ->
            val audio = File(dir, "$name.wav")
            run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=300:duration=40:sample_rate=16000", audio.path)
            audio.setLastModified(now - (names.size - index) * 86_400_000L)
            model.importFiles(listOf(audio))
            until { model.recents.size == index + 1 && model.recents.all { it.jobStatus?.name == "DONE" } }
        }
        val share = model.recents.single { (it.title as? UiMessage.Text)?.value == "Share" }.id
        val customer = model.recents.single { (it.title as? UiMessage.Text)?.value == names[1] }.id
        val weekly = model.recents.single { (it.title as? UiMessage.Text)?.value == "Weekly sync" }.id
        // The row's fallback: a recording with no title is Untitled in the view too.
        model.rename(RenameRequest(share, "Share"), "")
        until { model.recents.any { it.id == share && it.title !is UiMessage.Text } }

        fun seed(recordingId: String, vararg items: String) {
            val recording = File(dir, "data/recordings").listFiles()!!.single { candidate ->
                candidate.listFiles()!!.first { it.name.endsWith(".meta.json") }.readText().contains(recordingId)
            }
            val base = recording.listFiles()!!.first { it.name.endsWith(".meta.json") }.name.removeSuffix(".meta.json")
            File(recording, "$base.transcript.json").writeText(Json.encodeToString(Transcript.serializer(), sample(recordingId)))
            File(recording, "$base.notes.v1.json").writeText(
                """{"schema":1,"recordingId":"$recordingId","stage":"final",""" +
                    """"overview":"A short release review: the import button and the speed controls are in, and the release ships on Friday.",""" +
                    """"items":[${items.joinToString(",")}],"conversations":[{"startSec":0.0,"endSec":40.0}],"model":"gpt-5.4","modelName":"GPT-5.4",""" +
                    """"createdAt":"2026-11-06T10:00:00Z","updatedAt":"2026-11-06T10:05:00Z","finalizedAt":"2026-11-06T10:05:00Z"}""",
            )
        }
        fun item(id: String, kind: String, text: String, atSec: Double, extra: String = "") =
            """{"id":"$id","kind":"$kind","text":"$text","evidence":{"atSec":$atSec,"quote":""},"provisional":false,""" +
                """"conversation":0$extra,"createdAt":"2026-11-06T10:05:00Z"}"""
        val done = ""","doneAt":"2026-11-06T11:00:00Z""""
        seed(
            weekly,
            item("w0", "decision", "Ship the release on Friday.", 31.0),
            item("w1", "todo", "Publish the release notes.", 20.0, ""","owner":"S1","due":"Thursday""""),
            item("w2", "todo", "Check the speed controls on the player bar before the release goes out to everyone on the list.", 11.5, ""","owner":"S2""""),
            item("w3", "todo", "Move the import button to the list header.", 6.0, done),
        )
        seed(
            customer,
            item("c1", "todo", "Send the quote.", 20.0, ""","due":"Friday""""),
            item("c2", "todo", "Book the follow-up call.", 6.0, done),
        )
        seed(share, item("s1", "todo", "Share the recording with Joon.", 6.0, done))
        suspend fun set(recordingId: String, itemId: String, value: Boolean) {
            model.setTodoDone(recordingId, itemId, value)
            withTimeout(30_000) { model.observeTodos().first { all -> all.flatMap { it.items }.single { it.id == itemId }.done == value } }
        }

        val window: @Composable () -> Unit = { RecordingsWindow(model, model.localization.current) { it() } }
        // Light and dark in English, and the plain states in Korean and right to left.
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            shot("todos-list-$theme", 1000, 680, false, dark = dark) { window() }
            shot("todos-view-$theme", 1000, 680, false, dark = dark, clicks = listOf(TODOS)) { window() }
            shot("todos-done-open-$theme", 1000, 680, false, dark = dark, clicks = listOf(TODOS, TODOS_DONE)) { window() }
            // Checked here: it stays where it is, struck through, and the header counts one fewer.
            shot("todos-held-$theme", 1000, 680, false, dark = dark, clicks = listOf(TODOS, TODOS_FIRST_BOX)) { window() }
            set(weekly, "w1", false)
            // Left and opened again, it has gone behind `Done`.
            shot("todos-held-left-$theme", 1000, 680, false, dark = dark, clicks = listOf(TODOS, TODOS_FIRST_BOX, TODOS_BACK, TODOS)) { window() }
            set(weekly, "w1", false)
            // A group's header opens the recording on its Summary chip, with its done to-do where it was.
            shot("todos-detail-$theme", 1000, 680, false, dark = dark, clicks = listOf(TODOS, TODOS_FIRST_GROUP)) { window() }
            check(model.detail?.recordingId == weekly && model.detail?.showingNotes == true) { "the group did not open on its summary: ${model.detail}" }
            // Its box in the detail, with the view beside it.
            shot("todos-detail-checked-$theme", 1000, 680, false, dark = dark, clicks = listOf(TODOS, TODOS_DETAIL_BOX)) { window() }
            check(model.observeTodos().first().flatMap { it.items }.single { it.id == "w1" }.done) { "the detail's box did not check it" }
            set(weekly, "w1", false)
        }
        // The keyboard's focus on the box, which on a ticked one is the ink on its fill.
        for (ticked in listOf(false, true)) {
            shot("todos-focus-${if (ticked) "ticked" else "open"}-light", 500, 60, false, tabs = 2) {
                Column(androidx.compose.ui.Modifier.fillMaxSize().background(blueprint.surface)) {
                    TodoRow(
                        recly.core.notes.NoteItem("f", recly.core.notes.NoteKind.TODO, "Send the quote.", null, "Friday", 20.0, "", provisional = false, done = ticked),
                        onDone = {}, onOpen = {},
                    )
                }
            }
        }
        // The header in the languages with the longest words: the buttons under the title, and the title whole.
        for ((language, lang) in listOf(AppLanguage.KOREAN to "ko", AppLanguage.GERMAN to "de", AppLanguage.RUSSIAN to "ru", AppLanguage.FILIPINO to "fil")) {
            model.selectLanguage(language)
            val strings = model.localization.current
            shot("todos-list-$lang", 1000, 680, language == AppLanguage.KOREAN) { RecordingsWindow(model, strings) { it() } }
        }
        model.selectLanguage(AppLanguage.KOREAN)
        model.localization.current.let { strings ->
            shot("todos-view-ko", 1000, 680, true, clicks = listOf(TODOS_KO)) { RecordingsWindow(model, strings) { it() } }
        }
        model.selectLanguage(AppLanguage.ARABIC)
        model.localization.current.let { strings ->
            shot("todos-view-ar", 1000, 680, false, rtl = true, clicks = listOf(Offset(1000f - TODOS_AR.x, TODOS_AR.y))) { RecordingsWindow(model, strings) { it() } }
        }
        model.selectLanguage(AppLanguage.ENGLISH)

        // Nothing open: the done rows are simply there, with no `Done` row.
        set(weekly, "w1", true)
        set(weekly, "w2", true)
        set(customer, "c1", true)
        shot("todos-all-done-light", 1000, 680, false, clicks = listOf(TODOS)) { window() }
        // The last to-do gone with the view on show: it goes back by itself, and the button with it.
        shot("todos-none-left-light", 1000, 680, false, clicks = listOf(TODOS), after = {
            listOf(weekly to "w1", weekly to "w2", weekly to "w3", customer to "c1", customer to "c2", share to "s1").forEach { (recordingId, itemId) ->
                model.deleteNote(recordingId, itemId)
            }
            withTimeout(30_000) { model.observeTodos().first { it.isEmpty() } }
        }) { window() }
        model.shutdown()
    }

    /** `{base}.notes.v1.json` as the final pass writes it (`spec/notes.schema.json`), over [sample]'s lines. */
    private fun notesFile(recordingId: String): String {
        fun item(id: String, kind: String, text: String, atSec: Double, quote: String, extra: String = "") =
            """{"id":"$id","kind":"$kind","text":"$text","evidence":{"atSec":$atSec,"quote":"$quote"},"provisional":false,""" +
                """"conversation":0$extra,"createdAt":"2026-10-09T10:05:00Z"}"""
        val items = listOf(
            item("n1", "point", "The import button moved to the list header.", 6.0, "The import button is in the list header now"),
            item("n2", "point", "The speed controls show on the player bar.", 20.0, "They did"),
            item("n3", "decision", "Ship the release on Friday.", 31.0, "let's ship the release on Friday"),
            item("n4", "todo", "Publish the release notes by Thursday.", 20.0, "the release notes mention the waveform change", ""","owner":"S1","due":"Thursday""""),
            item("n5", "question", "Did the speed controls land on the player bar?", 11.5, "Did the speed controls land on the player bar"),
            item("n6", "section", "The waveform change could be missed in the notes.", 20.0, "mention the waveform change", ""","section":"s1""""),
        )
        return """{"schema":1,"recordingId":"$recordingId","stage":"final",""" +
            """"overview":"A short release review: the import button and the speed controls are in, and the release ships on Friday.",""" +
            """"items":[${items.joinToString(",")}],"conversations":[{"startSec":0.0,"endSec":40.0}],"model":"gpt-5.4","modelName":"GPT-5.4",""" +
            """"sections":[{"id":"s1","name":"Risks","description":"What could go wrong."}],""" +
            """"createdAt":"2026-10-09T10:00:00Z","updatedAt":"2026-10-09T10:05:00Z","finalizedAt":"2026-10-09T10:05:00Z"}"""
    }

    private fun sample(recordingId: String) = Transcript(
        recordingId = recordingId,
        track = Track.MONO,
        language = "en",
        provider = TranscriptProvider("assemblyai"),
        createdAt = "2026-10-07T00:00:00Z",
        durationSec = 40.0,
        // S2 is the person who made the recording (docs/08 "Me and others"), shown as Me.
        speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", me = true)),
        segments = listOf(
            TranscriptSegment(0.0, 6.0, "S1", "Thanks for joining. Let's go over the release notes for Recly first."),
            TranscriptSegment(6.0, 11.5, "S1", "The import button is in the list header now."),
            TranscriptSegment(11.5, 20.0, "S2", "Good. Did the speed controls land on the player bar as well?"),
            TranscriptSegment(20.0, 31.0, "S1", "They did, and the release notes mention the waveform change too."),
            TranscriptSegment(31.0, 40.0, "S2", "Then let's ship the release on Friday."),
        ),
    )

    /** Renders [content] at twice its size, after [clicks] (in dp) and [typed], and waits for what they start. */
    private suspend fun shot(
        name: String,
        width: Int,
        height: Int,
        ko: Boolean,
        clicks: List<Offset> = emptyList(),
        typed: String = "",
        dark: Boolean = false,
        /** A wheel turn at this point (dp), after the clicks. */
        scroll: Offset? = null,
        /** Laid out right to left, as `Main.Themed` lays out Arabic, Persian and Urdu. */
        rtl: Boolean = false,
        /** Tab presses, after the clicks: what the keyboard's focus ring looks like. */
        tabs: Int = 0,
        /** Run once the clicks have been drawn, with the scene still up. */
        after: (suspend () -> Unit)? = null,

        content: @Composable () -> Unit,
    ): Unit = withContext(Dispatchers.Swing) {
        // On the event dispatch thread, where the app's windows run, and so is everything the scene starts. With the
        // default context (`Dispatchers.Unconfined`) its recomposition and effects ran on whichever thread woke them
        // while the test sent clicks from its own, and a click could set its state and never be recomposed: the menu it
        // opened was never drawn, and the shot failed as a stale click point (2026-11-04, while another app launched).
        val scene = ImageComposeScene(width * SCALE, height * SCALE, Density(SCALE.toFloat()), coroutineContext = Dispatchers.Swing) {
            ReclyDesktopTheme(dark = dark, highContrast = false, tracked = !ko) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { content() }
            }
        }
        try {
            settle(scene)
            // A click point read off an older layout lands on nothing, and the shot comes out as the plain window
            // under the name of a state it never reached.
            val untouched = if (clicks.isEmpty()) null else scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            clicks.forEach { at ->
                val px = Offset(at.x * SCALE, at.y * SCALE)
                scene.sendPointerEvent(PointerEventType.Move, px)
                scene.sendPointerEvent(PointerEventType.Press, px, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, px, buttons = PointerButtons(), button = PointerButton.Primary)
                settle(scene)
            }
            val source = java.awt.Canvas()
            repeat(tabs) {
                for ((id, type) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
                    val key = java.awt.event.KeyEvent(source, id, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_TAB, '\t')
                    scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(Key.Tab, type, codePoint = '\t'.code, nativeEvent = key))
                }
            }
            if (tabs > 0) settle(scene)
            after?.let {
                it()
                settle(scene)
            }
            typed.forEach { char ->
                val key = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = key))
            }
            if (typed.isNotEmpty()) settle(scene)
            scroll?.let { at ->
                val px = Offset(at.x * SCALE, at.y * SCALE)
                scene.sendPointerEvent(PointerEventType.Move, px)
                repeat(3) { scene.sendPointerEvent(PointerEventType.Scroll, px, scrollDelta = Offset(0f, 2f)) }
                settle(scene)
            }
            val bytes = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(bytes)
            check(untouched == null || !untouched.contentEquals(bytes)) { "$name: the clicks at $clicks changed nothing — a stale click point?" }
        } finally {
            scene.close()
        }
    }

    /** Suspends between frames rather than sleeping: the thread is the scene's, and runs what each frame started. */
    private suspend fun settle(scene: ImageComposeScene) {
        repeat(FRAMES) {
            scene.render(System.nanoTime())
            delay(FRAME_MS)
        }
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(50) }


    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { command.toList().toString() }
    }

    private companion object {
        const val SCALE = 2
        const val FRAMES = 12
        const val FRAME_MS = 60L
        // Where the controls are in the 1000×680 window, in dp — read off the plain shot.
        val EXPORT = Offset(890f, 38f)
        val MORE = Offset(966f, 38f)
        val SPEED = Offset(560f, 150f)
        val BADGE = Offset(420f, 215f)
        val MORE_EDIT = Offset(835f, 130f)
        val EDIT_FIELD = Offset(700f, 250f)
        val BADGE_CHANGE = Offset(534f, 307f)
        val SEARCH = Offset(150f, 103f)
        val FIND_NEXT = Offset(900f, 210f)
        // Edit summary, under Summarize again and its reason — below Version history and Notes in the first group (2026-11-04).
        val MORE_EDIT_NOTES = Offset(835f, 359f)
        // The Ask field in its 460-wide shot, where a click gives it the cursor.
        val ASK_FIELD = Offset(120f, 38f)
        // The tray popup's Memo row while recording, in the 520×560 popup.
        val TRAY_MEMO = Offset(60f, 300f)
        // The decision's field in Edit notes, past the end of its words.
        val NOTES_FIELD = Offset(900f, 513f)
        // The to-dos view in the 1000×680 window, with [ShotsTest]'s three recordings seeded for it.
        val TODOS = Offset(120f, 38f)
        val TODOS_KO = Offset(136f, 38f)
        val TODOS_AR = Offset(159f, 38f)
        val TODOS_BACK = Offset(240f, 38f)
        val TODOS_FIRST_GROUP = Offset(150f, 95f)
        val TODOS_FIRST_BOX = Offset(25f, 135f)
        val TODOS_DONE = Offset(60f, 369f)
        val TODOS_DETAIL_BOX = Offset(326f, 411f)
        const val SETTINGS_HEIGHT = 3100

        const val ASK_REQUEST = "Mina's name is spelled Minna. Who publishes the release notes?"

        val VERSIONS = listOf(
            recly.core.transcribe.TranscriptVersion("4", recly.core.transcribe.TranscriptVersionKind.CORRECTED, "2026-11-04T09:12:00Z", current = true),
            recly.core.transcribe.TranscriptVersion("3", recly.core.transcribe.TranscriptVersionKind.EDITED, "2026-11-04T08:40:00Z", current = false),
            recly.core.transcribe.TranscriptVersion("2", recly.core.transcribe.TranscriptVersionKind.RECEIVED, "2026-11-03T18:05:00Z", current = false),
            recly.core.transcribe.TranscriptVersion("1", recly.core.transcribe.TranscriptVersionKind.TRANSCRIBED, "2026-11-03T17:58:00Z", current = false),
        )

        const val ANSWER = "- Mina: publish the release notes by Thursday [00:00:20]\n" +
            "- Unassigned: check the speed controls on the player bar [00:00:11]\n- Ship the release on Friday [00:00:31]"
    }
}
