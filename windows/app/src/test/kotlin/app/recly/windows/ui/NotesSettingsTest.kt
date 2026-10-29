package app.recly.windows.ui

import app.recly.windows.SilentLogger
import app.recly.windows.core.AppGraph
import app.recly.windows.core.AppModule
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.WORD_JOINER
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import recly.core.notes.NoteKind
import recly.core.notes.NoteSection
import recly.core.notes.Notes
import recly.core.notes.NotesPreferences

/**
 * docs/09 "AI view" — Settings → Notes on the PC: the instructions saved as the core keeps them; the built-in groups
 * first, in the app's words and saved as their own while the user keeps them, renamed, kept when left without a name,
 * removed and restored, and following the app's language; a section of the user's own kept once it has a name and
 * dropped without one, at most three besides the built-in groups, Remove at once; and the next window reading what this
 * PC kept.
 */
class NotesSettingsTest {

    private val defaultNames = listOf("Key points", "Decisions", "To-dos", "Questions")

    /** A PC of its own: [window] opens Settings → Notes over it, in the language [strings] is in then. */
    private class Pc(val graph: AppGraph, val scope: CoroutineScope) {
        var strings: Strings = StringTable.of(StringTable.BASE)
        val notes: Notes get() = graph.core.notes

        fun window() =
            ChatGptViewModel(graph.core.chatGpt, graph.core.summaries, notes, graph.loopback, scope, SilentLogger, { strings }).also { it.start() }

        suspend fun ownKept(): List<NoteSection> = notes.preferences().sections.filterNot { it.builtIn }
    }

