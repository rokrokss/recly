package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.StringTable
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

/**
 * A start that **throws** rather than one that is refused. The row and the directory are written
 * before the helper is asked for anything (`WindowsRecorder.start`), so a disk or database failure
 * there comes out of the coroutine `begin` launched — and everything that start raised before it
 * (docs/09 화면 원칙 1's `STARTING`, and ADR-006's playback gate) has to come back down anyway.
 *
 * A shell that leaked either of them would spend the rest of the process saying it was opening a
 * capture that is not running, with the recordings window refusing to play anything.
 *
 * The whole shell is opened for it — core, recorder and executor — over a temp directory and the
 * fake helper, which is what [ShellModel.load]'s two parameters are for.
 */
class ShellStartTest {

    /**
     * What the start threw, caught where the shell's own scope would otherwise drop it on the
     * thread's uncaught handler — which is a JVM-wide place, and the test JVM is shared: an
     * exception left to reach it is reported against *whatever test runs next*
     * (`UncaughtExceptionsBeforeTest`). Holding it here also makes it something to assert on.
     */
    private val failures = mutableListOf<Throwable>()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error },
    )
    private val dir = File("build/shell-start")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a start that throws puts the state node and the playback gate back`() = runBlocking {
        val model = shell()
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = FakeHelperCommand.command())

        // The recordings directory, as a *file*: the next `createDirectories` under it throws, which
        // is the shape of every reason a start dies before the helper — a full disk, a directory the
        // user cannot write, a profile that moved.
        val recordings = File(dir, "recordings")
        recordings.deleteRecursively()
        recordings.writeText("not a directory")

        model.start(null)

        // The launched start fails; the assertions are about what it left behind, so this waits for
        // the shell to settle rather than for a result there is none of.
        withTimeout(TIMEOUT_MS) {
            while (model.transition != null) delay(POLL_MS)
        }
        assertNull(model.transition, "the node was left saying STARTING over a capture that never opened")
        assertEquals("IDLE", model.stateCode(), "the state node never came back")
        assertFalse(model.recording)
        assertFalse(model.playbackBlocked, "playback stayed blocked for a capture that is not running")
        // And it really was the throw and not a refusal: the start died on the directory, which is
        // the whole point of the two assertions above.
        assertIs<IOException>(failures.singleOrNull(), "the start did not throw; $failures")

        model.shutdown()
    }

    /** And the same is true of the ordinary refusal, which is the path that already worked. */
    @Test
    fun `a start the recorder refuses puts them back too`() = runBlocking {
        val model = shell()
        // No helper command at all: `WindowsRecorder.start` has nothing to record with and returns
        // null rather than throwing (docs/14 deliverable 5).
        model.load(dataDirectory = dir.absolutePath.toPath(), helperCommand = null)

        model.start(null)

        withTimeout(TIMEOUT_MS) {
            while (model.transition != null) delay(POLL_MS)
        }
        assertEquals("NO_HELPER", model.stateCode())
        assertFalse(model.playbackBlocked, "playback stayed blocked for a capture that is not running")
        assertEquals(emptyList(), failures, "a refused start is not a failure")

        model.shutdown()
    }

    /**
     * The consent reminder is off: this is a test about what a start leaves behind, and docs/12 M8's
     * question would stop it in front of the recorder ([ShellModel.start]).
     */
    private fun shell() = ShellModel(
        scope = scope,
        localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE },
    )

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
    }
}
