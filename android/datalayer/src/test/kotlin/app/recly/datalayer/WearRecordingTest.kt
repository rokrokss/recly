@file:OptIn(ExperimentalTime::class)

package app.recly.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The watch's recorder state, as the phone reads it: a contract both builds link, and untrusted on arrival. */
class WearRecordingTest {

    private val startedAt = Instant.parse("2026-10-28T04:57:29.123Z")

    @Test
    fun `the path sits beside the transfer's and is not a channel path`() {
        assertEquals("/rec/recording", WearRecording.PATH)
        assertNull(TransferPath.parse(WearRecording.PATH), "the channel grammar must not mistake it for a transfer")
    }

    @Test
    fun `a state round-trips`() {
        assertEquals(
            WearRecording.State("01J9WATCH", startedAt),
            WearRecording.decode(WearRecording.encode("01J9WATCH", startedAt)),
        )
    }

    @Test
    fun `a start before 2020 or more than a day ahead is no state`() {
        val now = Instant.parse("2026-10-28T05:00:00Z")
        assertNull(WearRecording.decode(WearRecording.encode("01J9WATCH", Instant.parse("2019-12-31T23:59:59Z")), now))
        assertNull(WearRecording.decode(WearRecording.encode("01J9WATCH", Instant.parse("2026-10-29T05:00:01Z")), now))
        assertEquals(
            WearRecording.State("01J9WATCH", Instant.parse("2026-10-29T05:00:00Z")),
            WearRecording.decode(WearRecording.encode("01J9WATCH", Instant.parse("2026-10-29T05:00:00Z")), now),
        )
    }

    @Test
    fun `the payload is the two fields, the time in UTC with three fraction digits`() {
        assertEquals(
            """{"recordingId":"01J9WATCH","startedAt":"2026-10-28T04:57:29.123Z"}""",
            WearRecording.encode("01J9WATCH", startedAt).decodeToString(),
        )
        assertEquals(
            """{"recordingId":"01J9WATCH","startedAt":"2026-10-28T04:57:29.000Z"}""",
            WearRecording.encode("01J9WATCH", Instant.parse("2026-10-28T04:57:29Z")).decodeToString(),
        )
    }

    /** A newer watch may say more; what this build reads is still there. */
    @Test
    fun `a field it does not read is left alone`() {
        val payload = """{"recordingId":"01J9WATCH","startedAt":"2026-10-28T04:57:29.123Z","track":"mono"}"""

        assertEquals(WearRecording.State("01J9WATCH", startedAt), WearRecording.decode(payload.encodeToByteArray()))
    }

    @Test
    fun `a payload that is not the shape is no state at all`() {
        listOf(
            "",
            "recording",
            "[]",
            "{}",
            """{"recordingId":"01J9WATCH"}""",
            """{"startedAt":"2026-10-28T04:57:29.123Z"}""",
            """{"recordingId":"../01J9WATCH","startedAt":"2026-10-28T04:57:29.123Z"}""",
            """{"recordingId":"","startedAt":"2026-10-28T04:57:29.123Z"}""",
            """{"recordingId":"${"A".repeat(65)}","startedAt":"2026-10-28T04:57:29.123Z"}""",
            """{"recordingId":42,"startedAt":"2026-10-28T04:57:29.123Z"}""",
            """{"recordingId":"01J9WATCH","startedAt":"yesterday"}""",
            """{"recordingId":"01J9WATCH","startedAt":1761627449123}""",
            """{"recordingId":"01J9WATCH","startedAt":"2026-10-28T04:57:29.123Z","pad":"${"x".repeat(200)}"}""",
        ).forEach { junk ->
            assertNull(WearRecording.decode(junk.encodeToByteArray()), junk)
        }
    }
}
