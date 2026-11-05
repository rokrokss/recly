@file:OptIn(ExperimentalCoroutinesApi::class)

package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.message
import app.recly.windows.update.AvailableUpdate
import app.recly.windows.update.UpdateException
import app.recly.windows.update.UpdateFailure
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import recly.core.platform.Logger

/**
 * docs/14 "Updates": what the updater does on its own and when asked — the checks it makes by itself and the ones it
 * is asked for, what each failure leaves on screen, and an install that only quits the app once nothing is recording.
 */
class UpdateViewModelTest {

    private val update = AvailableUpdate("0.3.3", "0.3.3", "https://example.invalid/Recly-0.3.3.msi", 10, "a".repeat(64))
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val logger = object : Logger {
        override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
            events += event to fields
        }
    }

    private var answer: () -> AvailableUpdate? = { null }
    private var idle = true
    private var quits = 0
    private val launched = mutableListOf<Path>()
    private var download: suspend (AvailableUpdate) -> Path = { Paths.get("Recly-0.3.3.msi") }
    private var launch: suspend (Path) -> Unit = { launched.add(it) }

    private fun TestScope.model(settings: FakeSettings = FakeSettings()) = UpdateViewModel(
        scope = backgroundScope,
        settings = settings,
        logger = logger,
        checkFeed = { answer() },
        download = { download(it) },
        launch = { launch(it) },
        idle = { idle },
        quit = { quits++ },
    )

    @Test
    fun `it checks about ten seconds after launch and every six hours after, while the setting is on`() = runTest {
        var checks = 0
        answer = { checks++; null }
        val settings = FakeSettings(autoUpdateCheck = true)
        val model = model(settings)
        model.start()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, checks)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, checks)
        assertEquals(UpdateState.UpToDate, model.state)

        advanceTimeBy(UpdateViewModel.INTERVAL_MS)
        runCurrent()
        assertEquals(2, checks)

        model.answer(false)
        assertEquals(false, settings.autoUpdateCheck, "kept for the next launch")
        advanceTimeBy(UpdateViewModel.INTERVAL_MS)
        runCurrent()
        assertEquals(2, checks, "off: no check")
    }

    @Test
    fun `an automatic check that fails is only logged`() = runTest {
        answer = { throw UpdateException(UpdateFailure.CHECK, "http 404") }
        val model = model()

        model.check(manual = false)
        runCurrent()

        assertEquals(UpdateState.Idle, model.state)
        assertNull(model.failure)
        assertEquals("shell.update.check" to mapOf("outcome" to "failed", "reason" to "check", "detail" to "http 404", "manual" to false), events.single())
    }

    @Test
    fun `a check the user asks for shows its loader, and its failure keeps the title it had`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = UpdateViewModel(
            scope = backgroundScope, settings = FakeSettings(), logger = logger,
            checkFeed = { gate.await(); throw UpdateException(UpdateFailure.CHECK, "signature") },
            download = { Paths.get("x") }, launch = {}, idle = { true }, quit = {},
        )

        model.check()
        runCurrent()
        assertEquals(UpdateState.Checking, model.state)
        assertEquals(UpdateAction.CHECKING, row(model).action)

        gate.complete(Unit)
        runCurrent()
        assertEquals(UpdateState.Idle, model.state)
        assertEquals(UpdateFailure.CHECK, model.failure)
        assertEquals(UpdateRow(Str.UPDATE_CURRENT.message("0.3.2"), UpdateAction.CHECK, Str.UPDATE_CHECK_FAILED, waiting = false), row(model))
    }

    @Test
    fun `a check the user asks for during an automatic one takes it over`() = runTest {
        val gate = CompletableDeferred<AvailableUpdate?>()
        var checks = 0
        val model = UpdateViewModel(
            scope = backgroundScope, settings = FakeSettings(), logger = logger,
            checkFeed = { checks++; gate.await() }, download = { Paths.get("x") }, launch = {}, idle = { true }, quit = {},
        )

        model.check(manual = false)
        runCurrent()
        assertEquals(UpdateState.Idle, model.state, "an automatic check shows nothing while it runs")
        model.check()
        runCurrent()
        assertEquals(UpdateState.Checking, model.state)

        gate.complete(update)
        runCurrent()
        assertEquals(1, checks)
        assertEquals(UpdateState.Available(update), model.state)
        assertEquals(true, events.single().second["manual"])
    }

    @Test
    fun `an update offers Restart and install, here and on the popup`() = runTest {
        answer = { update }
        val model = model()

        model.check()
        runCurrent()

        val row = row(model)
        assertEquals(UpdateRow(Str.UPDATE_AVAILABLE.message("0.3.3"), UpdateAction.INSTALL, null, waiting = false), row)
        assertTrue(row.offers)
        assertEquals(
            "shell.update.check" to mapOf("outcome" to "available", "version" to "0.3.3", "installerVersion" to "0.3.3", "manual" to true),
            events.single(),
        )
        assertFalse(updateRow(UpdateState.UpToDate, null, idle = true).offers, "nothing on the popup for up to date")
        assertFalse(updateRow(UpdateState.Idle, UpdateFailure.CHECK, idle = true).offers, "nor for a failed check")
    }

    @Test
    fun `install downloads, starts the installer, then quits`() = runTest {
        answer = { update }
        val model = model()
        model.check()
        runCurrent()

        model.install()
        assertEquals(UpdateState.Installing(update), model.state)
        assertEquals(UpdateAction.INSTALLING, row(model).action)
        runCurrent()

        assertEquals(listOf(Paths.get("Recly-0.3.3.msi")), launched)
        assertEquals(1, quits)
        assertEquals("shell.update.install" to mapOf("version" to "0.3.3", "installerVersion" to "0.3.3"), events.last())
    }

    @Test
    fun `nothing is installed while a recording runs or is saved`() = runTest {
        answer = { update }
        val model = model()
        model.check()
        runCurrent()
        idle = false

        assertEquals(UpdateRow(Str.UPDATE_AVAILABLE.message("0.3.3"), UpdateAction.INSTALL, null, waiting = true), row(model))
        model.install()
        runCurrent()

        assertEquals(UpdateState.Available(update), model.state)
        assertEquals(0, quits)
        assertTrue(launched.isEmpty())
    }

    /** A recording started while the MSI came down: the offer comes back, and the app is not quit under it. */
    @Test
    fun `a recording started during the download keeps the app running`() = runTest {
        answer = { update }
        download = { idle = false; Paths.get("Recly-0.3.3.msi") }
        val model = model()
        model.check()
        runCurrent()

        model.install()
        runCurrent()

        assertEquals(UpdateState.Available(update), model.state)
        assertNull(model.failure)
        assertTrue(launched.isEmpty())
        assertEquals(0, quits)
    }

    @Test
    fun `a failed install says why and offers it again`() = runTest {
        answer = { update }
        val model = model()
        model.check()
        runCurrent()
        val cases = listOf(
            UpdateException(UpdateFailure.DOWNLOAD, "http 503") to Str.UPDATE_DOWNLOAD_FAILED,
            UpdateException(UpdateFailure.SIGNATURE, "sha256") to Str.UPDATE_SIGNATURE_FAILED,
            UpdateException(UpdateFailure.INSTALL, "write") to Str.UPDATE_INSTALL_FAILED,
        )

        cases.forEach { (error, line) ->
            download = { throw error }
            model.install()
            runCurrent()
            assertEquals(UpdateState.Available(update), model.state)
            assertEquals(UpdateRow(Str.UPDATE_AVAILABLE.message("0.3.3"), UpdateAction.INSTALL, line, waiting = false), row(model))
            assertEquals(
                "shell.update.fail" to mapOf("phase" to "download", "reason" to error.failure.reason, "detail" to error.detail, "cause" to null),
                events.last(),
            )
        }

        download = { Paths.get("Recly-0.3.3.msi") }
        launch = { throw UpdateException(UpdateFailure.INSTALL, "launch") }
        model.install()
        runCurrent()
        assertEquals(UpdateFailure.INSTALL, model.failure)
        assertEquals("launch", events.last().second["phase"])
        assertEquals(0, quits)
    }

    /** An automatic check that finds the same update leaves the reason the last install failed where it is. */
    @Test
    fun `the same answer from an automatic check keeps a failed install's reason`() = runTest {
        answer = { update }
        download = { throw UpdateException(UpdateFailure.DOWNLOAD, "http 503") }
        val model = model()
        model.check()
        runCurrent()
        model.install()
        runCurrent()

        model.check(manual = false)
        runCurrent()

        assertEquals(UpdateFailure.DOWNLOAD, model.failure)
    }

    @Test
    fun `the setting follows the store, and is off and asked until there is an answer`() = runTest {
        val unanswered = model()
        assertTrue(unanswered.asks)
        assertFalse(unanswered.automatic)
        assertTrue(model(FakeSettings(autoUpdateCheck = true)).automatic)
        assertFalse(model(FakeSettings(autoUpdateCheck = false)).asks)
    }

    @Test
    fun `nothing is checked by itself until the user says yes, and a yes looks at once`() = runTest {
        var checks = 0
        answer = { checks++; update }
        val settings = FakeSettings()
        val model = model(settings)
        model.start()
        advanceTimeBy(UpdateViewModel.FIRST_CHECK_MS + 1)
        runCurrent()
        assertEquals(0, checks, "unanswered: no check")
        assertTrue(model.asks)

        model.answer(true)
        runCurrent()
        assertEquals(1, checks)
        assertFalse(model.asks)
        assertEquals(true, settings.autoUpdateCheck)
        assertEquals(UpdateState.Available(update), model.state)
        assertTrue(events.any { it == ("shell.update.automatic" to mapOf<String, Any?>("on" to true)) })

        model.answer(true)
        runCurrent()
        assertEquals(1, checks, "the same answer again changes nothing")
    }

    private fun row(model: UpdateViewModel) = updateRow(model.state, model.failure, idle, appVersion = "0.3.2")
}
