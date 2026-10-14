@file:OptIn(ExperimentalTime::class)

package recly.core.chatgpt

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.withContext
import recly.core.db.RecDatabase
import recly.core.message.CoreMessage
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptFill
import recly.core.transcribe.TranscriptFilled

/**
 * docs/08 "Titles and speaker names": with its switches on in Settings → ChatGPT, a transcript about to be published
 * first asks the user's ChatGPT plan for the recording's title — when it has none — and for the names of the speakers
 * nobody has named, so the folder's first copy, and whatever reads it, has them. The switch is the user's express
 * consent to this background use (docs/15 §10). Nothing here stops the publication: a skip or a failure publishes
 * the transcript as it was.
 */
internal class AutoFill(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val summaries: Summaries,
    private val consents: TransferConsents,
    private val recordings: RecordingRepository,
    /** Gives the recording [title] if it still has none, and carries it to its folder; false when it was not given. */
    private val setTitle: suspend (recordingId: String, title: String) -> Boolean,
) : TranscriptFill {
    override suspend fun fill(recording: RecordingRecord, transcript: Transcript): TranscriptFilled {
        val unchanged = TranscriptFilled(transcript)
        val preferences = summaries.preferences()
        if (!preferences.autoTitle && !preferences.autoSpeakers) return unchanged
        if (transcript.segments.none { it.text.isNotBlank() }) return unchanged
        val title = preferences.autoTitle && recordings.get(recording.id)?.meta?.title.isNullOrBlank()
        val spoken = transcript.segments.map { it.speaker }.toSet()
        val speakers = if (!preferences.autoSpeakers) emptyList() else transcript.speakers
            .filter { it.id in spoken && it.name.isNullOrBlank() && it.me != true }
            .map { it.id }
        if (!title && speakers.isEmpty()) return unchanged

        val log = mapOf("recordingId" to recording.id)
        skipped()?.let { reason ->
            deps.logger.log(Logger.Level.INFO, "summary.fill.skipped", log + ("reason" to reason))
            return unchanged
        }
        deps.logger.log(Logger.Level.INFO, "summary.fill.start", log + mapOf("title" to title, "speakers" to speakers.size))
        val answer = try {
            summaries.fill(transcript, title, speakers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? ChatGptFailure)?.reason ?: CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName)
            failed(recording.id, reason)
            return unchanged
        }
        val named = transcript.copy(
            speakers = transcript.speakers.map { speaker ->
                answer.speakers[speaker.id]?.takeIf { speaker.id in speakers }?.let { speaker.copy(name = it) } ?: speaker
            },
        )
        val titled = answer.title?.takeIf { title }?.takeIf {
            try {
                setTitle(recording.id, it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.logger.log(Logger.Level.WARN, "summary.fill.title.failed", log, e)
                false
            }
        }
        deps.logger.log(
            Logger.Level.INFO, "summary.fill.done",
            log + mapOf("title" to (titled != null), "speakers" to answer.speakers.count { it.key in speakers }),
        )
        return TranscriptFilled(if (named.speakers == transcript.speakers) transcript else named, titled)
    }

    /** Why nothing is sent now, before anything is: a pause after the plan said no, or no permission on iPhone. */
    private suspend fun skipped(): String? {
        val until = withContext(deps.io) { db.recQueries.kvGet(PAUSED_UNTIL).executeAsOneOrNull() }?.toLongOrNull()
        if (until != null && until > deps.clock.now().epochSeconds) return "paused"
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) return "consent"
        return null
    }

    /**
     * Logged, and for the plan's own refusals a pause: OpenAI asks that requests stop once the usage limit is reached
     * or the plan no longer covers this app, rather than being made again after every transcription. Signed out is
     * not a failure — the switches stay on and wait for a sign-in.
     */
    private suspend fun failed(recordingId: String, reason: String) {
        val code = reason.substringBefore(CoreMessage.DETAIL).substringBefore(CoreMessage.SEPARATOR)
        val log = mapOf("recordingId" to recordingId, "reason" to code)
        if (code == CoreMessage.CHATGPT_SIGN_IN_REQUIRED.name || code == CoreMessage.PROVIDER_REGION_RESTRICTED.name) {
            deps.logger.log(Logger.Level.INFO, "summary.fill.skipped", log)
            return
        }
        deps.logger.log(Logger.Level.WARN, "summary.fill.failed", log)
        val pause = when (code) {
            CoreMessage.CHATGPT_USAGE_LIMIT.name -> USAGE_PAUSE_SEC
            CoreMessage.CHATGPT_PLAN_REQUIRED.name -> PLAN_PAUSE_SEC
            else -> return
        }
        val until = deps.clock.now().epochSeconds + pause
        withContext(deps.io) { db.recQueries.kvSet(PAUSED_UNTIL, until.toString()) }
    }

    internal companion object {
        const val PAUSED_UNTIL = "summary/fill/paused_until"

        /** The plan's shorter usage window is five hours (developers.openai.com/siwc "Profiles and sessions", 2026-10-15). */
        const val USAGE_PAUSE_SEC = 5 * 3600L
        const val PLAN_PAUSE_SEC = 24 * 3600L
    }
}
