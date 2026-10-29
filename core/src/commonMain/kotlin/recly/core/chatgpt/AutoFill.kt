@file:OptIn(ExperimentalTime::class)

package recly.core.chatgpt

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * docs/08 "Titles and speaker names": what a recording still lacks that ChatGPT can fill in — a title when it has
 * none, and a name for each speaker who says something, has none and is not `me`. Nothing when nothing was said.
 * The one rule the core asks by and the shells offer More → Fill in title ([title]) and Fill in speaker names
 * ([names]) by.
 */
data class FillNeeds(val title: Boolean, val speakers: List<String>) {
    val any: Boolean get() = title || speakers.isNotEmpty()

    /** A speaker to name: what Fill in speaker names is offered by. */
    val names: Boolean get() = speakers.isNotEmpty()

    /** Only the halves asked for: the title when [title], the speakers when [names]. */
    internal fun only(title: Boolean, names: Boolean): FillNeeds =
        FillNeeds(this.title && title, if (names) speakers else emptyList())

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

/**
 * docs/08 "Titles and speaker names": a fill that is asking ChatGPT now, and applying its answer — at a publication or
 * from the detail. [title] and [names] are what it asks for: the halves asked that the recording lacked. One per
 * recording in `ReclyCore.observeFills`.
 */
data class FillRun(val recordingId: String, val title: Boolean, val names: Boolean)

/** What `ReclyCore.fill`, `fillTitle` and `fillNames` did. Concrete classes, so Swift reads them as they are. */
sealed interface FillResult {
    /** ChatGPT answered: [title] is whether the recording was given one, [names] how many speakers were named — either may be none. */
    data class Filled(val title: Boolean, val names: Int) : FillResult

    /**
     * Of what was asked, the recording has its title, or every speaker who speaks has a name, or nothing was said:
     * nothing was sent.
     */
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
 * docs/08 "Titles and speaker names": a recording's title and speaker names from the user's ChatGPT plan. Every
 * transcript this device publishes first asks for what it lacks — the publish runner's [TranscriptFill] — for a user
 * signed in who agreed to the plan's background use ([agreed], docs/08 "Organizing"), so the folder's first copy, and
 * whatever reads it, has them; nothing there stops the publication, a skip or a failure publishes the transcript as it
 * was. More → Fill in title and More → Fill in speaker names ask for a recording already transcribed ([manual]), each
 * on its own, on request (docs/15 §10). What is asking now, for either, is [observe].
 */
internal class AutoFill(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val summaries: Summaries,
    private val consents: TransferConsents,
    private val recordings: RecordingRepository,
    /** Signed in to ChatGPT and agreed to the plan's background use — the notes' consent (docs/08 "Organizing"). */
    private val agreed: suspend () -> Boolean,
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

    /** The detail's fill of a recording that is going, and the halves it was asked for. */
    private class Manual(val title: Boolean, val names: Boolean, val outcome: Deferred<FillResult>) {
        fun covers(title: Boolean, names: Boolean): Boolean = (this.title || !title) && (this.names || !names)
    }

    private val runs = mutableMapOf<String, Manual>()
    private val runLock = Mutex()

    /** The fills asking ChatGPT now, by recording. */
    private val asking = MutableStateFlow<Map<String, FillRun>>(emptyMap())

    /** docs/08 "Titles and speaker names": what is being filled in now — a publication's fill or the detail's. */
    fun observe(): Flow<List<FillRun>> = asking.map { it.values.toList() }

    /**
     * Signed out, or not agreed, the transcript is published as it is and nothing is logged: the same no-op `notes.organize`
     * is for anyone else. A publication tried again asks again for what is still missing.
     */
    override suspend fun fill(recording: RecordingRecord, transcript: Transcript): TranscriptFilled {
        val unchanged = TranscriptFilled(transcript)
        if (!agreed()) return unchanged
        val needs = FillNeeds.of(recordings.get(recording.id)?.meta?.title, transcript)
        if (!needs.any) return unchanged.also { skipped(recording.id, PUBLISH, "nothing") }
        backgroundSkip()?.let { reason -> return unchanged.also { skipped(recording.id, PUBLISH, reason) } }
        return shown(recording.id, needs) {
            val answer = try {
                ask(recording.id, transcript, needs, PUBLISH)
            } catch (e: ChatGptFailure) {
                return@shown unchanged
            }
            val named = transcript.copy(
                speakers = transcript.speakers.map { speaker ->
                    answer.speakers[speaker.id]?.takeIf { speaker.id in needs.speakers }?.let { speaker.copy(name = it) } ?: speaker
                },
            )
            val titled = answer.title?.takeIf { needs.title }?.takeIf { giveTitle(recording.id, it, PUBLISH) }
            done(recording.id, PUBLISH, titled != null, answer.speakers.count { it.key in needs.speakers })
            TranscriptFilled(if (named.speakers == transcript.speakers) transcript else named, titled)
        }
    }

    /**
     * More → Fill in title ([title]) and More → Fill in speaker names ([names]): asks for what [recordingId] lacks of
     * that ([FillNeeds]) and applies it — the title as a fill does, the names as an edit, since the transcript is
     * already in the folder. The background pause does not apply: the user asked. One run per recording: asking again
     * for what the run going asks for answers that run; asking for the other half waits for it, then runs on its own.
     * On iPhone a destination the user has not allowed answers `TRANSFER_CONSENT_REQUIRED` before anything is sent,
     * as a summary does.
     */
    suspend fun manual(recordingId: String, title: Boolean, names: Boolean): FillResult {
        if (!title && !names) return FillResult.NothingToFill
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) {
            skipped(recordingId, MANUAL, "consent")
            return FillResult.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code())
        }
        while (true) {
            val run = runLock.withLock {
                runs[recordingId] ?: Manual(title, names, scope.async { manualRun(recordingId, title, names) }).also { runs[recordingId] = it }
            }
            if (run.covers(title, names)) return run.outcome.await()
            run.outcome.join()
        }
    }

