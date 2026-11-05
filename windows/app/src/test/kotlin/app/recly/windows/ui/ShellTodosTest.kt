package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

/**
 * docs/09 "To-dos view" through the shell and the real core: the collection, the check, a failed check, and what a group
 * or a row opens.
 */
class ShellTodosTest {
    @Test
    fun `a check reaches the collection, and a group opens on the summary`() = withRecording { model, id, _ ->
        val first = model.observeTodos().first { it.isNotEmpty() }.single()
        assertEquals(id, first.recordingId)
        assertEquals(listOf(false, false), first.items.map { it.done })

        // The row's fallback, as the list's: a recording with no title is Untitled.
        model.openTodos(id, first.title)
        assertEquals(Str.UNTITLED, (model.detail!!.title as UiMessage.Res).key)
        assertTrue(model.detail!!.showingNotes, "the view opens the recording on its Summary chip")

        model.setTodoDone(id, "t1", done = true)
        model.awaitDone("t1", true)
        model.setTodoDone(id, "t1", done = false)
        model.awaitDone("t1", false)
    }

    /** docs/09 "To-dos view": a failed write leaves the row as it was, and says so in the log alone. */
    @Test
    fun `a check that cannot be written leaves the row as it was and only logs`() = withRecording { model, id, notesFile ->
        // A folder where the write's temporary file goes stops it on every system; a read-only folder would not on Windows.
        File("${notesFile.path}.tmp").mkdir()
        model.setTodoDone(id, "t1", done = true)
        withTimeout(TIMEOUT) { while ("shell.notes.done.failed" !in logged.toString()) delay(20) }
        assertEquals(listOf(false, false), model.observeTodos().first().single().items.map { it.done })
    }

    private val logged = ByteArrayOutputStream()

    private suspend fun ShellModel.awaitDone(itemId: String, done: Boolean) = withTimeout(TIMEOUT) {
        observeTodos().first { groups -> groups.flatMap { it.items }.single { it.id == itemId }.done == done }
    }

    /** A shell logging into [logged], with one recording stopped and two to-dos in its notes. */
    private fun withRecording(test: suspend (ShellModel, String, File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("recly-todos-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stdout = System.out
        // Before the shell loads: the log takes the stream it finds then.
        System.setOut(PrintStream(logged, true))
        val model = ShellModel(scope = scope, localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE })
        suspend fun until(ready: () -> Boolean) = withTimeout(TIMEOUT) {
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
            val notesFile = File(folder, "$base.notes.v1.json")
            notesFile.writeText(notes(id))
            test(model, id, notesFile)
        } finally {
            System.setOut(stdout)
            model.shutdown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    /** `{base}.notes.v1.json` as the final pass writes it (`spec/notes.schema.json`). */
    private fun notes(recordingId: String): String {
        fun todo(id: String, text: String, atSec: Double) =
            """{"id":"$id","kind":"todo","text":"$text","evidence":{"atSec":$atSec,"quote":""},"provisional":false,""" +
                """"conversation":0,"createdAt":"2026-11-06T10:05:00Z"}"""
        return """{"schema":1,"recordingId":"$recordingId","stage":"final","items":[${todo("t1", "Send the quote", 2.0)},${todo("t2", "Book the room", 1.0)}],""" +
            """"conversations":[{"startSec":0.0,"endSec":2.0}],"model":"gpt-5.4","modelName":"GPT-5.4",""" +
            """"createdAt":"2026-11-06T10:00:00Z","updatedAt":"2026-11-06T10:05:00Z","finalizedAt":"2026-11-06T10:05:00Z"}"""
    }

    private companion object {
        const val TIMEOUT = 60_000L
    }
}
