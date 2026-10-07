@file:OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.ui.theme.ReclyDesktopTheme
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
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
        model.setHighlights(model.detail!!.recordingId, listOf(8.0, 27.5))
        until { model.detail?.highlights?.size == 2 }

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
            shot("recordings-tick-$lang", 1000, 680, ko, clicks = listOf(TICK)) { window() }
            shot("recordings-edit-$lang", 1000, 680, ko, clicks = listOf(MORE, MORE_EDIT)) { window() }
            shot("recordings-edit-changed-$lang", 1000, 680, ko, clicks = listOf(MORE, MORE_EDIT, EDIT_FIELD), typed = "!") { window() }
            shot("recordings-speaker-change-$lang", 1000, 680, ko, clicks = listOf(BADGE, BADGE_CHANGE)) { window() }
            shot("recordings-flag-$lang", 1000, 680, ko, clicks = listOf(FLAG)) { window() }
            shot("recordings-flag-remove-$lang", 1000, 680, ko, clicks = listOf(FLAG, FLAG_REMOVE)) { window() }
            model.setHighlights(model.detail!!.recordingId, listOf(8.0, 27.5))
            shot("recordings-search-$lang", 1000, 680, ko, clicks = listOf(SEARCH), typed = "release") { window() }
            shot("recordings-search-none-$lang", 1000, 680, ko, clicks = listOf(SEARCH), typed = "zzzz") { window() }
            val hit = model.search("release").first()
            model.openSearchHit(hit, "release")
            until { model.detail?.transcript != null }
            shot("recordings-find-$lang", 1000, 680, ko) { window() }
            shot("recordings-find-next-$lang", 1000, 680, ko, clicks = listOf(FIND_NEXT)) { window() }
            model.openDetail(model.recents.first())
            until { model.detail?.transcript != null && model.detail?.find == null }
            shot("settings-$lang", 640, 2700, ko) { SettingsWindow(model, strings) }
        }

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
                highlights = listOf(8.0, 27.5), onRemoveHighlight = {}, matches = emptyList(), currentMatch = null,
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

        // The tray popup while a recording runs, before and after its Highlight.
        model.start()
        until { model.recording }
        delay(1_500)
        shot("popup-recording-en", 520, 560, false) { TrayPopup(model, strings) {} }
        shot("popup-highlighted-en", 520, 560, false, clicks = listOf(POPUP_HIGHLIGHT)) { TrayPopup(model, strings) {} }
        model.stop()
        until { !model.recording }

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
        shot("settings-speaker-download-en", 640, 2700, false, clicks = listOf(MCP_COPY)) { SettingsWindow(model, strings) }
        val copied = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
        }.getOrNull()
        println("SHOTS clipboard after Copy configuration:\n$copied")
        model.shutdown()
    }

    private fun sample(recordingId: String) = Transcript(
        recordingId = recordingId,
        track = Track.MONO,
        language = "en",
        provider = TranscriptProvider("assemblyai"),
        createdAt = "2026-10-07T00:00:00Z",
        durationSec = 40.0,
        speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2")),
        segments = listOf(
            TranscriptSegment(0.0, 6.0, "S1", "Thanks for joining. Let's go over the release notes for Recly first."),
            TranscriptSegment(6.0, 11.5, "S1", "The import button is in the list header now."),
            TranscriptSegment(11.5, 20.0, "S2", "Good. Did the highlight ticks land on the waveform as well?"),
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
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width * SCALE, height * SCALE, Density(SCALE.toFloat())) {
            ReclyDesktopTheme(dark = dark, highContrast = false, tracked = !ko) { content() }
        }
        try {
            settle(scene)
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
            val image = scene.render(System.nanoTime())
            File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
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
        val BADGE = Offset(455f, 215f)
        val TICK = Offset(451f, 100f)
        val MORE_EDIT = Offset(835f, 86f)
        val EDIT_FIELD = Offset(700f, 250f)
        val BADGE_CHANGE = Offset(548f, 275f)
        val SEARCH = Offset(150f, 103f)
        val FLAG = Offset(415f, 215f)
        val FLAG_REMOVE = Offset(475f, 271f)
        val FIND_NEXT = Offset(900f, 210f)
        val POPUP_HIGHLIGHT = Offset(179f, 263f)
        val MCP_COPY = Offset(549f, 1806f)
    }
}
