package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.core.AppModule
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.StringTable
import app.recly.windows.jobs.Recents
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track

/**
 * docs/09 화면 원칙 2 / docs/12 "메뉴바": the ledger is [Recents.PAGE] rows a page, and the row that
 * comes into view at the bottom asks for the next one ([ShellModel.loadMoreRecents]).
 *
 * The two things a page-at-a-time list can get wrong are both here: the window has to *grow* rather
 * than be re-read at the same size, and it has to stop growing once a reading has come back short —
 * otherwise every scroll onto the last row would walk the limit off past the end of the recordings
 * for the rest of the session.
 *
 * The whole shell is opened for it over a temp directory and the fake helper, which is what
 * [ShellModel.load]'s two parameters are for (`ShellStartTest`).
 */
class LedgerPagingTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File("build/ledger-paging")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `the ledger reads a page at a time and stops at the end of the recordings`() = runBlocking {
        dir.deleteRecursively()
        dir.mkdirs()
        val dataDir = dir.absolutePath.toPath()
        // A page and a bit: the second reading is the one that comes back short.
        val core = AppModule.build(dataDir = dataDir).core
        repeat(RECORDINGS) { index ->
            val id = "rec-$index"
            core.recordings.create(meta(id, index), dataDir.resolve(id))
        }

        val model = shell()
        model.load(dataDirectory = dataDir, helperCommand = FakeHelperCommand.command())

        withTimeout(TIMEOUT_MS) { while (model.recents.isEmpty()) delay(POLL_MS) }
        assertEquals(Recents.PAGE, model.recents.size, "the ledger opened on something other than one page")

        model.loadMoreRecents()

        withTimeout(TIMEOUT_MS) { while (model.recents.size == Recents.PAGE) delay(POLL_MS) }
        assertEquals(RECORDINGS, model.recents.size, "the second page did not add the rest of the recordings")

        // That reading came back short of the window it asked for, so there is nothing older to ask
        // for: the row at the bottom goes on firing as the user scrolls it, and the list stays
        // where it is rather than being re-read for rows that are not there.
        model.loadMoreRecents()
        delay(SETTLE_MS)
        assertEquals(RECORDINGS, model.recents.size, "the ledger kept reading past the end of the recordings")

        model.shutdown()
    }

    private fun shell() = ShellModel(
        scope = scope,
        localization = Localization(
            FakeSettings(consentReminder = false, language = AppLanguage.ENGLISH),
        ) { StringTable.BASE },
    )

    /** Distinct `startedAt`s, because the list is ordered by them (`selectRecordings`). */
    private fun meta(id: String, index: Int) = RecordingMeta(
        schema = 1,
        recordingId = id,
        source = Source.DESKTOP,
        platform = Platform.WINDOWS,
        deviceId = "device",
        deviceName = "PC",
        startedAt = "2026-08-27T10:%02d:00.000Z".format(index),
        timezone = "Asia/Seoul",
        audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
        tracks = listOf(Track.MIC, Track.SYS, Track.MIX),
        parts = emptyList(),
        status = RecordingStatus.FINALIZED,
    )

    private companion object {
        const val RECORDINGS = Recents.PAGE + 5
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L

        /** Long enough for a page that was asked for to have arrived, had one been asked for. */
        const val SETTLE_MS = 300L
    }
}
