@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path
import recly.core.model.RecordingMeta
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.model.wire

/** File names and the on-disk `meta.json` (docs/03 "이름 규칙"). */
object MetaWriter {
    /** `{yyyyMMdd}T{HHmmss}Z_{source}_{first 8 of recordingId}` — always UTC, never a user string. */
    fun baseName(meta: RecordingMeta): String {
        val t = Instant.parse(meta.startedAt).toLocalDateTime(TimeZone.UTC)
        val date = "${pad(t.year, 4)}${pad(t.month.number)}${pad(t.day)}"
        val time = "${pad(t.hour)}${pad(t.minute)}${pad(t.second)}"
        return "${date}T${time}Z_${meta.source.wire}_${meta.recordingId.take(8)}"
    }

    fun partFileName(base: String, part: Int, track: Track): String =
        "${base}_p${pad(part, 3)}_${track.wire}.m4a"

    fun metaFileName(base: String): String = "$base.meta.json"

    /**
     * Rewritten at every segment boundary, so a crash still leaves a readable meta: write a temp
     * file and rename it, never truncate the real one.
     */
    fun write(fs: FileSystem, dir: Path, meta: RecordingMeta) {
        fs.createDirectories(dir)
        val target = dir / metaFileName(baseName(meta))
        // A unique suffix per write: two processes (or a retry after a crash) must not land on
        // the same temp file and hand `atomicMove` a half-written one.
        val temp = dir / "${target.name}.${Random.nextInt(Int.MAX_VALUE)}.tmp"
        fs.write(temp) { writeUtf8(recJson.encodeToString(meta)) }
        fs.atomicMove(temp, target)
    }

    private fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
}
