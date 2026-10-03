package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.message
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.processing.ProcessingSettingsState
import recly.core.storage.StorageKind

/**
 * docs/03 "Storage location" on this shell: the Local folder chip, the folder picked on this PC, and what
 * the rest of the shell does about it — the tray stops asking for Drive, an open processing form keeps
 * its draft, and a recording that waited for a folder goes into the one picked.
 *
 * The whole shell is opened for it, over a temp directory, the way `ShellStartTest` does; the picker
 * itself is the one thing left out ([ShellModel.useLocalFolder] is what it hands its answer to).
 */
class ShellStorageTest {

    /** What the shell's scope threw, held here rather than left to a JVM-wide handler (`ShellStartTest`). */
    private val failures = ConcurrentLinkedQueue<Throwable>()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error },
    )
    private val dir: File = Files.createTempDirectory("recly-storage-").toFile()
    private val settings = FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `the local folder is chosen, picked on this PC and looked at again`() = runBlocking {
        val model = load()
        assertEquals(StorageKind.DRIVE, model.storage)
        assertTrue(CONNECT_DRIVE in menu(model), "a signed-out Drive PC is offered the sign-in")

        model.selectStorage(StorageKind.FOLDER)
        until { model.storage == StorageKind.FOLDER }

        assertFalse(CONNECT_DRIVE in menu(model), "a PC whose recordings go to the local folder is asked for Drive")
        assertNull(model.localFolder)
        assertFalse(model.localFolderAvailable, "nothing is picked yet")

        val folder = File(dir, "picked").apply { mkdirs() }
        model.useLocalFolder(folder.absolutePath)

        assertEquals(folder.absolutePath, settings.localFolder, "the pick is not kept on this PC")
        assertEquals(folder.absolutePath, model.localFolder)
        assertTrue(model.localFolderAvailable)

        // A USB drive pulled out while the settings window was behind another one.
        folder.deleteRecursively()
        model.refreshStorage()
        until { !model.localFolderAvailable }

        model.shutdown()
        assertEquals(emptyList(), failures.toList())
    }

    /**
     * The Apple shell's `ProcessingSettingsModel.storageChanged`: the chip saves a new revision under an
     * open form, which takes it without losing what the user has typed — and its Save is then not
     * refused as stale, and does not put the storage back.
     */
    @Test
    fun `an open processing form keeps its draft across a storage switch and still saves`() = runBlocking {
        val model = load()
        val processing = assertNotNull(model.processing)
        until { processing.draft != null }
        processing.edit { it.minimumSeconds = "42" }

        model.selectStorage(StorageKind.FOLDER)
        until { model.storage == StorageKind.FOLDER }

        assertTrue(processing.dirty, "the switch threw the draft away")
        assertEquals("42", processing.draft?.minimumSeconds)

        processing.save().join()

        assertEquals(Str.PROCESSING_SAVED.message(), processing.message, "the save was refused")
        val stored = assertIs<ProcessingSettingsState.Ready>(processing.stored).document.settings.storage
        assertEquals(42, stored.minDurationSec)
        assertEquals(StorageKind.FOLDER, stored.provider, "the form's save put the storage back")

        model.shutdown()
        assertEquals(emptyList(), failures.toList())
    }

    /**
     * docs/03 "Storage location": a recording made with no folder picked waits for one — a wait the ledger
     * names, and whose delete dialog speaks of the local folder although nothing has reached it yet —
     * and the pick lets it go at once rather than at its next look, five minutes out.
     */
    @Test
    fun `a recording waits for the local folder and goes into it once one is picked`() = runBlocking {
        val model = load(FakeHelperCommand.command("parts=1", "sec=2.0", "write"))
        model.selectStorage(StorageKind.FOLDER)
        until { model.storage == StorageKind.FOLDER }

        model.start()
        until { model.recording }
        model.stop()
        until { model.titlePrompt != null }
        model.saveTitle("Weekly")
        until { model.recents.firstOrNull()?.state == Str.STATE_WAITING_FOLDER.message() }

        model.askToDelete(model.recents.first())
        until { model.deleteRequest != null }
        val request = assertNotNull(model.deleteRequest)
        assertTrue(request.folder, "the dialog would speak of Drive for a recording bound for the local folder")
        assertTrue(request.unuploaded > 0, "the audio is only on this PC")
        model.cancelDelete()

        val folder = File(dir, "picked").apply { mkdirs() }
        model.useLocalFolder(folder.absolutePath)
        // The meta goes in last (docs/03 "Drive layout"), so it is what says the copy is whole.
        fun copied(): List<String> = model.recents.first().localFolderPath
            ?.let { File(folder, it).list()?.toList() }.orEmpty()
        until { copied().any { it.endsWith(".meta.json") } }

        assertTrue(copied().any { it.endsWith(".m4a") }, "no part in the folder: ${copied()}")
        assertNotEquals(Str.STATE_WAITING_FOLDER.message(), model.recents.first().state)

        model.shutdown()
        assertEquals(emptyList(), failures.toList())
    }

    private suspend fun load(helper: List<String>? = null): ShellModel {
        val model = ShellModel(scope = scope, localization = Localization(settings) { StringTable.BASE })
        model.load(dataDirectory = File(dir, "data").absolutePath.toPath(), helperCommand = helper)
        return model
    }

    private fun menu(model: ShellModel): List<String> =
        trayMenu(model, model.localization.current, quit = {}).filterIsInstance<TrayEntry.Item>().map { it.label }

    private suspend fun until(ready: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!ready()) delay(POLL_MS)
    }

    private companion object {
        const val CONNECT_DRIVE = "Connect Google Drive"
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
    }
}
