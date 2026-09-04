package recly.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import okio.FileSystem
import okio.Path
import recly.core.drive.KtorTransport

/** Everything the shell owns and the core needs (docs/01 "코어 ↔ 셸 경계"). */
class CoreDeps(
    val clock: Clock,
    val logger: Logger,
    val secureStore: SecureStore,
    val tokenProvider: TokenProvider,
    /** Ktor unless the shell has a reason to own the transport — Apple's background uploads do. */
    val transport: Transport = KtorTransport(),
    val fileSystem: FileSystem,
    /** Lossless part concatenation for `transcribe` (docs/08); every shell has its own muxer. */
    val audio: AudioTools,
    val dataDir: Path,
    val device: DeviceInfo,
    /** Shell build version, `1.4.0` — the webhook `user-agent` is `rec/{appVersion} ({platform})` (docs/04). */
    val appVersion: String,
    /** Single dispatcher for file and DB work: SQLDelight drivers are not thread-safe everywhere. */
    val io: CoroutineDispatcher,
    /**
     * The app's language as a bare tag, `en` or `ko` (docs/07). The core needs it for one thing
     * only: the names of the seeded default workflows, which are user data from the moment they
     * are written. Everything else the core says to a person is a [recly.core.message.CoreMessage]
     * the shell translates.
     */
    val locale: String = "en",
)
