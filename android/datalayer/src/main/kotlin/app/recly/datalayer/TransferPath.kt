package app.recly.datalayer

import recly.core.model.Track

/**
 * The Data Layer channel paths of docs/11 A8, as a grammar rather than string surgery at the call
 * site. Two shapes and nothing else:
 *
 * ```
 * /rec/part/{recordingId}/{part}/{track}/{sha256}/{file}
 * /rec/meta/{recordingId}
 * ```
 *
 * Everything in a path arrives from another device, so [parse] is a whitelist, not a split: a
 * `recordingId` becomes a directory name under `dataDir/recordings` and a `..` in it would be a
 * write anywhere on the phone. Anything that does not match exactly is rejected and the channel
 * closed — there is no partial understanding of a path.
 *
 * Both devices link this class (M3-L2): the watch calls [serialize] to open the channel, the phone
 * calls [parse] on what arrives. That is the point of it living in `:android:datalayer` rather than
 * once on each side — a grammar that two builds could disagree about is not a grammar.
 */
sealed interface TransferPath {

    val recordingId: String

    /** The channel path this describes — the exact string the watch opened the channel with. */
    fun serialize(): String

    /**
     * [file] is the name the watch wrote the part under — `{base}_pNNN_{track}.m4a` (docs/03 "이름
     * 규칙"), where `{base}` comes from `startedAt` and so is not knowable on this side until the
     * meta arrives, last. It rides in the path precisely so this side never has to rename: the part
     * is received under that name and `acceptPart` files it under that name, which is the one the
     * meta will ask for.
     */
    data class PartFile(
        override val recordingId: String,
        val part: Int,
        val track: Track,
        val sha256: String,
        val file: String,
    ) : TransferPath {

        override fun serialize(): String =
            "$PART_PREFIX$recordingId/$part/${track.wire}/$sha256/$file"
    }

    data class Meta(override val recordingId: String) : TransferPath {
        override fun serialize(): String = "$META_PREFIX$recordingId"
    }

    companion object {
        /** Everything this app listens for lives under here — also the manifest's path prefix. */
        const val ROOT: String = "/rec/"
        const val PART_PREFIX: String = "/rec/part/"
        const val META_PREFIX: String = "/rec/meta/"

        /** Null for anything that is not exactly one of the two shapes. */
        fun parse(path: String): TransferPath? = when {
            path.startsWith(PART_PREFIX) -> parsePart(path.removePrefix(PART_PREFIX))
            path.startsWith(META_PREFIX) -> parseMeta(path.removePrefix(META_PREFIX))
            else -> null
        }

        private fun parsePart(rest: String): PartFile? {
            val fields = rest.split('/')
            if (fields.size != 5) return null
            val (id, part, track, sha, file) = fields
            // Not `toIntOrNull()` alone: "+1" and "007" parse, and a part number that does not
            // round-trip is a path this side would not have generated.
            val number = part.toIntOrNull()?.takeIf { it > 0 && it.toString() == part } ?: return null
            // The name becomes a file in the recording directory, so it is checked against the
            // schema's own pattern (spec/recording.meta.schema.json, `parts[].file`) — which admits
            // no separator and no dot beyond the extension — and then against the rest of the path.
            // A name that disagrees with the `{part}`/`{track}` it travelled with describes two
            // different files, and this side has no way to tell which one it is holding.
            val name = PART_FILE.matchEntire(file) ?: return null
            if (name.groupValues[1] != number.toString().padStart(3, '0')) return null
            if (name.groupValues[2] != track) return null
            return PartFile(
                recordingId = id.takeIf(::isId) ?: return null,
                part = number,
                track = Track.entries.firstOrNull { it.wire == track } ?: return null,
                sha256 = sha.takeIf(::isSha256) ?: return null,
                file = file,
            )
        }

        private fun parseMeta(rest: String): Meta? =
            if (isId(rest)) Meta(rest) else null

        /**
         * A ULID is 26 Crockford base32 characters, but the id is the watch's and this is a safety
         * check, not a format check: what matters is that it cannot escape a directory or collide
         * with one. Length-bounded, and no character that means anything to a file system.
         */
        private fun isId(value: String): Boolean =
            value.length in 1..64 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        private fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /** `parts[].file` of spec/recording.meta.schema.json, with `pNNN` and the track captured. */
        private val PART_FILE =
            Regex("""[0-9]{8}T[0-9]{6}Z_(?:watch|phone|desktop)_[0-9A-Z]{8}_p([0-9]{3})_(mono|mic|sys|mix)\.m4a""")
    }
}

/**
 * `Track.wire` is `internal` to :core, so this module spells out the same mapping it uses — the
 * `@SerialName`s of `recly.core.model.Track` (docs/03: `mono`, `mic`, `sys`, `mix`).
 */
val Track.wire: String get() = name.lowercase()
