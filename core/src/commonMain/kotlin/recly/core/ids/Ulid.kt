@file:OptIn(ExperimentalTime::class)

package recly.core.ids

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val PATTERN = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")

    /** The first 10 characters are the millisecond clock; the other 16 are randomness. */
    private const val TIME_CHARS = 10

    fun generate(clock: Clock): String {
        val millis = clock.now().toEpochMilliseconds()
        val out = StringBuilder(26)
        for (shift in 45 downTo 0 step 5) {
            out.append(ALPHABET[((millis shr shift) and 0x1F).toInt()])
        }
        repeat(16) { out.append(ALPHABET[Random.nextInt(ALPHABET.length)]) }
        return out.toString()
    }

    fun isValid(s: String): Boolean = PATTERN.matches(s)

    /**
     * When the id was made, read back out of it (docs/01 "식별자·시간"): [generate] writes the
     * millisecond clock into the first [TIME_CHARS] characters, most significant first, so an id is
     * its own timestamp. What a recording that has not sent its `meta.json` yet is dated by — the
     * watch transfer in flight and the folder another device is still uploading into (docs/03).
     *
     * Null when [s] is not a ULID: a time decoded from something else is not a time.
     */
    fun timestamp(s: String): Instant? {
        if (!isValid(s)) return null
        var millis = 0L
        for (index in 0 until TIME_CHARS) {
            millis = (millis shl 5) or ALPHABET.indexOf(s[index]).toLong()
        }
        return Instant.fromEpochMilliseconds(millis)
    }
}
