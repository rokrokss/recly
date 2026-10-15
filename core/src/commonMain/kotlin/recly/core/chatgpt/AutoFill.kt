@file:OptIn(ExperimentalTime::class)

package recly.core.chatgpt

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import recly.core.db.RecDatabase
import recly.core.message.CoreMessage
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptFill
import recly.core.transcribe.TranscriptFilled

/**
 * docs/08 "Titles and speaker names": what a recording still lacks that ChatGPT can fill in — a title when it has
 * none, and a name for each speaker who says something, has none and is not `me`. Nothing when nothing was said.
 * The one rule the core asks by and the shells offer More → Fill in title and names by ([any]).
 */
data class FillNeeds(val title: Boolean, val speakers: List<String>) {
    val any: Boolean get() = title || speakers.isNotEmpty()

    companion object {
        /** [title] is the recording's as it is now: blank asks for one, anything else — an import's file name too — stays. */
        fun of(title: String?, transcript: Transcript): FillNeeds {
            if (transcript.segments.none { it.text.isNotBlank() }) return FillNeeds(false, emptyList())
            val spoken = transcript.segments.map { it.speaker }.toSet()
            return FillNeeds(
                title = title.isNullOrBlank(),
                speakers = transcript.speakers.filter { it.id in spoken && it.name.isNullOrBlank() && it.me != true }.map { it.id },
            )
        }
    }
}

/** What `ReclyCore.fillTitleAndNames` did. Concrete classes, so Swift reads them as they are. */
sealed interface FillResult {
    /** ChatGPT answered: [title] is whether the recording was given one, [names] how many speakers were named — either may be none. */
    data class Filled(val title: Boolean, val names: Int) : FillResult

    /** The recording has its title and every speaker who speaks has a name, or nothing was said: nothing was sent. */
    data object NothingToFill : FillResult

    /** There is no transcript, here or in the recording's folder. */
    data object NoTranscript : FillResult

    /** A job of the recording has not settled, or it is being transcribed here — the rule of an edit. Nothing was sent. */
    data object Busy : FillResult

    /**
     * [reason] is a [CoreMessage] wire code, as a summary's is: `CHATGPT_SIGN_IN_REQUIRED`, `CHATGPT_USAGE_LIMIT`, … and,
     * on iPhone before anything is sent, `TRANSFER_CONSENT_REQUIRED`.
     */
    data class Failed(val reason: String) : FillResult
}

/**
 * docs/08 "Titles and speaker names": a recording's title and speaker names from the user's ChatGPT plan, asked for
 * one recording at a time. The title sheet after Stop asks for it ([request]): the transcript about to be published
 * first asks — the publish runner's [TranscriptFill] — so the folder's first copy, and whatever reads it, has them;
 * nothing there stops the publication, a skip or a failure publishes the transcript as it was. More → Fill in title
 * and names asks for a recording already transcribed ([manual]). Either is the user's express consent to that use of
 * the plan (docs/15 §10).
 */
