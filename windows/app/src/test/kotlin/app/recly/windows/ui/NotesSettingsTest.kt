package app.recly.windows.ui

import app.recly.windows.SilentLogger
import app.recly.windows.core.AppModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.notes.NoteSection
import recly.core.notes.NotesPreferences

/**
 * docs/09 "AI view" — Settings → Notes on the PC: the instructions saved as the core keeps them, a section kept once it
 * has a name and dropped without one, at most three, Remove at once, and the next window reading what this PC kept.
 */
class NotesSettingsTest {

    @Test
    fun `sections are kept once they have a name, and the instructions as the core keeps them`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val graph = AppModule.build(dataDir = Files.createTempDirectory("recly-notes").toString().toPath())
        val notes = graph.core.notes
        fun viewModel() = ChatGptViewModel(graph.core.chatGpt, graph.core.summaries, notes, graph.loopback, scope, SilentLogger).also { it.start() }
        suspend fun until(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }
        try {
            // What this PC kept is read when the window opens, and only then is anything typed.
            notes.setPreferences(NotesPreferences(instructions = "Long answers."))
            val settings = viewModel()
            until { settings.notesInstructions == "Long answers." }
            settings.saveNotesInstructions("  Short lines.  ")
            until { notes.preferences().instructions == "Short lines." && settings.notesInstructions == "Short lines." }

            // Added and left without a name: gone, and nothing was saved for it.
            val unnamed = settings.addSection()!!
            assertEquals(listOf(""), settings.sections.map { it.name })
            settings.endSection(unnamed, "  ", "Anything")
            assertTrue(settings.sections.isEmpty())

            // Named: saved with the id the core gave it, under the key it was edited by.
            val risks = settings.addSection()!!
            settings.endSection(risks, "Risks ", "What could go wrong.")
            until { settings.sections.singleOrNull()?.id?.isNotEmpty() == true }
            val kept = notes.preferences().sections.single()
            assertEquals(NoteSection(kept.id, "Risks", "What could go wrong."), kept)
            assertEquals(risks, settings.sections.single().key)
            assertEquals(kept.id, settings.sections.single().id)

            // Three at most: no fourth to add.
            val budget = settings.addSection()!!
            settings.endSection(budget, "Budget", "")
            val people = settings.addSection()!!
            settings.endSection(people, "People", "")
            assertNull(settings.addSection())
            until { notes.preferences().sections.map { it.name } == listOf("Risks", "Budget", "People") }

            // Remove, at once and without asking; the others keep their ids.
            settings.removeSection(budget)
            until { notes.preferences().sections.map { it.name } == listOf("Risks", "People") }
            assertEquals(kept.id, notes.preferences().sections.first().id)

            // The next window reads what this PC kept.
            val next = viewModel()
            until { next.sections.map { it.name } == listOf("Risks", "People") }
            assertEquals("Short lines.", next.notesInstructions)
            assertEquals(notes.preferences().sections.map { it.id }, next.sections.map { it.id })
        } finally {
            scope.cancel()
        }
    }
}
