@file:OptIn(ExperimentalTime::class)

package recly.core.notes

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import recly.core.chatgpt.ChatGptAccount
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptFailure
import recly.core.chatgpt.ChatGptStanding
import recly.core.chatgpt.Summaries
import recly.core.db.RecDatabase
import recly.core.ids.Ulid
import recly.core.job.StepContext
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.job.StepRunner
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Part
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Step
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.model.wire
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.platform.SecureStore
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.processing.ProcessingPlan
import recly.core.processing.ProcessingSettingsRepository
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.LocalTranscriptionService
import recly.core.transcribe.PollResult
import recly.core.transcribe.SttContext
import recly.core.transcribe.SttProvider
import recly.core.transcribe.SttProviders
import recly.core.transcribe.SttResult
import recly.core.transcribe.Submitted
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/08 "Organizing": a recording's decisions, to-dos and open questions, organized through the user's ChatGPT
 * plan — always, with no switch, for a user who signed in to ChatGPT and agreed once ([consent]). While recording,
 * each part is transcribed on its own as it is filed and its new items are added ([NotesStage.PROVISIONAL]); after
 * Stop the processing plan's `notes.organize` step ([OrganizeRunner]) makes the final notes from the recording's
 * transcript, conversation by conversation, and they replace the provisional ones ([NotesStage.FINAL]). Only text
 * goes to ChatGPT — the transcript's lines, with numbers and addresses left out ([Placeholders]) — never audio
 * (docs/15 §10). The notes are kept beside the recording's parts, on this device only.
 */
