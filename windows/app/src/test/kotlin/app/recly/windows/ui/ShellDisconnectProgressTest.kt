package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.StringTable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

/** Real shell progress over an isolated database, with no Google credentials or live revocation. */
class ShellDisconnectProgressTest {
    @Test
    fun `confirmation immediately stays busy until cleanup and ignores repeat requests`() = runBlocking {
        val directory = Files.createTempDirectory("recly-disconnect-progress").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = ShellModel(scope = scope,
            localization = Localization(FakeSettings()) { StringTable.BASE })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            DisconnectGate.hold {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            model.load(dataDirectory = directory.absolutePath.toPath(), helperCommand = FakeHelperCommand.command())
            model.askToDisconnect()
            withTimeout(60_000) { while (model.disconnectPrompt == null) delay(20) }

            model.disconnect(alsoDeleteRecordings = false)
            assertTrue(model.disconnecting, "confirmation did not publish progress synchronously")
            assertNull(model.disconnectPrompt, "confirmation stayed open while the operation ran")
            model.askToDisconnect()
            model.disconnect(alsoDeleteRecordings = false)
            assertNull(model.disconnectPrompt, "another warning opened over an active disconnect")
            assertTrue(model.disconnecting, "a repeat request cleared the progress state")

            release.complete(Unit)
            withTimeout(60_000) { while (model.disconnecting) delay(20) }
            assertFalse(model.disconnectPhase.owed, "local cleanup was left unfinished")
            assertFalse(model.signedIn)
        } finally {
            release.complete(Unit)
            holder.join()
            model.shutdown()
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
