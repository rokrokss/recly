package app.recly.windows.record

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import recly.core.model.Part

/**
 * docs/03 "크래시 시 마지막 경계까지는 복구 가능": a part whose audio is on disk but whose row is
 * not. The phone writes an empty `<file>.pending` beside the segment for the same reason; here the
 * marker carries the whole `part_done` the helper sent, because this shell cannot read an `.m4a`
 * back to work out what the marker would otherwise have to omit (duration, hash, offset).
 *
 * That is what lets [RecordingRecovery] register the part rather than quarantine it: the audio is
 * good and everything known about it is right here.
 */
object PartMarker {
    const val SUFFIX = ".pending"

    private val json = Json { ignoreUnknownKeys = true }

    fun path(dir: Path, part: Part): Path = dir / "${part.file}$SUFFIX"

    fun write(fileSystem: FileSystem, dir: Path, part: Part) {
        fileSystem.write(path(dir, part)) { writeUtf8(json.encodeToString(part)) }
    }

    /** Null for a marker this version cannot read — it is left where it is rather than guessed at. */
    fun read(fileSystem: FileSystem, path: Path): Part? = runCatching {
        json.decodeFromString<Part>(fileSystem.read(path) { readUtf8() })
    }.getOrNull()
}
