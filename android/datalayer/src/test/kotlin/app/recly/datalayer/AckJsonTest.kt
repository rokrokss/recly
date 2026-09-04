package app.recly.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.model.Part
import recly.core.model.Track
import recly.core.transfer.Ack

/**
 * The two ends of one payload, in one test. [WearJson] is the phone writing and [AckJson] the watch
 * reading, and the only thing that makes them the same protocol is that this round-trips: a field
 * renamed on one side without the other fails here rather than on a wrist, three hours of audio
 * later.
 */
class AckJsonTest {

    private val path = TransferPath.PartFile(
        recordingId = "01J9WATCH",
        part = 3,
        track = Track.MONO,
        sha256 = "a".repeat(64),
        file = "20260827T090000Z_watch_01J9WATC_p003_mono.m4a",
    )

    @Test
    fun `an ok part ack round-trips`() {
        val ack = AckJson.parse(WearJson.ACK_PART, WearJson.partAck(path, Ack(ok = true)))

        assertEquals(AckMessage.Part("01J9WATCH", PartRef(3, Track.MONO), ok = true), ack)
    }

    /** The nack the watch must not mistake for a resend: `ok` false with a reason and no list. */
    @Test
    fun `a part nack carries its reason`() {
        val ack = AckJson.parse(WearJson.ACK_PART, WearJson.partAck(path, Ack(ok = false, reason = "SHA256_MISMATCH")))

        assertEquals(AckMessage.Part("01J9WATCH", PartRef(3, Track.MONO), ok = false, reason = "SHA256_MISMATCH"), ack)
    }

    @Test
    fun `an ok meta ack round-trips`() {
        val ack = AckJson.parse(WearJson.ACK_META, WearJson.metaAck("01J9WATCH", ok = true))

        assertEquals(AckMessage.Meta("01J9WATCH", ok = true), ack)
    }

    /**
     * `Incomplete`: the phone names the parts it does not have, and those names are what the watch
     * looks for on its own disk (docs/03 알려진 한계).
     */
    @Test
    fun `a meta ack names the parts the phone is missing`() {
        val payload = WearJson.metaAck(
            recordingId = "01J9WATCH",
            ok = false,
            missing = listOf(part(1, Track.MONO), part(4, Track.MIC)),
        )

        assertEquals(
            AckMessage.Meta(
                recordingId = "01J9WATCH",
                ok = false,
                missing = listOf(PartRef(1, Track.MONO), PartRef(4, Track.MIC)),
            ),
            AckJson.parse(WearJson.ACK_META, payload),
        )
    }

    @Test
    fun `a fatal meta nack has a reason and no list`() {
        val payload = WearJson.metaAck("01J9WATCH", ok = false, reason = "RECORDING_ID_MISMATCH")

        assertEquals(
            AckMessage.Meta("01J9WATCH", ok = false, reason = "RECORDING_ID_MISMATCH"),
            AckJson.parse(WearJson.ACK_META, payload),
        )
    }

    /** Not a fault the watch reacts to — the timeout already covers it — so it must not throw. */
    @Test
    fun `nothing readable is null, never an exception`() {
        assertNull(AckJson.parse(WearJson.ACK_PART, "not json"))
        assertNull(AckJson.parse(WearJson.ACK_PART, """{"recordingId":"01J9"}"""))
        assertNull(AckJson.parse(WearJson.ACK_PART, """{"recordingId":"01J9","ok":true}"""))
        assertNull(AckJson.parse("/rec/something-else", WearJson.metaAck("01J9", ok = true)))
    }

    /** A newer phone naming a track this build does not know drops that entry, not the ack. */
    @Test
    fun `an unknown track in missing is dropped, the rest kept`() {
        val payload = """{"recordingId":"01J9","ok":false,"missing":[{"part":1,"track":"quad"},{"part":2,"track":"mic"}]}"""

        assertEquals(
            AckMessage.Meta("01J9", ok = false, missing = listOf(PartRef(2, Track.MIC))),
            AckJson.parse(WearJson.ACK_META, payload),
        )
    }

    private fun part(number: Int, track: Track) = Part(
        part = number,
        track = track,
        file = "20260827T090000Z_watch_01J9WATC_p${number.toString().padStart(3, '0')}_${track.name.lowercase()}.m4a",
        bytes = 1,
        sha256 = "b".repeat(64),
        startOffsetSec = 0.0,
        durationSec = 1.0,
    )
}
