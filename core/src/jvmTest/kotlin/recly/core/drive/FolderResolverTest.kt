@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Platform
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.UnusedSecureStore
import recly.core.testing.inMemoryDatabase

class FolderResolverTest {
    private val drive = FakeDrive()
    private val fs = FakeFileSystem()
    private val clock = FakeClock()
    private val db = inMemoryDatabase()
    private val deps = CoreDeps(
        clock = clock,
        logger = FakeLogger(),
        secureStore = UnusedSecureStore,
        tokenProvider = ScriptedTokenProvider(),
        transport = mockTransport(drive, fs),
        fileSystem = fs,
        audio = recly.core.testing.FakeAudioTools(fs),
        dataDir = "/data".toPath(),
        device = DeviceInfo("7c1e4b2a", Platform.MACOS, "MacBook Pro"),
        appVersion = "1.0.0",
        io = Dispatchers.Unconfined,
    )
    private val api = DriveApi(deps)
    private val resolver = FolderResolver(api, DriveStore(db, deps), deps)

    @Test
    fun `every missing segment is created once, under the previous one`() = runBlocking {
        val id = resolver.resolve("recly/2026/2026-08")

        assertEquals(3, creations())
        assertEquals(drive.idOf("2026-08"), id)
        assertEquals(listOf("root"), drive.byName("recly")!!.parents)
        assertEquals(listOf(drive.idOf("recly")), drive.byName("2026")!!.parents)
        assertEquals(listOf(drive.idOf("2026")), drive.byName("2026-08")!!.parents)
    }

    @Test
    fun `a cached path costs no requests at all within the day`() = runBlocking {
        val id = resolver.resolve("recly/2026/2026-08")
        val requests = drive.requests.size

        clock.advance(23.hours)

        assertEquals(id, resolver.resolve("recly/2026/2026-08"))
        assertEquals(requests, drive.requests.size)
    }

    @Test
    fun `after a day the cached ids are verified and reused`() = runBlocking {
        val id = resolver.resolve("recly/2026/2026-08")
        val creations = creations()

        clock.advance(25.hours)

        assertEquals(id, resolver.resolve("recly/2026/2026-08"))
        assertEquals(creations, creations())
        assertEquals(3, drive.requests.count { it.method == "GET" && it.path.startsWith("/drive/v3/files/") })
    }

    @Test
    fun `a folder the user deleted is created again`() = runBlocking {
        val id = resolver.resolve("recly/2026/2026-08")
        drive.files.remove(id)

        clock.advance(25.hours)
        val fresh = resolver.resolve("recly/2026/2026-08")

        assertNotEquals(id, fresh)
        assertEquals(drive.idOf("2026-08"), fresh)
        assertEquals(listOf(drive.idOf("2026")), drive.byName("2026-08")!!.parents)
    }

    private fun creations(): Int = drive.requests.count { it.method == "POST" && it.path == "/drive/v3/files" }
}
