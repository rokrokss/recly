package recly.core.transcribe

/** Capability is independent of the selected preference; no implicit network or CPU fallback. */
enum class LocalEngineStatus { READY, MODEL_REQUIRED, UNSUPPORTED, WAITING }

data class LocalEngineInfo(
    val status: LocalEngineStatus,
    val name: String,
    val revision: String,
    val supportsDiarization: Boolean = false,
)

data class LocalTranscriptionRequest(
    val path: String,
    val language: String,
    val startTimeSec: Double = 0.0,
    val diarize: Boolean = false,
)
data class LocalTranscriptionResult(val segments: List<SttSegment>, val completed: Boolean = true)

interface LocalTranscriptionProgress {
    /** Final segments only, on the whole input file's axis. Native engines can safely resume here. */
    @Throws(Throwable::class)
    suspend fun checkpoint(segment: SttSegment, completedThroughSec: Double)
}

/** The shell owns native models, OS admission and cancellation. Audio never crosses this boundary. */
interface LocalTranscriptionEngine {
    /** Interrupt the native service too, including bridges that do not propagate coroutine cancellation. */
    fun cancel() {}
    @Throws(Throwable::class)
    suspend fun status(language: String): LocalEngineInfo

    /** Explicit model preparation, called only from the settings action. */
    @Throws(Throwable::class)
    suspend fun prepare(language: String): LocalEngineInfo

    /** Must cooperate with cancellation and stop when thermal/OS admission is withdrawn. */
    @Throws(Throwable::class)
    suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult
}

/** False for the placeholder: this build ships no on-device runtime, so `local` can never run here. */
val LocalTranscriptionEngine.installed: Boolean get() = this !is UnavailableLocalTranscriptionEngine

class UnavailableLocalTranscriptionEngine : LocalTranscriptionEngine {
    override suspend fun status(language: String) = LocalEngineInfo(LocalEngineStatus.UNSUPPORTED, "", "")
    override suspend fun prepare(language: String) = status(language)
    override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult =
        error("No validated local transcription runtime is installed")
}
