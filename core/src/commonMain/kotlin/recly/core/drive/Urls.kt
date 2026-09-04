package recly.core.drive

private const val HEX = "0123456789ABCDEF"

/**
 * Percent-encodes a query-string value. Drive's `q` and `fields` carry spaces, quotes, commas and
 * parentheses, and the plans travel to transports we do not own (ADR-015) — so the core hands out
 * URLs that are already valid rather than trusting every transport to escape the same way.
 */
internal fun urlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        val unreserved = code in 0x30..0x39 || code in 0x41..0x5A || code in 0x61..0x7A || char in "-._~"
        if (unreserved) append(char) else append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
    }
}

/**
 * Escapes a string for a Drive `q` term, which quotes with `'`. Only the backslash and the quote
 * itself are special, and the backslash has to go first or it would double the one we just added.
 */
internal fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