    private suspend fun manualRun(recordingId: String, title: Boolean, names: Boolean): FillResult = try {
        attempt(recordingId, title, names)
    } finally {
        withContext(NonCancellable) { runLock.withLock { runs.remove(recordingId) } }
    }

    private suspend fun attempt(recordingId: String, title: Boolean, names: Boolean): FillResult {
        if (busy(recordingId)) return FillResult.Busy.also { skipped(recordingId, MANUAL, "busy") }
        val record = recordings.get(recordingId) ?: return FillResult.NoTranscript.also { skipped(recordingId, MANUAL, "transcript") }
        val current = transcript(recordingId) ?: return FillResult.NoTranscript.also { skipped(recordingId, MANUAL, "transcript") }
        val needs = FillNeeds.of(record.meta.title, current).only(title, names)
        if (!needs.any) return FillResult.NothingToFill.also { skipped(recordingId, MANUAL, "nothing") }
        return shown(recordingId, needs) {
            val answer = try {
                ask(recordingId, current, needs, MANUAL)
            } catch (e: ChatGptFailure) {
                return@shown FillResult.Failed(e.reason)
            }
            val titled = answer.title?.takeIf { needs.title }?.let { giveTitle(recordingId, it, MANUAL) } ?: false
            val given = answer.speakers.filterKeys { it in needs.speakers }
            val named = if (given.isEmpty()) 0 else name(recordingId, given)
            // A transcription started meanwhile: nothing of the transcript was changed.
            if (named == null && !titled) return@shown FillResult.Busy.also { skipped(recordingId, MANUAL, "busy") }
            done(recordingId, MANUAL, titled, named ?: 0)
            FillResult.Filled(titled, named ?: 0)
        }
    }

    /** [block] asks ChatGPT for [needs] of [recordingId] and applies the answer: in [observe] while it goes, however it ends. */
    private suspend fun <T> shown(recordingId: String, needs: FillNeeds, block: suspend () -> T): T {
        asking.update { it + (recordingId to FillRun(recordingId, needs.title, needs.names)) }
        try {
            return block()
        } finally {
            asking.update { it - recordingId }
        }
    }

    /** One request for [needs], logged; a failure is logged too, and the plan's own refusals pause the background fills. */
    private suspend fun ask(recordingId: String, transcript: Transcript, needs: FillNeeds, trigger: String): FillAnswer {
        deps.logger.log(
            Logger.Level.INFO, "summary.fill.start",
            mapOf(
                "recordingId" to recordingId, "trigger" to trigger,
                "title" to needs.title, "names" to needs.names, "speakers" to needs.speakers.size,
            ),
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

    private suspend fun giveTitle(recordingId: String, title: String, trigger: String): Boolean = try {
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

    /** Tests: wait for the fills [manual] started. */
    internal suspend fun awaitRuns() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
    }

    internal companion object {
        const val PAUSED_UNTIL = "summary/fill/paused_until"

        /** The `trigger` of the log events: a publication, or the detail's More → Fill in title or Fill in speaker names. */
        const val PUBLISH = "publish"
        const val MANUAL = "manual"

        /** The plan's shorter usage window is five hours (developers.openai.com/siwc "Profiles and sessions", 2026-10-15). */
        const val USAGE_PAUSE_SEC = 5 * 3600L
        const val PLAN_PAUSE_SEC = 24 * 3600L
    }
}