internal class AutoFill(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val summaries: Summaries,
    private val consents: TransferConsents,
    private val recordings: RecordingRepository,
    /** Gives the recording [title] if it still has none, and carries it to its folder; false when it was not given. */
    private val setTitle: suspend (recordingId: String, title: String) -> Boolean,
    /** The recording's transcript as its detail shows it: this device's copy, or its folder's. */
    private val transcript: suspend (recordingId: String) -> Transcript?,
    /** A job of the recording has not settled, or it is being transcribed here: an edit of it would be refused. */
    private val busy: suspend (recordingId: String) -> Boolean,
    /**
     * Gives the speakers that still have no name the [names] ChatGPT suggested, as an edit of the transcript
     * (docs/08 "Editing"); how many were named, or null when the edit was refused.
     */
    private val name: suspend (recordingId: String, names: Map<String, String>) -> Int?,
) : TranscriptFill {
    /** A fill goes on when the screen that asked for it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = mutableMapOf<String, Deferred<FillResult>>()
    private val runLock = Mutex()

    override suspend fun fill(recording: RecordingRecord, transcript: Transcript): TranscriptFilled {
        if (!recordings.fillRequested(recording.id)) return TranscriptFilled(transcript)
        val filled = publish(recording, transcript)
        // Asked once — filled, failed or skipped. A pass cut short never gets here, and its publication asks again.
        recordings.takeFillRequest(recording.id)
        return filled
    }

    private suspend fun publish(recording: RecordingRecord, transcript: Transcript): TranscriptFilled {
        val unchanged = TranscriptFilled(transcript)
        val needs = FillNeeds.of(recordings.get(recording.id)?.meta?.title, transcript)
        if (!needs.any) return unchanged.also { skipped(recording.id, PUBLISH, "nothing") }
        backgroundSkip()?.let { reason -> return unchanged.also { skipped(recording.id, PUBLISH, reason) } }
        val answer = try {
            ask(recording.id, transcript, needs, PUBLISH)
        } catch (e: ChatGptFailure) {
            return unchanged
        }
        val named = transcript.copy(
            speakers = transcript.speakers.map { speaker ->
                answer.speakers[speaker.id]?.takeIf { speaker.id in needs.speakers }?.let { speaker.copy(name = it) } ?: speaker
            },
        )
        val titled = answer.title?.takeIf { needs.title }?.takeIf { title(recording.id, it, PUBLISH) }
        done(recording.id, PUBLISH, titled != null, answer.speakers.count { it.key in needs.speakers })
        return TranscriptFilled(if (named.speakers == transcript.speakers) transcript else named, titled)
    }

    /**
     * The title sheet's box was ticked for [recordingId]: kept for its publication. A transcript already published —
     * its job settled while the sheet was open — is filled now, in the background, as [manual] fills.
     */
    suspend fun request(recordingId: String) {
        if (!recordings.requestFill(recordingId)) return
        deps.logger.log(Logger.Level.INFO, "summary.fill.requested", mapOf("recordingId" to recordingId))
        if (busy(recordingId) || !transcribedHere(recordingId)) return
        if (!recordings.takeFillRequest(recordingId)) return
        scope.launch { manual(recordingId) }
    }

    /**
     * This device's copy of the transcript is on disk. Asked only with no job left to settle, when it means the
     * publication is over — a look at the disk, never at the folder.
     */
    private suspend fun transcribedHere(recordingId: String): Boolean {
        val record = recordings.get(recordingId) ?: return false
        val path = record.dir / TranscribeRunner.jsonFileName(MetaWriter.baseName(record.meta))
        return withContext(deps.io) { deps.fileSystem.exists(path) }
    }

    /**
     * More → Fill in title and names: asks for what [recordingId] lacks ([FillNeeds]) and applies it — the title as a
     * fill does, the names as an edit, since the transcript is already in the folder. The background pause does not
     * apply: the user asked. One run per recording; asking again while it runs answers that run. On iPhone a
     * destination the user has not allowed answers `TRANSFER_CONSENT_REQUIRED` before anything is sent, as a
     * summary does.
     */
    suspend fun manual(recordingId: String): FillResult {
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) {
            skipped(recordingId, MANUAL, "consent")
            return FillResult.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code())
        }
        val run = runLock.withLock {
            runs[recordingId]?.takeIf { it.isActive } ?: scope.async { manualRun(recordingId) }.also { runs[recordingId] = it }
        }
        return run.await()
    }

    private suspend fun manualRun(recordingId: String): FillResult {
        val outcome = attempt(recordingId)
        runLock.withLock { runs.remove(recordingId) }
        return outcome
    }

    private suspend fun attempt(recordingId: String): FillResult {
        if (busy(recordingId)) return FillResult.Busy.also { skipped(recordingId, MANUAL, "busy") }
        val record = recordings.get(recordingId) ?: return FillResult.NoTranscript.also { skipped(recordingId, MANUAL, "transcript") }
        val current = transcript(recordingId) ?: return FillResult.NoTranscript.also { skipped(recordingId, MANUAL, "transcript") }
        val needs = FillNeeds.of(record.meta.title, current)
        if (!needs.any) return FillResult.NothingToFill.also { skipped(recordingId, MANUAL, "nothing") }
        val answer = try {
            ask(recordingId, current, needs, MANUAL)
        } catch (e: ChatGptFailure) {
            return FillResult.Failed(e.reason)
        }
        val titled = answer.title?.takeIf { needs.title }?.let { title(recordingId, it, MANUAL) } ?: false
        val names = answer.speakers.filterKeys { it in needs.speakers }
        val named = if (names.isEmpty()) 0 else name(recordingId, names)
        // A transcription started meanwhile: nothing of the transcript was changed.
        if (named == null && !titled) return FillResult.Busy.also { skipped(recordingId, MANUAL, "busy") }
        done(recordingId, MANUAL, titled, named ?: 0)
        return FillResult.Filled(titled, named ?: 0)
    }

    /** One request for [needs], logged; a failure is logged too, and the plan's own refusals pause the background fills. */
    private suspend fun ask(recordingId: String, transcript: Transcript, needs: FillNeeds, trigger: String): FillAnswer {
        deps.logger.log(
            Logger.Level.INFO, "summary.fill.start",
            mapOf("recordingId" to recordingId, "trigger" to trigger, "title" to needs.title, "speakers" to needs.speakers.size),
        )
        return try {
            summaries.fill(transcript, needs.title, needs.speakers, trigger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? ChatGptFailure)?.reason ?: CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName)
            failed(recordingId, reason, trigger)
            throw ChatGptFailure(reason)
        }
    }

    private suspend fun title(recordingId: String, title: String, trigger: String): Boolean = try {
        setTitle(recordingId, title)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        deps.logger.log(Logger.Level.WARN, "summary.fill.title.failed", mapOf("recordingId" to recordingId, "trigger" to trigger), e)
        false
    }

    private fun done(recordingId: String, trigger: String, title: Boolean, speakers: Int) {
        deps.logger.log(
            Logger.Level.INFO, "summary.fill.done",
            mapOf("recordingId" to recordingId, "trigger" to trigger, "title" to title, "speakers" to speakers),
        )
    }

    private fun skipped(recordingId: String, trigger: String, reason: String) {
        deps.logger.log(Logger.Level.INFO, "summary.fill.skipped", mapOf("recordingId" to recordingId, "trigger" to trigger, "reason" to reason))
    }

    /** Why a publication sends nothing now, before anything is: a pause after the plan said no, or no permission on iPhone. */
    private suspend fun backgroundSkip(): String? {
        val until = withContext(deps.io) { db.recQueries.kvGet(PAUSED_UNTIL).executeAsOneOrNull() }?.toLongOrNull()
        if (until != null && until > deps.clock.now().epochSeconds) return "paused"
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) return "consent"
        return null
    }

    /**
     * Logged, and for the plan's own refusals a pause of the publications' fills: OpenAI asks that requests stop once
     * the usage limit is reached or the plan no longer covers this app, rather than being made again after every
     * transcription. Signed out, or not offered here, a publication is skipped rather than failed.
     */
    private suspend fun failed(recordingId: String, reason: String, trigger: String) {
        val code = reason.substringBefore(CoreMessage.DETAIL).substringBefore(CoreMessage.SEPARATOR)
        val log = mapOf("recordingId" to recordingId, "trigger" to trigger, "reason" to code)
        val quiet = code == CoreMessage.CHATGPT_SIGN_IN_REQUIRED.name || code == CoreMessage.PROVIDER_REGION_RESTRICTED.name
        if (quiet && trigger == PUBLISH) {
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

    /** Tests: wait for the fills [request] started in the background. */
    internal suspend fun awaitRuns() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
    }

    internal companion object {
        const val PAUSED_UNTIL = "summary/fill/paused_until"

        /** The `trigger` of the log events: a publication the title sheet asked for, or More → Fill in title and names. */
        const val PUBLISH = "publish"
        const val MANUAL = "manual"

        /** The plan's shorter usage window is five hours (developers.openai.com/siwc "Profiles and sessions", 2026-10-15). */
        const val USAGE_PAUSE_SEC = 5 * 3600L
        const val PLAN_PAUSE_SEC = 24 * 3600L
    }
}
