package recly.core.transcribe

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okio.FileSystem
import okio.Path
import recly.core.model.recJson

/** docs/08 "Versions": what made a version of a recording's transcript. */
enum class TranscriptVersionKind {
    /** A transcription wrote it — the first one or a transcription again — with the names filled in at its publication. */
    TRANSCRIBED,

    /** The user's edit, a rename of a speaker, or names filled in from the detail. */
    EDITED,

    /** A change asked for in Ask (docs/08 "Ask" — "Changes"). */
    CORRECTED,

    /** An earlier version brought back. */
    RESTORED,

    /** Another device's change, read from the recording's folder. */
    RECEIVED,
}

/** One version of a recording's transcript this device kept, newest first in a list; [current] is the one shown. */
data class TranscriptVersion(
    val id: String,
    val kind: TranscriptVersionKind,
    /** When it was made — its `editedAt`, else its `createdAt` (ISO 8601, UTC). */
    val at: String,
    val current: Boolean,
)

/**
 * docs/08 "Versions": the transcripts a recording had on this device, kept beside it in `versions/` — an index and
 * one file per version, the transcript as it was. Every writer of `{base}.transcript.json` calls [keep] under
 * [resultFileMutex] right before it writes, so the copy it replaces is kept too. Only this device's: nothing here
 * goes to the recording's folder.
 */
internal object TranscriptVersions {
    const val DIR = "versions"
    private const val INDEX = "index.json"

    /** How many versions a recording keeps, the one shown included. */
    const val MAX = 20

    @Serializable
    private data class Index(val versions: List<Entry> = emptyList())

    @Serializable
    private data class Entry(val id: String, val kind: Kind, val at: String)

    @Serializable
    private enum class Kind {
        @SerialName("transcribed") TRANSCRIBED,
        @SerialName("edited") EDITED,
        @SerialName("corrected") CORRECTED,
        @SerialName("restored") RESTORED,
        @SerialName("received") RECEIVED,
    }

    /**
     * Keeps [transcript], which is about to replace `{base}.transcript.json` in [dir] — after the copy it replaces, when
     * that one is not kept yet (written before versions were kept, or by a writer that keeps none). Nothing when it is
     * the version kept last; a transcription's own later write (its names filled in at the publication) takes that
     * version's place. Call under [resultFileMutex]. Never throws: a version that cannot be kept never stops the write.
     */
    fun keep(fs: FileSystem, dir: Path, base: String, transcript: Transcript, kind: TranscriptVersionKind) {
        runCatching {
            var index = catchUp(fs, dir, base)
            val newest = index.versions.lastOrNull()
            val kept = newest?.let { read(fs, dir, it.id) }
            if (kept == transcript) return
            val wire = Kind.valueOf(kind.name)
            val replaces = newest != null && kept != null && wire == Kind.TRANSCRIBED && newest.kind == Kind.TRANSCRIBED &&
                kept.createdAt == transcript.createdAt && kept.editedAt == null && transcript.editedAt == null
            index = if (replaces) {
                write(fs, dir, newest!!.id, transcript)
                Index(index.versions.dropLast(1) + newest.copy(at = at(transcript)))
            } else {
                append(fs, dir, index, transcript, wire)
            }
            save(fs, dir, prune(fs, dir, index))
        }
    }

    /** The versions kept, newest first — the copy shown first, kept now if it was not. Empty when there is none. */
    fun list(fs: FileSystem, dir: Path, base: String): List<TranscriptVersion> = runCatching {
        val index = catchUp(fs, dir, base)
        index.versions.asReversed().mapIndexed { position, entry ->
            TranscriptVersion(entry.id, TranscriptVersionKind.valueOf(entry.kind.name), entry.at, current = position == 0)
        }
    }.getOrDefault(emptyList())

    /** Version [id]'s transcript, or null when it is not kept. */
    fun read(fs: FileSystem, dir: Path, id: String): Transcript? {
        if (!id.all { it.isDigit() } || id.isEmpty()) return null
        val path = dir / DIR / "$id.json"
        if (!fs.exists(path)) return null
        return runCatching { recJson.decodeFromString<Transcript>(fs.read(path) { readUtf8() }) }.getOrNull()
    }

    /** The version kept before the newest — what an undo of the newest brings back — or null. */
    fun previous(fs: FileSystem, dir: Path): String? = index(fs, dir).versions.dropLast(1).lastOrNull()?.id

    /** The index, with the copy shown now kept as the newest version when it is not already. */
    private fun catchUp(fs: FileSystem, dir: Path, base: String): Index {
        val index = index(fs, dir)
        val path = dir / TranscribeRunner.jsonFileName(base)
        if (!fs.exists(path)) return index
        val shown = runCatching { recJson.decodeFromString<Transcript>(fs.read(path) { readUtf8() }) }.getOrNull() ?: return index
        if (index.versions.lastOrNull()?.let { read(fs, dir, it.id) } == shown) return index
        val kind = if (shown.editedAt != null) Kind.EDITED else Kind.TRANSCRIBED
        return prune(fs, dir, append(fs, dir, index, shown, kind)).also { save(fs, dir, it) }
    }

    private fun append(fs: FileSystem, dir: Path, index: Index, transcript: Transcript, kind: Kind): Index {
        val id = ((index.versions.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0L) + 1).toString()
        write(fs, dir, id, transcript)
        return Index(index.versions + Entry(id, kind, at(transcript)))
    }

    private fun prune(fs: FileSystem, dir: Path, index: Index): Index {
        if (index.versions.size <= MAX) return index
        val (gone, kept) = index.versions.withIndex().partition { it.index < index.versions.size - MAX }
        gone.forEach { fs.delete(dir / DIR / "${it.value.id}.json", mustExist = false) }
        return Index(kept.map { it.value })
    }

    private fun at(transcript: Transcript): String = transcript.editedAt ?: transcript.createdAt

    private fun index(fs: FileSystem, dir: Path): Index {
        val path = dir / DIR / INDEX
        if (!fs.exists(path)) return Index()
        return runCatching { recJson.decodeFromString<Index>(fs.read(path) { readUtf8() }) }.getOrDefault(Index())
    }

    private fun save(fs: FileSystem, dir: Path, index: Index) = atomic(fs, dir / DIR / INDEX, recJson.encodeToString(index))

    private fun write(fs: FileSystem, dir: Path, id: String, transcript: Transcript) =
        atomic(fs, dir / DIR / "$id.json", recJson.encodeToString(transcript))

    private fun atomic(fs: FileSystem, path: Path, text: String) {
        fs.createDirectories(path.parent!!)
        val temp = path.parent!! / "${path.name}.tmp"
        fs.write(temp) { writeUtf8(text) }
        fs.atomicMove(temp, path)
    }
}
