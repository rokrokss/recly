package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.StringTable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import recly.core.model.Track
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment

/**
 * The UX decisions of 2026-10-08, item 8: a Details row whose recording has a transcript on this PC shows its
 * first words, read from the core for the rows the ledger has loaded and again when those rows change; a row
 * with no transcript has none.
 */
class ShellPreviewsTest {

    @Test
    fun `a row's first words arrive with its transcript`() = runBlocking {
        val directory = Files.createTempDirectory("recly-previews-").toFile()
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
            assertNull(model.previews[id], "a recording with no transcript has no first words")

            val folder = File(directory, "recordings").listFiles()!!.single { it.isDirectory }
            val base = folder.listFiles()!!.first { it.name.endsWith(".meta.json") }.name.removeSuffix(".meta.json")
            val transcript = Transcript(
                recordingId = id,
                track = Track.MIX,
                language = "en",
                provider = TranscriptProvider("assemblyai"),
                createdAt = "2026-10-08T00:00:00Z",
                durationSec = 2.0,
                speakers = emptyList(),
                segments = listOf(
                    TranscriptSegment(0.0, 1.0, "S1", "Thanks   for joining."),
                    TranscriptSegment(1.0, 2.0, "S1", "Let's start."),
                ),
            )
            File(folder, "$base.transcript.json").writeText(Json.encodeToString(Transcript.serializer(), transcript))

            // Any change to the rows reads them again: a highlight written to the meta is one.
            model.setHighlights(id, listOf(1.0))
            until { model.previews[id] != null }
            assertEquals("Thanks for joining. Let's start.", model.previews[id])
        } finally {
            model.shutdown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
