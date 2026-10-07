package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import okio.Path.Companion.toPath
import recly.core.model.Language
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import recly.core.recording.SearchSnippet

class ShellDetailTest {
    @Test
    fun `a detail opened during capture gets its finished audio without reopening`() = runBlocking {
        val directory = Files.createTempDirectory("recly-detail-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = ShellModel(scope = scope, localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE })
        var observer: Job? = null
        suspend fun until(ready: () -> Boolean) = withTimeout(60_000) {
            while (!ready()) delay(20)
        }
        try {
            model.load(directory.absolutePath.toPath(), FakeHelperCommand.command("parts=1", "sec=2.0", "write"))
            model.start()
            until { model.recording && model.recents.isNotEmpty() }
            model.openDetail(model.recents.first())
            until { model.detail?.loading == false }
            observer = launch { model.followDetailResults(model.detail!!.recordingId) }
            until { model.detail?.writing == true }
            model.stop()
            until { model.detail?.let { !it.writing && !it.audio.isEmpty } == true }
            assertFalse(model.detail!!.writing)
            assertTrue(model.detail!!.audio.paths.isNotEmpty())
        } finally {
            observer?.cancelAndJoin()
            model.shutdown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the detail keeps what the core keeps, finds only transcript hits, and says why Transcribe again did not start`() = runBlocking {
        val directory = Files.createTempDirectory("recly-detail-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = ShellModel(scope = scope, localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE })
        var observer: Job? = null
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
            model.openDetail(model.recents.first())
            until { model.detail?.loading == false }
            observer = launch { model.followDetailState(id) }

            // A mark within a second of another is not drawn even for a moment: the core would not keep it.
            model.setHighlights(id, listOf(5.0, 5.4, 1.0))
            assertEquals(listOf(1.0, 5.0), model.detail!!.highlights)

            // Nothing was uploaded (no storage is connected), so there is no folder to transcribe again into.
            until { model.detail!!.notUploaded }
            model.retranscribe(RetranscribeRequest(id, provider = null, language = Language.EN, replacesEdits = false))
            until { model.detail?.notice == Str.DETAIL_NOT_UPLOADED }

            // A hit in the title alone opens without a find; one in the transcript opens on its query.
            model.openSearchHit(SearchHit(id, "Weekly sync", "2026-10-07T00:00:00Z", true, listOf(SearchRange(0, 6)), emptyList()), "weekly")
            assertNull(model.detail!!.find)
            val snippet = SearchSnippet(1.0, "the weekly numbers", listOf(SearchRange(4, 6)))
            model.openSearchHit(SearchHit(id, "Weekly sync", "2026-10-07T00:00:00Z", true, listOf(SearchRange(0, 6)), listOf(snippet)), "weekly")
            assertEquals("weekly", model.detail!!.find)
            assertEquals(1.0, model.detail!!.findAtSec)
        } finally {
            observer?.cancelAndJoin()
            model.shutdown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
