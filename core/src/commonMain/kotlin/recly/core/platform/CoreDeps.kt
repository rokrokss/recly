package recly.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import okio.FileSystem
import okio.Path
import recly.core.drive.KtorTransport
import recly.core.transcribe.TranscriptionPolicy

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
    /** Single dispatcher for file and DB work: SQLDelight drivers are not thread-safe everywhere. */
    val io: CoroutineDispatcher,
    /**
     * The app's language tag (docs/07), including a script or region when available. It seeds
     * the first transcription language; later locale changes do not replace saved user
     * preferences. User-visible core errors are [recly.core.message.CoreMessage]
     * codes translated by the shell.
     */
    val locale: String = "en",
    /** docs/15: the iOS shell supplies the destination-consent UI before enabling this policy. */
    val requireTransferConsent: Boolean = false,
    val transcriptionPolicy: TranscriptionPolicy = TranscriptionPolicy(),
    val localTranscription: recly.core.transcribe.LocalTranscriptionEngine = recly.core.transcribe.UnavailableLocalTranscriptionEngine(),
) {
    internal fun withTransport(transport: Transport): CoreDeps = CoreDeps(
        clock, logger, secureStore, tokenProvider, transport, fileSystem, audio, dataDir,
        device, io, locale, requireTransferConsent, transcriptionPolicy, localTranscription,
    )
}
