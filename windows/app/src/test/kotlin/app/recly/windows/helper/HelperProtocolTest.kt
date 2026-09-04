package app.recly.windows.helper

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/14: the lines the Rust helper writes, read by the classes that are the contract for them
 * (`windows/capture-helper/src/protocol.rs` has the same assertions from the other side).
 */
class HelperProtocolTest {

    /** docs/09 화면 원칙 6: the strip's line — the finished tenths of a second, oldest first. */
    @Test
    fun `a level line is the peaks the strip draws`() {
        val event = decode("""{"event":"level","peaks":[0.1,0.5,1.0]}""")

        assertEquals(HelperEvent.Level(listOf(0.1f, 0.5f, 1.0f)), event)
    }

    /** A pump that finished no window sends no line, but an empty one is not a broken one. */
    @Test
    fun `a level line with nothing in it still parses`() {
        assertEquals(HelperEvent.Level(emptyList()), decode("""{"event":"level","peaks":[]}"""))
    }

    private fun decode(line: String): HelperEvent =
        helperJson.decodeFromString(HelperEvent.serializer(), line)
}
