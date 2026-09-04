@file:OptIn(ExperimentalTime::class)

package app.recly.android.core

import android.content.Context
import android.os.Build
import app.recly.android.BuildConfig
import app.recly.android.R
import app.recly.android.auth.AndroidTokenProvider
import app.recly.android.auth.GoogleAuth
import app.recly.android.auth.PlayAuthorizer
import app.recly.recording.platform.AndroidSecureStore
import app.recly.recording.platform.SystemClock
import app.recly.recording.platform.deviceId
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.model.Platform
import recly.core.platform.AndroidRuntime
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo

/** What the shell holds for the life of the process (docs/01 "코어 ↔ 셸 경계"). */
class AppGraph internal constructor(
    val core: ReclyCore,
    val auth: GoogleAuth,
    val tokens: AndroidTokenProvider,
    val secrets: SecretStore,
)

/**
 * Manual DI — no Hilt. One [ReclyCore] per process, built on first use.
 *
 * Suspending because the device ID lives in the secure store: reading or minting it is the first
 * thing that opens EncryptedSharedPreferences, and neither belongs on the main thread.
 */
object CoreModule {
    private val mutex = Mutex()

    @Volatile private var graph: AppGraph? = null

    suspend fun get(context: Context): AppGraph = graph ?: mutex.withLock {
        graph ?: build(context.applicationContext).also { graph = it }
    }

    private suspend fun build(context: Context): AppGraph = withContext(Dispatchers.IO) {
        val clock = SystemClock
        val logger = AndroidLogger()
        val secureStore = AndroidSecureStore(context, Dispatchers.IO)
        val tokens = AndroidTokenProvider(PlayAuthorizer(context, clock, logger), secureStore, clock)

        val dataDir = context.filesDir.absolutePath.toPath() / "rec"
        FileSystem.SYSTEM.createDirectories(dataDir)

        val deps = CoreDeps(
            clock = clock,
            logger = logger,
            secureStore = secureStore,
            tokenProvider = tokens,
            fileSystem = FileSystem.SYSTEM,
            audio = AndroidAudioTools(Dispatchers.IO),
            dataDir = dataDir,
            device = DeviceInfo(
                deviceId = deviceId(secureStore),
                platform = Platform.ANDROID,
                name = Build.MODEL,
            ),
            appVersion = BuildConfig.VERSION_NAME,
            io = Dispatchers.IO,
            // docs/07 §6: only the seeded workflow names use it, and only on a first install.
            locale = Locale.getDefault().language,
        )

        val core = ReclyCore(deps, AndroidRuntime.driverFactory(context, "rec.db"))
        AppGraph(
            core = core,
            auth = GoogleAuth(
                context = context,
                secureStore = secureStore,
                tokens = tokens,
                clock = clock,
                logger = logger,
                serverClientId = context.getString(R.string.google_server_client_id),
            ),
            tokens = tokens,
            // docs/05 "코어 구현 메모": the shell's secret writes go through the core, not straight
            // into the secure store, or `secrets.enc` never hears about them.
            secrets = SecretStore(core.secrets),
        )
    }
}
