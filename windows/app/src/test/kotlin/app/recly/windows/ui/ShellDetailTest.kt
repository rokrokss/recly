package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.StringTable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
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
            model.start(null)
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
}
