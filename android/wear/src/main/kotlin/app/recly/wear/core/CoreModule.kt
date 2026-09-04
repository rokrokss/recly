@file:OptIn(ExperimentalTime::class)

package app.recly.wear.core

import android.content.Context
import android.os.Build
import app.recly.recording.platform.AndroidSecureStore
import app.recly.recording.platform.SystemClock
import app.recly.recording.platform.deviceId
import app.recly.wear.BuildConfig
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.model.Platform
import recly.core.platform.AndroidRuntime
import recly.core.platform.AudioTools
import recly.core.platform.AuthRequiredException
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo
import recly.core.platform.TokenProvider

/**
 * Manual DI, as on the phone — one [ReclyCore] per process, built on first use.
 *
 * The watch builds the whole core rather than a hand-picked subset: `RecordingRepository` is where
 * a recording is registered and finalized, and it only exists as part of [ReclyCore]. What is missing
 * is everything that would talk to Google — see [NoTokens]. Nothing on this device calls
 * `runDueJobs`, `sync` or `enqueue`, so the Drive half of the core is built and never used.
 */
object CoreModule {
    private val mutex = Mutex()

    @Volatile private var core: ReclyCore? = null

    suspend fun get(context: Context): ReclyCore = core ?: mutex.withLock {
        core ?: build(context.applicationContext).also { core = it }
    }

    private suspend fun build(context: Context): ReclyCore = withContext(Dispatchers.IO) {
        val secureStore = AndroidSecureStore(context, Dispatchers.IO)

        val dataDir = context.filesDir.absolutePath.toPath() / "rec"
        FileSystem.SYSTEM.createDirectories(dataDir)

        val deps = CoreDeps(
            clock = SystemClock,
            logger = WearLogger,
            secureStore = secureStore,
            // The watch holds only its device UUID (ADR-002) and never syncs secrets; this is here
            // because [CoreDeps] asks for it, not because anything on the watch calls it.
            tokenProvider = NoTokens,
            fileSystem = FileSystem.SYSTEM,
            audio = NoAudioTools,
            dataDir = dataDir,
            device = DeviceInfo(
                deviceId = deviceId(secureStore),
                // What makes the recording a `_watch_` one (docs/03 이름 규칙): `RecorderService`
                // reads the source off this.
                platform = Platform.WEAROS,
                name = Build.MODEL,
            ),
            appVersion = BuildConfig.VERSION_NAME,
            io = Dispatchers.IO,
            // docs/07 §6: only the seeded workflow names use it, and only on a first install.
            locale = Locale.getDefault().language,
        )

        ReclyCore(deps, AndroidRuntime.driverFactory(context, "rec.db"))
    }
}

/**
 * ADR-002 again: the watch runs no jobs, so nothing on it ever remuxes parts for a `transcribe`
 * step. The core wants an implementation to build [CoreDeps] with, and this is the honest one.
 */
internal object NoAudioTools : AudioTools {
    override suspend fun concat(parts: List<Path>, out: Path): Unit =
        throw UnsupportedOperationException("the watch does not run jobs (ADR-002); the phone transcribes")
}

/**
 * ADR-002: the watch never talks to Drive. It has no sign-in screen, so there is no token to hand
 * back and no way to get one — anything that reaches this is code that should not be on the watch,
 * and `AuthRequiredException` is the failure the core already knows how to park on.
 */
internal object NoTokens : TokenProvider {
    override suspend fun accessToken(): String =
        throw AuthRequiredException("the watch does not sign in (ADR-002); the phone uploads")

    override suspend fun invalidate() = Unit
}
