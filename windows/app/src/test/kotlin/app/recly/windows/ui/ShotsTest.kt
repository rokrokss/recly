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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.recly.windows.FakeSettings
import app.recly.windows.TitleSheet
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.ui.component.DialogCard
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.theme.blueprint
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            // docs/08 "Ask": the panel's body as the dialog holds it, running and answered — states drawn, not asked.
            val presets = AskPreset.entries
            val panel: @Composable (AskState) -> Unit = { state ->
                Column(
                    androidx.compose.ui.Modifier.fillMaxSize().border(1.dp, blueprint.grid).background(blueprint.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(strings[Str.ASK_TITLE], style = MaterialTheme.typography.titleMedium, color = blueprint.text)
                    AskPanel(state, presets, { _, _ -> "GPT-5.4" }, canPlay = true, onPlay = {}, onAsk = { _, _ -> }, onManageUsage = {}, strings = strings)
                }
            }
            shot("ask-running-$lang", 460, 560, ko) { panel(AskState.Running(AskPreset.ACTION_ITEMS, null)) }
            shot("ask-ready-$lang", 460, 560, ko) {
                panel(AskState.Ready(AskAnswer(model.detail!!.recordingId, AskPreset.ACTION_ITEMS, null, ANSWER, "gpt-5.4")))
            }
            shot("ask-failed-$lang", 460, 560, ko) {
                panel(AskState.Failed(recly.core.message.CoreMessage.CHATGPT_USAGE_LIMIT.code(), null, "When do we ship?"))
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
        // Its row clicked: the field open in place, with Done.
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
    private fun shot(
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

        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width * SCALE, height * SCALE, Density(SCALE.toFloat())) {
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

    private fun settle(scene: ImageComposeScene) {
        repeat(FRAMES) {
            scene.render(System.nanoTime())
            Thread.sleep(FRAME_MS)
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
        val MORE_EDIT_NOTES = Offset(835f, 265f)
        // The tray popup's Memo row while recording, in the 520×560 popup.
        val TRAY_MEMO = Offset(60f, 300f)
        // The decision's field in Edit notes, past the end of its words.
        val NOTES_FIELD = Offset(900f, 513f)
        const val SETTINGS_HEIGHT = 3100

        const val ANSWER = "- Mina: publish the release notes by Thursday [00:00:20]\n" +
            "- Unassigned: check the speed controls on the player bar [00:00:11]\n- Ship the release on Friday [00:00:31]"
    }
}
