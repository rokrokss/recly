@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import recly.core.platform.CoreDeps
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptNormalizer

/** What a share sheet can be handed (docs/08 "Exports"). */
enum class ExportFormat { TXT, MD, SRT, VTT, AUDIO }

/**
 * docs/08 "Exports": one file for the share sheet, made on demand in a directory of its own under
 * `{dataDir}/exports/` and named for people — `2026-08-26 Weekly meeting.srt`, the date in the
 * recording's own time zone, or the recording's `{base}` when it has no title. The audio is the
 * playback track (`mix`, else `mono`) joined into one `.m4a` by the shell's lossless concat, after the
 * parts the retention sweep took are fetched back.
 *
 * Exports are a cache: the first export of a process removes every earlier one, and each later export
 * the ones older than [KEEP] — long enough for any share sheet to have taken its copy.
 */
internal class RecordingExport(
    private val deps: CoreDeps,
    private val recordings: RecordingRepository,
    private val transcript: suspend (String) -> Transcript?,
    private val audio: suspend (String) -> RecordingAudio,
) {
    private val mutex = Mutex()
    private var cleaned = false

    suspend fun export(recordingId: String, format: ExportFormat): String? {
        val record = recordings.get(recordingId) ?: return null
        val content = when (format) {
            ExportFormat.TXT -> transcript(recordingId)?.let(TranscriptNormalizer::text)
            ExportFormat.MD -> transcript(recordingId)?.let { TranscriptNormalizer.markdown(it, record.meta) }
            ExportFormat.SRT -> transcript(recordingId)?.let(TranscriptNormalizer::srt)
            ExportFormat.VTT -> transcript(recordingId)?.let(TranscriptNormalizer::vtt)
            ExportFormat.AUDIO -> null
        }
        val parts = if (format == ExportFormat.AUDIO) {
            audio(recordingId).takeIf { it.missing.isEmpty() && it.paths.isNotEmpty() }?.paths ?: return null
        } else {
            if (content == null) return null
            emptyList()
        }
        val dir = mutex.withLock {
            withContext(deps.io) {
                sweep()
                (deps.dataDir / EXPORTS / Random.nextLong().toULong().toString(16)).also { deps.fileSystem.createDirectories(it) }
            }
        }
        val out = dir / fileName(record.meta, format.name.lowercase().let { if (format == ExportFormat.AUDIO) "m4a" else it })
        withContext(deps.io) {
            when {
                content != null -> deps.fileSystem.write(out) { writeUtf8(content) }
                parts.size == 1 -> deps.fileSystem.copy(parts.single(), out)
                else -> deps.audio.concat(parts, out)
            }
        }
        return out.toString()
    }

    /** Everything the first time, then what is older than [KEEP]. */
    private fun sweep() {
        val root = deps.dataDir / EXPORTS
        val cutoff = (deps.clock.now() - KEEP).toEpochMilliseconds()
        deps.fileSystem.listOrNull(root)?.forEach { entry ->
            val modified = deps.fileSystem.metadataOrNull(entry)?.lastModifiedAtMillis ?: 0
            if (!cleaned || modified < cutoff) deps.fileSystem.deleteRecursively(entry, mustExist = false)
        }
        cleaned = true
    }

    companion object {
        private const val EXPORTS = "exports"
        private val KEEP = 1.hours

        /** The longest title a file name keeps. */
        private const val TITLE_CHARS = 100

        /**
         * The longest file name, in UTF-8 bytes, that the file systems a share sheet writes to take (ext4,
         * APFS; NTFS counts 255 UTF-16 units, which this never exceeds). A Korean character is 3 bytes.
         */
        private const val NAME_BYTES = 255

        /**
         * `{yyyy-MM-dd} {title}.{extension}` with what no file system takes out of the title, or
         * `{base}.{extension}` without one. The title is cut to what fits in [NAME_BYTES] beside the date and
         * the extension, never inside a character.
         */
        internal fun fileName(meta: recly.core.model.RecordingMeta, extension: String): String {
            val budget = NAME_BYTES - "yyyy-MM-dd ".length - ".$extension".encodeToByteArray().size
            val title = meta.title?.map { if (it in FORBIDDEN || it.isISOControl()) ' ' else it }?.joinToString("")
                ?.replace(Regex("\\s+"), " ")?.trim()?.trimEnd('.')?.let { prefix(it, TITLE_CHARS, budget) }?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return "${MetaWriter.baseName(meta)}.$extension"
            val zone = runCatching { TimeZone.of(meta.timezone) }.getOrDefault(TimeZone.UTC)
            val day = Instant.parse(meta.startedAt).toLocalDateTime(zone)
            val date = "${day.year.toString().padStart(4, '0')}-${day.month.number.toString().padStart(2, '0')}-${day.day.toString().padStart(2, '0')}"
            return "$date $title.$extension"
        }

        private const val FORBIDDEN = "\\/:*?\"<>|"

        /**
         * The longest prefix of [text] that is at most [chars] long and [bytes] in UTF-8, ending between two
         * code points — a surrogate pair is kept or dropped whole.
         */
        private fun prefix(text: String, chars: Int, bytes: Int): String {
            var used = 0
            var end = 0
            while (end < text.length) {
                val c = text[end]
                val pair = c.isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate()
                val size = when {
                    pair -> 4
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                val next = end + if (pair) 2 else 1
                if (next > chars || used + size > bytes) break
                used += size
                end = next
            }
            return text.substring(0, end)
        }
    }
}
