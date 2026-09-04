package app.recly.windows.detect

/**
 * The handoff between the two things that can hold a capture helper (docs/14 "감지").
 *
 * There must never be two helpers alive at once. The helper leaves **its own** process out of the
 * capture sessions it enumerates (`detect.rs`), so a detect-only helper running beside a recording
 * one would see Recly's own microphone and report a meeting that never goes quiet — and docs/14's
 * sixty-second idle offer would never be made. Two helpers also means two `mic_in_use` streams for one
 * microphone.
 *
 * So ownership moves, and it moves in one direction at a time: the recorder asks for it
 * ([yieldToRecorder], which does not return until the detect-only helper is closed and its reader
 * has ended) and gives it back when its own helper's stdout has ended ([resume]).
 *
 * **Ownership is per recording session, not per recorder.** A stop that had to be deferred leaves
 * its consumer coroutine running (`WindowsRecorder.stop`, `StopResult.Deferred`), and that consumer
 * reaches EOF at some point after a *later* recording has already taken the helper. Its `resume`
 * would then spawn a detect-only helper beside the running recording, and its queued microphone
 * events would be attributed to a session that is over. The token every call carries is what makes
 * both of those no-ops.
 */
interface Detection {
    /**
     * Suspends until nothing else holds a helper, and answers with the token that now owns it. The
     * recorder's own helper is opened after this returns, and every later call carries the token.
     */
    suspend fun yieldToRecorder(): Long

    /** The recording's helper has reached EOF. Ignored unless [token] is still the owner. */
    fun resume(token: Long)

    /** A `mic_in_use` from the recording's helper. Ignored unless [token] is still the owner. */
    fun micInUse(token: Long, app: String, inUse: Boolean)
}

/** No detection: the tests that are only about recording, and a host with no helper at all. */
object NoDetection : Detection {
    override suspend fun yieldToRecorder(): Long = 0

    override fun resume(token: Long) = Unit

    override fun micInUse(token: Long, app: String, inUse: Boolean) = Unit
}
