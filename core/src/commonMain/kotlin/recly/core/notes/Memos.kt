@file:OptIn(ExperimentalTime::class)

package recly.core.notes

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository

/** docs/08 "Memo": `{base}.memo.v1.json` beside the parts (`spec/memo.schema.json`). */
@Serializable
internal data class MemoFile(
    val schema: Int = SCHEMA,
    val recordingId: String,
    /** As the user typed it, at most [Memos.MAX] characters; empty once they emptied it. */
    val text: String,
    /** When the user last changed it: the newer copy wins, and the folder's `memoAt` is this. */
    val updatedAt: String,
) {
    companion object {
        const val SCHEMA = 1

        fun name(base: String): String = "$base.memo.v1.json"
    }
}

/** docs/08 "Memo": the copy in the recording's Drive or iCloud folder — the same JSON, with the folder's `memoAt`. */
internal object MemoFolder {
    const val STAMP = "memoAt"
    const val MIME = "application/json"

    fun name(base: String): String = "$base.memo.json"

    fun decode(json: String): MemoFile? =
        runCatching { recJson.decodeFromString(MemoFile.serializer(), json) }.getOrNull()?.takeIf { it.schema == MemoFile.SCHEMA }
}

/**
 * docs/08 "Memo": the user's own text about a recording — an agenda, who is there, what to keep track of — typed while it
 * records or any time after. Kept beside the parts as typed, carried to the recording's Drive or iCloud folder a few
 * seconds after the last change ([published]), and sent with its transcript wherever the transcript goes to ChatGPT
 * ([forAi]). Nothing rewrites it.
 */
class Memos internal constructor(
    private val deps: CoreDeps,
    private val recordings: RecordingRepository,
    /** A memo changed here and has been left alone for [PUBLISH_DELAY_SEC]: it is to go up to the recording's folder. */
    private val published: suspend (String) -> Unit = {},
    /** The recording's folder copy, for a recording opened here before a pull brought it ([MemoFolder]). */
    private val fetch: suspend (String) -> String? = { null },
) {
    /** How long a change waits for the next before it goes up (tests shorten it). */
    internal var publishDelayMillis: Long = PUBLISH_DELAY_SEC * 1000L

    /** The waits before a memo goes up; they outlive the screen that typed it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val waits = mutableMapOf<String, Job>()
    private val waitsLock = Mutex()

    private val changes = MutableStateFlow(0L)

    /** Recordings whose folder was already asked for a memo this run, found or not. */
    private val fetched = mutableSetOf<String>()
    private val fetchLock = Mutex()

    /** The recording's memo as it changes — here or from its folder; empty when it has none. */
    fun observe(recordingId: String): Flow<String> = changes
        .map {
            // Never fails: a memo that cannot be read now is none.
            try {
                get(recordingId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
        .distinctUntilChanged()

    /** [observe]'s value now. */
    @Throws(Throwable::class)
    suspend fun get(recordingId: String): String {
        val record = recordings.get(recordingId) ?: return ""
        return (read(record) ?: fetched(record))?.text.orEmpty()
    }

    /**
     * Keeps [text] as the recording's memo, as typed — line breaks and spaces included — cut to [MAX] characters. Any
     * time: while recording, while it is processed, for another device's recording. Emptying it keeps an empty text, so
     * the change reaches the other devices. False when nothing changed, or there is no such recording.
     */
    @Throws(Throwable::class)
    suspend fun set(recordingId: String, text: String): Boolean {
        val kept = cut(text)
        val written = recordings.memoWrite {
            val record = recordings.get(recordingId) ?: return@memoWrite false
            if (read(record)?.text.orEmpty() == kept) return@memoWrite false
            val memo = MemoFile(recordingId = recordingId, text = kept, updatedAt = deps.clock.now().isoUtc())
            if (!recordings.writeFile(recordingId, MemoFile.name(base(record)), recJson.encodeToString(MemoFile.serializer(), memo))) {
                return@memoWrite false
            }
            recordings.memoPending(recordingId)
            true
        }
        if (!written) return false
        deps.logger.log(Logger.Level.INFO, "memo.saved", mapOf("recordingId" to recordingId, "chars" to kept.length))
        changes.value++
        publishLater(recordingId)
        return true
    }

    /** What a request to ChatGPT carries of the memo: trimmed, empty when blank (docs/08 "Memo" — "What reads it"). */
    internal suspend fun forAi(recordingId: String): String = try {
        get(recordingId).trim()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    /** A pull wrote a newer memo from a folder: whoever shows it reads it again. */
    internal fun changed() {
        changes.value++
    }

    /** Tests: let the waits go now. */
    internal suspend fun awaitPublishes() {
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    /** One wait per recording: a change while it waits starts it over, so typing does not upload each keystroke. */
    private suspend fun publishLater(recordingId: String) {
        waitsLock.withLock {
            waits.remove(recordingId)?.cancel()
            waits[recordingId] = scope.launch {
                delay(publishDelayMillis)
                waitsLock.withLock { waits.remove(recordingId) }
                published(recordingId)
            }
        }
    }

    /** Another device's memo of a recording that has none here: its folder is asked once a run, and what it has is kept. */
    private suspend fun fetched(record: RecordingRecord): MemoFile? {
        if (!fetchLock.withLock { fetched.add(record.id) }) return null
        val json = try {
            fetch(record.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val memo = MemoFolder.decode(json)?.takeIf { it.recordingId == record.id } ?: return null
        recordings.memoWrite {
            // Typed here meanwhile: that one is newer, and goes up.
            if (read(record) == null) recordings.writeFile(record.id, MemoFile.name(base(record)), json)
        }
        return read(record)
    }

    private suspend fun read(record: RecordingRecord): MemoFile? =
        recordings.readFile(record.id, MemoFile.name(base(record)))?.let(MemoFolder::decode)?.takeIf { it.recordingId == record.id }

    private fun base(record: RecordingRecord): String = MetaWriter.baseName(record.meta)

    companion object {
        /** The longest memo, in characters. */
        const val MAX: Int = 2000

        /** How long after the last change a memo goes up to the recording's folder. */
        const val PUBLISH_DELAY_SEC: Int = 5

        /**
         * [MAX] characters at most, never half of a character outside the Basic Multilingual Plane — what [set] keeps, and
         * what a shell's field takes.
         */
        fun cut(text: String): String {
            if (text.length <= MAX) return text
            return if (text[MAX - 1].isHighSurrogate()) text.take(MAX - 1) else text.take(MAX)
        }
    }
}
