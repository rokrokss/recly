package recly.core.transcribe

/** Capability is independent of the selected preference; no implicit network or CPU fallback. */
enum class LocalEngineStatus { READY, MODEL_REQUIRED, UNSUPPORTED, WAITING }

/**
 * [modelBytes] is what [LocalTranscriptionEngine.prepare] downloads, when the engine knows it (the
 * app-managed model; Apple's system assets do not say). [progress] is how much of the model is
 * already here, 0–1, while it is partly downloaded, and [downloading] whether a download is running
 * now — so settings can show a percentage, and "resume" instead of "download".
 */
data class LocalEngineInfo(
    val status: LocalEngineStatus,
    val name: String,
    val revision: String,
    val supportsDiarization: Boolean = false,
    val modelBytes: Long? = null,
    val progress: Double? = null,
    val downloading: Boolean = false,
)

/**
 * [diarize] asks for speaker labels; it is only ever true for an engine that reports
 * [LocalEngineInfo.supportsDiarization]. [expectedSpeakers] is how many people the user said were in the
 * room (`context.participants`, docs/03), or null when nobody said. [vocabulary] is the words and names the
 * user listed (docs/05 "Fixed processing settings") — a hint the engine may use, or ignore.
 */
data class LocalTranscriptionRequest(
    val path: String,
    val language: String,
    val startTimeSec: Double = 0.0,
    val diarize: Boolean = false,
    val expectedSpeakers: Int? = null,
    val vocabulary: List<String> = emptyList(),
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

    /** Explicit model preparation, called only from a user's download action (settings, a waiting recording, the first-run card). */
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
