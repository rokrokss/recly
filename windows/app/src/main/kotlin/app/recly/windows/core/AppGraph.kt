@file:OptIn(ExperimentalTime::class)

package app.recly.windows.core

import app.cash.sqldelight.db.SqlDriver
import app.recly.windows.auth.GoogleAuth
import app.recly.windows.auth.JvmTokenProvider
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.auth.TokenEndpoint
import app.recly.windows.i18n.Localization
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import recly.core.DriverFactory
import recly.core.ReclyCore
import recly.core.drive.KtorTransport
import recly.core.model.Platform
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo
import recly.core.platform.JvmRuntime
import kotlin.time.Clock as TimeClock
import recly.core.platform.Clock as CoreClock

/** What the shell holds for the life of the process (docs/01 "코어 ↔ 셸 경계"). */
class AppGraph(
    val core: ReclyCore,
    val auth: GoogleAuth,
    val tokens: JvmTokenProvider,
    val secrets: SecretStore,
    val dataDir: Path,
)

/**
 * Manual DI, one [ReclyCore] per process — the phone's `CoreModule` without the `Context`.
 * Suspending because the device id and the secure store are both disk.
 */
object AppModule {

    suspend fun build(
        dataDir: Path = Host.dataDir(),
        fileSystem: FileSystem = FileSystem.SYSTEM,
        io: CoroutineDispatcher = Dispatchers.IO,
        databaseName: String = "rec.db",
        /** The app's language, which the core needs for the names it seeds (docs/07 §6). */
        localization: Localization = Localization(),
    ): AppGraph = withContext(io) {
        fileSystem.createDirectories(dataDir)
        val clock = SystemClock
        val logger = JvmLogger()
        val secureStore = SecureStores.create(dataDir, fileSystem, io)
        val transport = KtorTransport()
        val endpoint = TokenEndpoint(transport)
        val tokens = JvmTokenProvider(secureStore, clock, endpoint, logger)

        val deps = CoreDeps(
            clock = clock,
            logger = logger,
            secureStore = secureStore,
            tokenProvider = tokens,
            transport = transport,
            fileSystem = fileSystem,
            audio = FfmpegAudioTools(fileSystem, io),
            dataDir = dataDir,
            device = DeviceInfo(
                deviceId = deviceId(fileSystem, dataDir),
                platform = Platform.WINDOWS,
                name = Host.deviceName(),
            ),
            appVersion = OAuthConfig.APP_VERSION,
            io = io,
            locale = localization.tag,
        )

        val core = ReclyCore(deps, JvmDriverFactory(dataDir / databaseName))
        AppGraph(
            core = core,
            auth = GoogleAuth(tokens, endpoint, logger, localization::current),
            tokens = tokens,
            // docs/05 "코어 구현 메모": the shell's secret writes go through the core, not straight
            // into the secure store, or `secrets.enc` never hears about them.
            secrets = SecretStore(core.secrets),
            dataDir = dataDir,
        )
    }

    /**
     * docs/01: a UUID v4 minted at install time; a reinstall gets a new one. A plain file next to
     * the database and not the Credential Manager, for the reason docs/12 M4-L2 gives on the Mac —
     * the id is not a secret (it is written into every `meta.json` in the clear) and the secure
     * store is where the things that *are* secret live.
     */
    internal fun deviceId(fileSystem: FileSystem, dataDir: Path): String {
        val file = dataDir / DEVICE_ID_FILE
        if (fileSystem.exists(file)) {
            val existing = fileSystem.read(file) { readUtf8() }.trim()
            if (existing.isNotEmpty()) return existing
        }
        val minted = UUID.randomUUID().toString()
        // Written through a temp file: a half-written id is one the next launch reads back as a
        // different device.
        val temp = dataDir / "$DEVICE_ID_FILE.tmp"
        fileSystem.write(temp) { writeUtf8(minted) }
        fileSystem.atomicMove(temp, file)
        return minted
    }

    const val DEVICE_ID_FILE = "device.id"
}

/**
 * The JDBC driver creates no schema and tracks no version the way the Android and native ones do,
 * so both live in [JvmRuntime] — a new file gets the whole schema, an existing one gets whatever
 * migrations it is behind by (docs/10 "스키마 마이그레이션").
 */
class JvmDriverFactory(private val path: Path) : DriverFactory {
    override fun create(): SqlDriver = JvmRuntime.openDriver(path.toString())
}

internal object SystemClock : CoreClock {
    override fun now(): Instant = TimeClock.System.now()
}