    private fun onPc(test: suspend Pc.() -> Unit): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            Pc(AppModule.build(dataDir = Files.createTempDirectory("recly-notes").toString().toPath()), scope).test()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }

    private fun own(settings: ChatGptViewModel): List<SectionDraft> = settings.sections.filterNot { it.builtIn }

    @Test
    fun `sections of the user's own are kept once they have a name, and the instructions as the core keeps them`() = onPc {
        // What this PC kept is read when the window opens, and only then is anything typed.
        notes.setPreferences(NotesPreferences(instructions = "Long answers."))
        val settings = window()
        until { settings.notesInstructions == "Long answers." }
        assertEquals(defaultNames, settings.sections.map { it.name })
        settings.saveNotesInstructions("  Short lines.  ")
        until { notes.preferences().instructions == "Short lines." && settings.notesInstructions == "Short lines." }

        // Added and left without a name: gone, and nothing was saved for it.
        val unnamed = settings.addSection()!!
        assertEquals(defaultNames + "", settings.sections.map { it.name })
        assertNull(settings.endSection(unnamed, "  ", "Anything"))
        assertEquals(defaultNames, settings.sections.map { it.name })

        // Named: saved with the id the core gave it, under the key it was edited by; the built-in groups the user did
        // not touch go as their own.
        val risks = settings.addSection()!!
        settings.endSection(risks, "Risks ", "What could go wrong.")
        until { own(settings).singleOrNull()?.id?.isNotEmpty() == true }
        val kept = ownKept().single()
        assertEquals(NoteSection(kept.id, "Risks", "What could go wrong."), kept)
        assertEquals(NoteSection.DEFAULTS, notes.preferences().sections.filter { it.builtIn })
        assertEquals(risks, own(settings).single().key)
        assertEquals(kept.id, own(settings).single().id)

        // Three of the user's own at most, the four built-in groups not counted: no fourth to add.
        val budget = settings.addSection()!!
        settings.endSection(budget, "Budget", "")
        val people = settings.addSection()!!
        settings.endSection(people, "People", "")
        assertFalse(settings.canAddSection)
        assertNull(settings.addSection())
        until { ownKept().map { it.name } == listOf("Risks", "Budget", "People") }

        // Remove, at once and without asking; the others keep their ids.
        settings.removeSection(budget)
        until { ownKept().map { it.name } == listOf("Risks", "People") }
        assertEquals(kept.id, ownKept().first().id)

        // The next window reads what this PC kept.
        val next = window()
        until { next.sections.map { it.name } == defaultNames + listOf("Risks", "People") }
        assertEquals("Short lines.", next.notesInstructions)
        assertEquals(notes.preferences().sections.map { it.id }, next.sections.map { it.id })
    }

    @Test
    fun `a built-in group shows the app's words, is saved as its own while it keeps them, and Restore defaults brings it back`() = onPc {
        notes.setPreferences(NotesPreferences(sections = NoteSection.DEFAULTS + NoteSection("", "Risks", "What could go wrong.")))
        val settings = window()
        until { settings.sections.size == 5 }
        val (point, decision, todo, question) = settings.sections
        assertEquals(NoteSection.BUILT_IN, listOf(point, decision, todo, question).map { it.kind })
        assertEquals(defaultNames, listOf(point, decision, todo, question).map { it.name })
        assertEquals("What was agreed or decided.", decision.description)
        assertFalse(settings.builtInsChanged)

        // Its own words, a space more or not, are its own: nothing for Restore defaults to do.
        settings.endSection(point.key, "Key points ", point.description)
        assertFalse(settings.builtInsChanged)

        // Renamed: saved trimmed; the description it kept is still its own, and so is the untouched one's.
        settings.endSection(decision.key, " Agreed ", decision.description)
        assertTrue(settings.builtInsChanged)
        until { settings.sections[1].name == "Agreed" }
        assertEquals(
            listOf(
                NoteSection.default(NoteKind.POINT),
                NoteSection("decision", "Agreed", "", NoteKind.DECISION),
                NoteSection.default(NoteKind.TODO),
                NoteSection.default(NoteKind.QUESTION),
            ),
            notes.preferences().sections.filter { it.builtIn },
        )

        // Left without a name: still there, its own name shown again and saved as its own.
        assertEquals("Decisions", settings.endSection(decision.key, "  ", " Things settled ")?.name)
        assertEquals(defaultNames, settings.sections.filter { it.builtIn }.map { it.name })
        until { settings.sections[1].description == "Things settled" }
        assertEquals(NoteSection("decision", "", "Things settled", NoteKind.DECISION), notes.preferences().sections[1])

        // Removed at once.
        settings.removeSection(todo.key)
        assertEquals(listOf(NoteKind.POINT, NoteKind.DECISION, NoteKind.QUESTION, NoteKind.SECTION), settings.sections.map { it.kind })
        until { notes.preferences().sections.none { it.kind == NoteKind.TODO } }
        assertTrue(settings.builtInsChanged)

        // Restore defaults: the four built-in groups as they come, first, and the user's own section as it was.
        val risks = own(settings).single()
        settings.restoreSections()
        assertEquals(defaultNames + "Risks", settings.sections.map { it.name })
        assertEquals("What was agreed or decided.", settings.sections[1].description)
        assertEquals(risks, own(settings).single())
        assertFalse(settings.builtInsChanged)
        until { notes.preferences().sections.filter { it.builtIn } == NoteSection.DEFAULTS }
        assertEquals(listOf(NoteSection(risks.id, "Risks", "What could go wrong.")), ownKept())
    }

    @Test
    fun `a built-in group in its own words follows the app's language and is still saved as its own`() = onPc {
        val settings = window()
        until { settings.sections.size == 4 }
        // One renamed: the user's words stay as they are in any language.
        settings.endSection(settings.sections[1].key, "Agreed ", settings.sections[1].description)
        until { settings.sections[1].name == "Agreed" }

        val english = strings
        strings = StringTable.of(StringTable.KOREAN)
        settings.languageChanged(english)
        assertEquals(listOf("핵심", "Agreed", "할 일", "질문"), settings.sections.map { it.name })
        assertEquals(builtInWords(NoteKind.POINT, strings).second, settings.sections[0].description)
        assertFalse(settings.sections.any { WORD_JOINER in it.name || WORD_JOINER in it.description }, "no joiner a field would carry on")

        // Saved after the switch: the words the fields show are the groups' own, not text of the user's.
        settings.endSection(settings.sections[0].key, settings.sections[0].name, settings.sections[0].description)
        settings.endSection(settings.sections[2].key, settings.sections[2].name, " Who does what ")
        until { settings.sections[2].description == "Who does what" }
        assertEquals(
            listOf(
                NoteSection.default(NoteKind.POINT),
                NoteSection("decision", "Agreed", "", NoteKind.DECISION),
                NoteSection("todo", "", "Who does what", NoteKind.TODO),
                NoteSection.default(NoteKind.QUESTION),
            ),
            notes.preferences().sections,
        )
    }
}