class Notes internal constructor(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    private val account: ChatGptAccount,
    private val summaries: Summaries,
    private val recordings: RecordingRepository,
    private val settings: ProcessingSettingsRepository,
    private val local: LocalTranscriptionService,
    private val consents: TransferConsents,
    /** The recording's transcript as its detail shows it: this device's copy, or its folder's. */
    private val transcript: suspend (String) -> Transcript?,
    /** Emits whenever the recording's transcript may have changed — a speaker named, a transcription again. */
    private val transcriptChanges: (String) -> Flow<Any?>,
) {
    /** The providers a part goes to (tests replace it). */
    internal var providers: (String) -> SttProvider? = SttProviders::create

    /** How long a part waits between polls of an asynchronous provider (tests shorten it). */
    internal var pollMillis: Long = POLL_MILLIS

    /** The parts organized while recording; they outlive the call that filed them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One part at a time, in the order they were filed, so each sees the notes of the one before. */
    private val partLock = Mutex()

    /** Every read-modify-write of a notes file. */
    private val fileLock = Mutex()

    private val changes = MutableStateFlow(0L)
    private val working = MutableStateFlow<Set<String>>(emptySet())
    private val consented = MutableStateFlow<Boolean?>(null)

    // --- Consent ---

    /**
     * Whether to ask the user before the next recording: signed in to ChatGPT and not yet agreed to organizing.
     * The sign-in screen shows the same consent, and the shell calls [consent] when the user signs in under it; a
     * user signed in before this existed is asked once, at their first recording start — Continue ([consent]) or
     * Sign out (`chatGpt.signOut`). Read from this device's store; no request is made.
     */
    @Throws(Throwable::class)
    suspend fun needsConsent(): Boolean = signedIn(account.standing()) && !consented()

    /**
     * [needsConsent] as the connection and the consent change; signed out, it is false. Never fails: a Swift
     * `for await` over a flow that throws ends the app, so a store that will not be read counts as not agreed.
     */
    fun observeNeedsConsent(): Flow<Boolean> = flow {
        try {
            consented()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consented.compareAndSet(null, false)
        }
        emitAll(
            combine(account.observe(), consented.filterNotNull()) { connection, agreed ->
                (connection is ChatGptConnection.SignedIn || connection is ChatGptConnection.Expired) && !agreed
            },
        )
    }.distinctUntilChanged()

    /**
     * The user agreed to organizing — under the sign-in, or at the question before a recording. Kept on this device
     * until they sign out of ChatGPT. On iPhone the shell also grants `TransferTargets.chatGptSummary()` in the same
     * action: without that destination nothing is sent (docs/15 "iPhone providers").
     */
    @Throws(Throwable::class)
    suspend fun consent() {
        withContext(deps.io) { db.recQueries.kvSet(CONSENT_KEY, ON) }
        consented.value = true
        deps.logger.log(Logger.Level.INFO, "organize.consent", emptyMap())
    }

    /** Signing out of ChatGPT takes the consent with it: the next sign-in asks again. */
    internal suspend fun signedOut() {
        withContext(deps.io) { db.recQueries.kvDelete(CONSENT_KEY) }
        consented.value = false
        deps.logger.log(Logger.Level.INFO, "organize.consent.cleared", emptyMap())
    }

    private suspend fun consented(): Boolean = consented.value ?: withContext(deps.io) {
        db.recQueries.kvGet(CONSENT_KEY).executeAsOneOrNull() == ON
    }.also { consented.compareAndSet(null, it) }

    private fun signedIn(standing: ChatGptStanding) = standing == ChatGptStanding.SIGNED_IN || standing == ChatGptStanding.EXPIRED

    private enum class Gate { ON, OFF, EXPIRED }

    /** On for a signed-in, consenting user — and, on iPhone, the plan allowed as a destination. */
    private suspend fun gate(): Gate {
        val standing = account.standing()
        if (!signedIn(standing) || !consented()) return Gate.OFF
        if (consents.missing(listOf(TransferTargets.chatGptSummary())).isNotEmpty()) return Gate.OFF
        return if (standing == ChatGptStanding.EXPIRED) Gate.EXPIRED else Gate.ON
    }

    /** Organizing is off on this device: the final step would send nothing (the offline pass may run it). */
    internal suspend fun off(): Boolean = try {
        gate() == Gate.OFF
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }

    // --- What the shells read ---

    /**
     * The recording's notes as they change: a provisional item as soon as its part is done, the final notes once
     * the final pass has written them, an owner as soon as its speaker is named. Every recording has a value —
     * [NotesStage.NONE] with nothing in it when it has no notes.
     */
    fun observe(recordingId: String): Flow<RecordingNotes> =
        combine(changes, working, transcriptChanges(recordingId).onStart { emit(Unit) }) { _, busy, _ -> recordingId in busy }
            .map { organizing ->
                // Never fails, for the same reason as [observeNeedsConsent]: a notes file that cannot be read now is none.
                try {
                    view(recordingId, organizing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    empty(recordingId, organizing)
                }
            }
            .distinctUntilChanged()

    /** [observe]'s value now. */
    @Throws(Throwable::class)
    suspend fun get(recordingId: String): RecordingNotes = view(recordingId, recordingId in working.value)

    /**
     * Removes an item for good: it is gone from the notes now and never comes back — not from a later part, not
     * from the final pass, not from a transcription again. False when there is no such item.
     */
    @Throws(Throwable::class)
    suspend fun deleteItem(recordingId: String, itemId: String): Boolean {
        val now = deps.clock.now().isoUtc()
        var provisional = false
        val deleted = update(recordingId) { file ->
            val note = file?.items?.firstOrNull { it.id == itemId } ?: return@update null
            provisional = note.provisional
            file.copy(
                items = file.items - note,
                tombstones = file.tombstones + NoteTombstone(note.kind, note.text, note.evidence.atSec, now),
                updatedAt = now,
            )
        }
        if (deleted) deps.logger.log(Logger.Level.INFO, "organize.item.deleted", mapOf("recordingId" to recordingId, "provisional" to provisional))
        return deleted
    }

    /**
     * The transcript's lines around [atSec] — from [BEFORE_SEC] before it to [AFTER_SEC] after, at most [MAX_LINES] —
     * for "show the transcript here" under an item: the recording's transcript once there is one, the parts'
     * made while recording before that (there is no playback while recording).
     */
    @Throws(Throwable::class)
    suspend fun linesAround(recordingId: String, atSec: Double): List<NoteLine> {
        val record = recordings.get(recordingId) ?: return emptyList()
        val whole = if (record.meta.status == RecordingStatus.FINALIZED) readTranscript(recordingId) else null
        if (whole != null) return around(whole.segments, whole.speakers, atSec, provisional = false)
        val parts = provisionalTranscripts(record)
        return around(parts.flatMap { it.segments }, emptyList(), atSec, provisional = true)
    }

    private suspend fun view(recordingId: String, organizing: Boolean): RecordingNotes {
        val file = readNotes(recordingId) ?: return empty(recordingId, organizing)
        val names = if (file.items.any { !it.provisional && it.owner != null }) {
            readTranscript(recordingId)?.speakers.orEmpty().mapNotNull { speaker -> speaker.name?.trim()?.takeIf { it.isNotEmpty() }?.let { speaker.id to it } }.toMap()
        } else {
            emptyMap()
        }
        val items = file.items
            .sortedWith(compareByDescending<StoredNote> { it.evidence.atSec }.thenByDescending { it.createdAt })
            .map { note ->
                NoteItem(
                    id = note.id, kind = note.kind, text = note.text,
                    // A part's own label names nobody the user can see; only a named speaker of the transcript is shown.
                    owner = note.owner?.takeUnless { note.provisional }?.let(names::get),
                    due = note.due, atSec = note.evidence.atSec, quote = note.evidence.quote, provisional = note.provisional,
                )
            }
        return RecordingNotes(
            recordingId = recordingId,
            stage = if (file.isFinal) NotesStage.FINAL else NotesStage.PROVISIONAL,
            overview = file.overview,
            decisions = items.filter { it.kind == NoteKind.DECISION },
            todos = items.filter { it.kind == NoteKind.TODO },
            questions = items.filter { it.kind == NoteKind.QUESTION },
            organizing = organizing,
        )
    }

    private fun empty(recordingId: String, organizing: Boolean) =
        RecordingNotes(recordingId, NotesStage.NONE, null, emptyList(), emptyList(), emptyList(), organizing)

    private fun around(segments: List<TranscriptSegment>, speakers: List<TranscriptSpeaker>, atSec: Double, provisional: Boolean): List<NoteLine> {
        val byId = speakers.associateBy { it.id }
        return segments.filter { it.text.isNotBlank() && it.end >= atSec - BEFORE_SEC && it.start <= atSec + AFTER_SEC }
            .sortedBy { kotlin.math.abs(it.start - atSec) }
            .take(MAX_LINES)
            .sortedBy { it.start }
            .map { segment ->
                val speaker = byId[segment.speaker]
                NoteLine(
                    atSec = segment.start,
                    endSec = segment.end,
                    speakerId = segment.speaker.takeIf { it.isNotEmpty() },
                    speakerName = speaker?.name?.trim()?.takeIf { it.isNotEmpty() },
                    me = speaker?.me == true,
                    text = segment.text.trim(),
                    provisional = provisional,
                )
            }
    }

    // --- While recording ---

    /**
     * [RecordingRepository.addPart] filed [part]: organized in the background, never in the recorder's way.
     * [received] is a part of a watch recording still being made, which the transfer filed here and its
     * in-progress meta lists (`TransferReceiver.partReceived`, docs/03 "Watch → phone transfer contract").
     */
    internal fun partFiled(recordingId: String, part: Part, received: Boolean = false) {
        scope.launch {
            try {
                partLock.withLock { provisional(recordingId, part, received) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                deps.logger.log(Logger.Level.WARN, "organize.part.failed", mapOf("recordingId" to recordingId, "part" to part.part, "stage" to "internal"), e)
            }
        }
    }

    /** Tests: wait for the parts filed so far. */
    internal suspend fun awaitWork() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
    }

    private suspend fun provisional(recordingId: String, part: Part, received: Boolean) {
        val record = recordings.get(recordingId) ?: return
        val meta = record.meta
        // A recording in progress: this device's own, or a watch's coming in part by part — never a watch's
        // own recording on the watch itself, and not an import.
        if (record.remote || meta.status != RecordingStatus.RECORDING || meta.source == Source.IMPORT) return
        if ((meta.source == Source.WATCH) != received) return
        if (part.track != track(meta.tracks)) return
        // A keychain that will not be read now is a part left out, like any other failure while recording.
        val open = try {
            gate() == Gate.ON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
        if (!open) return
        val notes = readNotes(record)
        if (notes?.isFinal == true || notes?.parts?.contains(part.part) == true) return
        // The settings the recording froze when it started, as its own job will run them.
        val step = settings.recordingSnapshot(recordingId)?.let(ProcessingPlan::compile)?.steps
            ?.firstOrNull { it is Step.Transcribe || it is Step.LocalTranscribe } ?: return
        working.update { it + recordingId }
        try {
            organizePart(record, part, step)
        } finally {
            working.update { it - recordingId }
        }
    }

    private class PartSkipped(val reason: String) : Exception(reason)

    private suspend fun organizePart(record: RecordingRecord, part: Part, step: Step) {
        val fields = mapOf("recordingId" to record.id, "part" to part.part)
        val transcribed = try {
            when (step) {
                is Step.Transcribe -> external(record, part, step)
                is Step.LocalTranscribe -> onDevice(record, part, step)
                else -> return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PartSkipped) {
            return partDone(record, part, "organize.part.skipped", fields + ("reason" to e.reason))
        } catch (e: Throwable) {
            return partDone(record, part, "organize.part.failed", fields + ("stage" to "transcribe") + ("reason" to reasonOf(e)), Logger.Level.WARN)
        }
        recordings.writeFile(record.id, provisionalName(part.part), recJson.encodeToString(Transcript.serializer(), transcribed))
        deps.logger.log(
            Logger.Level.INFO, "organize.part.transcribed",
            fields + ("provider" to transcribed.provider.name) + ("segments" to transcribed.segments.size),
        )
        if (NoteText.words(transcribed.segments.joinToString(" ") { it.text }) < MIN_WORDS) {
            return partDone(record, part, "organize.part.skipped", fields + ("reason" to "short"))
        }
        try {
            extract(record, part, transcribed, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No retries while recording: this part is left out, and the final pass covers it.
            partDone(record, part, "organize.part.failed", fields + ("stage" to "extract") + ("reason" to reasonOf(e)), Logger.Level.WARN)
        }
    }

    /** The provider of the recording's settings, through the same adapter, submission and polls as its job. */
    private suspend fun external(record: RecordingRecord, part: Part, step: Step.Transcribe): Transcript {
        if (deps.transcriptionPolicy.issue(step) != null) throw PartSkipped("not_offered")
        TransferTargets.forStep(step)?.let { if (consents.missing(listOf(it)).isNotEmpty()) throw PartSkipped("consent") }
        val provider = providers(step.provider) ?: throw PartSkipped("provider")
        val key = deps.secureStore.get(SecureStore.SECRETS, step.secretRef)?.decodeToString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PartSkipped("no_key")
        val ctx = SttContext(
            step = step,
            apiKey = key,
            speakersExpected = step.speakers.min.takeIf { it == step.speakers.max },
            audioDurationSec = part.durationSec,
            deps = deps.transcriptionPolicy.guardedDeps(step, consents.guardedDeps(step)),
        )
        val result = when (val submitted = provider.submit(ctx, record.dir / part.file)) {
            is Submitted.Finished -> submitted.result
            is Submitted.Polling -> poll(provider, ctx, submitted.ref)
        }
        return partTranscript(
            record, part, result, step.diarize,
            TranscriptProvider(provider.name, result.model ?: step.model), result.language ?: step.language.wire,
        )
    }

    private suspend fun poll(provider: SttProvider, ctx: SttContext, ref: String): SttResult {
        val limit = minOf(provider.resultTimeout, PART_TIMEOUT)
        repeat((limit.inWholeMilliseconds / POLL_MILLIS).toInt().coerceAtLeast(1)) {
            delay(pollMillis)
            when (val polled = provider.poll(ctx, ref)) {
                PollResult.Pending -> Unit
                is PollResult.Done -> return polled.result
                is PollResult.Failed -> throw IllegalStateException("${provider.name} ${polled.reason}")
            }
        }
        throw IllegalStateException("'$ref' did not finish within $limit")
    }

    /** The on-device engine, one run at a time with the job's own ([LocalTranscriptionService.transcribePart]). */
    private suspend fun onDevice(record: RecordingRecord, part: Part, step: Step.LocalTranscribe): Transcript {
        val info = deps.localTranscription.status(step.language.wire)
        if (info.status != LocalEngineStatus.READY) throw PartSkipped("engine")
        val segments = local.transcribePart(
            LocalTranscriptionRequest(path = (record.dir / part.file).toString(), language = step.language.wire, vocabulary = step.vocabulary),
        )
        return partTranscript(
            record, part, SttResult(segments, step.language.wire, null, info.revision), diarize = false,
            TranscriptProvider(info.name, info.revision), step.language.wire,
        )
    }

    /** The part on the recording's own axis. Without speaker labels it says so, rather than calling everyone S1. */
    private fun partTranscript(
        record: RecordingRecord,
        part: Part,
        result: SttResult,
        diarize: Boolean,
        provider: TranscriptProvider,
        language: String,
    ): Transcript {
        val normalized = TranscriptNormalizer.normalize(record.id, part.track, listOf(part), result, diarize, provider, deps.clock.now().isoUtc(), language)
        if (diarize) return normalized
        return normalized.copy(
            schema = Transcript.LOCAL_SCHEMA,
            speakers = emptyList(),
            segments = normalized.segments.map { it.copy(speaker = "") },
            speakerIdentification = "unavailable",
        )
    }

    /** The new items of one part, append-only: checked, then added to what is shown. */
    private suspend fun extract(record: RecordingRecord, part: Part, transcribed: Transcript, fields: Map<String, Any?>) {
        val previous = readProvisional(record.id, part.part - 1)
        val shown = readNotes(record)?.items.orEmpty()
        val placeholders = Placeholders()
        val input = placeholders.hide(
            NotesPrompts.provisionalInput(
                shown,
                previous?.let { NoteLines.tail(NoteLines.of(it.segments), TAIL_CHARS) }.orEmpty(),
                NoteLines.render(NoteLines.of(transcribed.segments)),
            ),
        )
        val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        deps.logger.log(Logger.Level.INFO, "organize.part.start", fields + ("model" to model) + ("hidden" to placeholders.hidden))
        val json = NotesAnswer.parse(
            summaries.complete(model, NotesPrompts.provisional(summaries.language(transcribed.language)), input, timeoutSec = PART_REQUEST_SEC),
        )
        val offered = NotesAnswer.offered(json)
        val speakers = transcribed.speakers.map { it.id }.toSet()
        val evidence = transcribed.segments + previous?.segments.orEmpty()
        val start = part.startOffsetSec - EDGE_SEC
        val end = part.startOffsetSec + part.durationSec + EDGE_SEC
        val checked = NotesAnswer.items(json, placeholders::restore)
            .filter { it.atSec in start..end && QuoteCheck.passes(it.quote, it.atSec, evidence) }
            .map { if (it.owner != null && it.owner !in speakers) it.copy(owner = null) else it }
        val now = deps.clock.now().isoUtc()
        var added = 0
        var tombstoned = 0
        var repeated = 0
        update(record.id) { current ->
            // The final pass came first: what is shown now is the final notes, and they stay as they are.
            if (current?.isFinal == true) return@update null
            val file = current ?: NotesFile(recordingId = record.id, createdAt = now, updatedAt = now)
            val kept = checked.filter { item ->
                when {
                    file.tombstones.any { SameItem.matches(item.kind, item.text, item.atSec, it.kind, it.text, it.atSec) } -> false.also { tombstoned++ }
                    file.items.any { repeats(item, it) } -> false.also { repeated++ }
                    else -> true
                }
            }
            added = kept.size
            file.copy(
                items = file.items + kept.map { item ->
                    StoredNote(ulid(), item.kind, item.text, item.owner, item.due, NoteEvidence(item.atSec, item.quote), provisional = true, createdAt = now)
                },
                parts = (file.parts + part.part).distinct().sorted(),
                model = model,
                updatedAt = now,
            )
        }
        deps.logger.log(
            Logger.Level.INFO, "organize.part.done",
            fields + mapOf("items" to added, "dropped" to offered - added - tombstoned - repeated, "tombstoned" to tombstoned, "repeated" to repeated),
        )
    }

    /** The model said again what is already shown — the same words, or the same moment in the same words. */
    private fun repeats(item: Extracted, shown: StoredNote): Boolean {
        if (item.kind != shown.kind) return false
        if (NoteText.ratio(NoteText.normalize(item.text), NoteText.normalize(shown.text)) >= REPEAT_RATIO) return true
        return kotlin.math.abs(item.atSec - shown.evidence.atSec) <= SameItem.SAME_MOMENT_SEC &&
            NoteText.ratio(NoteText.chars(item.quote), NoteText.chars(shown.evidence.quote)) >= QuoteCheck.THRESHOLD
    }

    /** The part is done with — notes or not — and is not asked again; [event] says how it went. */
    private suspend fun partDone(record: RecordingRecord, part: Part, event: String, fields: Map<String, Any?>, level: Logger.Level = Logger.Level.INFO) {
        val now = deps.clock.now().isoUtc()
        update(record.id) { current ->
            if (current?.isFinal == true) return@update null
            val file = current ?: NotesFile(recordingId = record.id, createdAt = now, updatedAt = now)
            file.copy(parts = (file.parts + part.part).distinct().sorted(), updatedAt = now)
        }
        deps.logger.log(level, event, fields)
    }

    // --- After Stop: the processing plan's `notes.organize` ---

    /**
     * The final notes of the recording's transcript ([OrganizeRunner]). A no-op for a user who is not signed in or has
     * not agreed. The plan's own refusals wait: a usage limit five hours, a plan that no longer covers this app a day,
     * a sign-in OpenAI ended six hours — each a try of the step's budget, after which the job is done without them.
     * What is finished is kept in the step's state, so a try after a wait asks only what is left.
     */
    internal suspend fun organize(ctx: StepContext): StepOutcome {
        val recordingId = ctx.recording.id
        when (gate()) {
            Gate.OFF -> return skipped(recordingId, "off")
            Gate.EXPIRED -> throw failure(recordingId, CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
            Gate.ON -> Unit
        }
        val transcript = readTranscript(recordingId) ?: return skipped(recordingId, "no_transcript")
        working.update { it + recordingId }
        try {
            return finalPass(ctx, transcript)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepFailure) {
            throw e
        } catch (e: ChatGptFailure) {
            throw failure(recordingId, e.reason)
        } catch (e: Exception) {
            throw failure(recordingId, CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName))
        } finally {
            working.update { it - recordingId }
        }
    }

    private suspend fun finalPass(ctx: StepContext, transcript: Transcript): StepOutcome {
        val recordingId = ctx.recording.id
        val segments = transcript.segments.filter { it.text.isNotBlank() }.sortedBy { it.start }
        // A try after a wait goes on where the last one stopped — unless what is sent changed since: a speaker named
        // meanwhile changes nothing sent, an edit of the words does.
        val version = NoteLines.render(NoteLines.of(segments)).encodeUtf8().sha256().hex()
        var state = OrganizeState.from(ctx.state)?.takeIf { it.transcript == version } ?: OrganizeState(version)
        val model = account.model() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        val language = summaries.language(transcript.language)
        if (state.spans == null) {
            val spans = ConversationSplit.blocks(segments).flatMapIndexed { index, block ->
                if (block.endSec - block.startSec <= ConversationSplit.LONG_SEC) return@flatMapIndexed listOf(block)
                val lines = segments.filter { it.start >= block.startSec && it.start <= block.endSec }
                val placeholders = Placeholders()
                val answer = summaries.complete(model, NotesPrompts.boundaries(), placeholders.hide(NotesPrompts.transcriptInput(NoteLines.render(NoteLines.of(lines)))))
                val found = NotesAnswer.boundaries(NotesAnswer.parse(answer))
                ConversationSplit.cut(block, found).also {
                    deps.logger.log(
                        Logger.Level.INFO, "organize.final.boundaries",
                        mapOf("recordingId" to recordingId, "block" to index + 1, "answered" to found.size, "kept" to it.size - 1),
                    )
                }
            }
            state = state.copy(spans = spans, done = List(spans.size) { null })
            ctx.saveState(state.toJson())
        }
        val spans = state.spans!!
        val grouped = ConversationSplit.assign(segments, spans)
        deps.logger.log(Logger.Level.INFO, "organize.final.start", mapOf("recordingId" to recordingId, "conversations" to spans.size, "model" to model))
        val results = state.done.toMutableList()
        while (results.size < spans.size) results += null
        for (index in spans.indices) {
            if (results[index] != null) continue
            results[index] = conversation(model, language, grouped[index], transcript)
            state = state.copy(done = results.toList())
            ctx.saveState(state.toJson())
        }
        val conversations = spans.mapIndexed { index, span -> span.copy(summary = results[index]?.summary) }
        val summarized = conversations.filter { it.summary != null }
        val overview = when (summarized.size) {
            0 -> null
            1 -> summarized.single().summary
            else -> {
                val placeholders = Placeholders()
                val answer = summaries.complete(model, NotesPrompts.overview(language), placeholders.hide(NotesPrompts.overviewInput(summarized)))
                NotesAnswer.overview(NotesAnswer.parse(answer), placeholders::restore)
            }
        }
        val finals = results.flatMapIndexed { index, result -> result?.items.orEmpty().map { index to it } }
        val now = deps.clock.now().isoUtc()
        var tombstoned = 0
        var parts = emptyList<Int>()
        update(recordingId) { current ->
            val tombstones = current?.tombstones.orEmpty()
            // The final items replace the provisional ones; one that says the same keeps its id, so it does not jump.
            val earlier = current?.items.orEmpty().toMutableList()
            parts = current?.parts.orEmpty()
            val items = finals.mapNotNull { (conversation, item) ->
                if (tombstones.any { SameItem.matches(item.kind, item.text, item.atSec, it.kind, it.text, it.atSec) }) {
                    tombstoned++
                    return@mapNotNull null
                }
                val same = earlier.firstOrNull { SameItem.matches(item.kind, item.text, item.atSec, it.kind, it.text, it.evidence.atSec) }
                    ?.also { earlier.remove(it) }
                StoredNote(
                    id = same?.id ?: ulid(), kind = item.kind, text = item.text, owner = item.owner, due = item.due,
                    evidence = NoteEvidence(item.atSec, item.quote), provisional = false, conversation = conversation,
                    createdAt = same?.createdAt ?: now,
                )
            }
            NotesFile(
                recordingId = recordingId, stage = NotesFile.FINAL, overview = overview, items = items, tombstones = tombstones,
                conversations = conversations, parts = parts, model = model,
                createdAt = current?.createdAt ?: now, updatedAt = now, finalizedAt = now,
            )
        }
        // What was transcribed while recording has served its purpose: the recording's transcript is here.
        parts.forEach { recordings.deleteFile(recordingId, provisionalName(it)) }
        val kept = finals.size - tombstoned
        deps.logger.log(
            Logger.Level.INFO, "organize.final.done",
            mapOf(
                "recordingId" to recordingId, "conversations" to spans.size, "items" to kept, "tombstoned" to tombstoned,
                "dropped" to results.sumOf { it?.dropped ?: 0 }, "overview" to (overview != null),
            ),
        )
        return StepOutcome.Done(StepOutput(buildJsonObject {
            put("notes", "written")
            put("conversations", spans.size)
            put("items", kept)
        }))
    }

    /** One conversation's summary and items; too little said, and nothing is asked. */
    private suspend fun conversation(model: String, language: String, segments: List<TranscriptSegment>, transcript: Transcript): ConversationResult {
        if (NoteText.words(segments.joinToString(" ") { it.text }) < MIN_WORDS) return ConversationResult()
        val placeholders = Placeholders()
        val answer = summaries.complete(
            model, NotesPrompts.conversation(language),
            placeholders.hide(NotesPrompts.transcriptInput(NoteLines.render(NoteLines.of(segments)))),
        )
        val json = NotesAnswer.parse(answer)
        val items = NotesAnswer.items(json, placeholders::restore)
        val speakers = transcript.speakers.map { it.id }.toSet()
        val kept = items.filter { QuoteCheck.passes(it.quote, it.atSec, transcript.segments) }
            .map { if (it.owner != null && it.owner !in speakers) it.copy(owner = null) else it }
        return ConversationResult(NotesAnswer.summary(json, placeholders::restore), kept, dropped = NotesAnswer.offered(json) - kept.size)
    }

    private fun skipped(recordingId: String, reason: String): StepOutcome {
        deps.logger.log(Logger.Level.INFO, "organize.final.skipped", mapOf("recordingId" to recordingId, "reason" to reason))
        return StepOutcome.Done(StepOutput(buildJsonObject {
            put("notes", "skipped")
            put("reason", reason)
        }))
    }

    /** The existing retry machinery, with the waits the plan's own refusals ask for. */
    private fun failure(recordingId: String, reason: String): StepFailure {
        val message = CoreMessageRef.parse(reason)?.message
        deps.logger.log(Logger.Level.WARN, "organize.final.failed", mapOf("recordingId" to recordingId, "reason" to (message?.name ?: reason.substringBefore('|'))))
        return when (message) {
            CoreMessage.CHATGPT_USAGE_LIMIT -> StepFailure(retryable = true, reason = reason, retryAfterSec = USAGE_WAIT_SEC)
            CoreMessage.CHATGPT_PLAN_REQUIRED -> StepFailure(retryable = true, reason = reason, retryAfterSec = PLAN_WAIT_SEC)
            CoreMessage.CHATGPT_SIGN_IN_REQUIRED -> StepFailure(retryable = true, reason = reason, retryAfterSec = SIGN_IN_WAIT_SEC)
            // Not offered in this storefront any more: there is nothing to wait for.
            CoreMessage.PROVIDER_REGION_RESTRICTED -> StepFailure(retryable = false, reason = reason)
            else -> StepFailure(retryable = true, reason = reason)
        }
    }

    // --- Files ---

    private fun base(record: RecordingRecord): String = MetaWriter.baseName(record.meta)

    private suspend fun readNotes(recordingId: String): NotesFile? = recordings.get(recordingId)?.let { readNotes(it) }

    private suspend fun readNotes(record: RecordingRecord): NotesFile? =
        recordings.readFile(record.id, NotesFile.name(base(record)))?.let { json ->
            runCatching { recJson.decodeFromString(NotesFile.serializer(), json) }.getOrNull()?.takeIf { it.recordingId == record.id }
        }

    /** [change] makes the next version of the recording's notes, or null to leave them; true when it was written. */
    private suspend fun update(recordingId: String, change: (NotesFile?) -> NotesFile?): Boolean = fileLock.withLock {
        val record = recordings.get(recordingId) ?: return@withLock false
        val next = change(readNotes(record)) ?: return@withLock false
        recordings.writeFile(recordingId, NotesFile.name(base(record)), recJson.encodeToString(NotesFile.serializer(), next))
            .also { if (it) changes.value++ }
    }

    private suspend fun readProvisional(recordingId: String, part: Int): Transcript? =
        if (part < 1) null else recordings.readFile(recordingId, provisionalName(part))?.let { json ->
            runCatching { recJson.decodeFromString(Transcript.serializer(), json) }.getOrNull()
        }

    private suspend fun provisionalTranscripts(record: RecordingRecord): List<Transcript> {
        val track = track(record.meta.tracks)
        return record.meta.parts.filter { it.track == track }.map { it.part }.distinct().sorted().mapNotNull { readProvisional(record.id, it) }
    }

    /** The recording's transcript, or null when it has none or it cannot be read now. */
    private suspend fun readTranscript(recordingId: String): Transcript? = try {
        transcript(recordingId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun ulid(): String = Ulid.generate(object : kotlin.time.Clock {
        override fun now() = deps.clock.now()
    })

    private fun reasonOf(e: Throwable): String = when (e) {
        is ChatGptFailure -> e.reason.substringBefore('|')
        is StepFailure -> e.reason.substringBefore('|')
        else -> e::class.simpleName ?: "error"
    }

    internal companion object {
        const val CONSENT_KEY = "notes/consent"
        private const val ON = "1"

        /** A part or a conversation with fewer words than this is not asked about. */
        const val MIN_WORDS = 40

        /** How much of the previous part's end goes with a part, for context. */
        const val TAIL_CHARS = 2400

        /** An item's time may lie this far outside its part: a line that began just before it. */
        const val EDGE_SEC = 10.0

        /** How alike two texts are for the model to have merely said again what is shown. */
        const val REPEAT_RATIO = 0.85

        const val POLL_MILLIS = 15_000L
        val PART_TIMEOUT = 30.minutes
        const val PART_REQUEST_SEC = 180

        /** The plan's shorter usage window (developers.openai.com/siwc, 2026-10-15), as the title fills wait. */
        const val USAGE_WAIT_SEC = 5 * 3600L
        const val PLAN_WAIT_SEC = 24 * 3600L
        const val SIGN_IN_WAIT_SEC = 6 * 3600L

        const val BEFORE_SEC = 20.0
        const val AFTER_SEC = 40.0
        const val MAX_LINES = 12

        /** `.provisional-p001.json`: one part transcribed while recording, on the recording's axis. */
        fun provisionalName(part: Int): String = ".provisional-p${part.toString().padStart(3, '0')}.json"

        /** The track a recording is transcribed from (docs/08): `mono`, else `mix`. */
        fun track(tracks: List<Track>): Track = if (Track.MONO in tracks) Track.MONO else Track.MIX
    }
}

/** The plan's `notes.organize` step (docs/08 "Organizing"), run by [Notes]. */
class OrganizeRunner internal constructor(private val organize: suspend (StepContext) -> StepOutcome) : StepRunner {
    override val type: String = TYPE

    override suspend fun run(ctx: StepContext): StepOutcome = organize(ctx)

    companion object {
        const val TYPE: String = "notes.organize"
    }
}

/** `step_run.state_json` of `notes.organize`: the transcript version it is for, its conversations, and those done. */
@Serializable
internal data class OrganizeState(
    val transcript: String,
    val spans: List<NoteConversation>? = null,
    val done: List<ConversationResult?> = emptyList(),
) {
    fun toJson(): JsonObject = recJson.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        fun from(json: JsonObject?): OrganizeState? = json?.let { runCatching { recJson.decodeFromJsonElement(serializer(), it) }.getOrNull() }
    }
}

@Serializable
internal data class ConversationResult(
    val summary: String? = null,
    val items: List<Extracted> = emptyList(),
    val dropped: Int = 0,
)
