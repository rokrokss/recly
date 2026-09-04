@file:OptIn(ExperimentalTime::class)

package recly.core.ids

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val PATTERN = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")

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
}
