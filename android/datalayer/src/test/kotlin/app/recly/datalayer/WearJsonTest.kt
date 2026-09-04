package app.recly.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.model.Part
import recly.core.model.Track
import recly.core.sync.WorkflowSummary
import recly.core.transfer.Ack

private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val ID = "01J9ABCDEF0123456789ABCDEF"

/**
 * The watch (M3) parses every one of these, so the shapes are a contract and not a detail. Exact
 * strings on purpose: a renamed field is the kind of change that only shows up on a real watch.
 */
class WearJsonTest {

    private fun part(number: Int, track: Track = Track.MONO) = Part(
        part = number,
        track = track,
        file = "20260826T010000Z_watch_01J9ABCD_p00${number}_${track.name.lowercase()}.m4a",
        bytes = 1024,
        sha256 = SHA,
        startOffsetSec = 0.0,
        durationSec = 900.0,
    )

    @Test
    fun `an accepted part is acked with what it was`() {
        val json = WearJson.partAck(TransferPath.PartFile(ID, 2, Track.MONO, SHA, part(2).file), Ack(ok = true))

        assertEquals("""{"recordingId":"$ID","part":2,"track":"mono","ok":true}""", json)
    }

    @Test
    fun `a rejected part carries the reason so the watch can tell a resend from a dead end`() {
        val json = WearJson.partAck(
            TransferPath.PartFile(ID, 2, Track.SYS, SHA, part(2, Track.SYS).file),
            Ack(ok = false, reason = "SHA256_MISMATCH"),
        )

        assertEquals(
            """{"recordingId":"$ID","part":2,"track":"sys","ok":false,"reason":"SHA256_MISMATCH"}""",
            json,
        )
    }

    @Test
    fun `a complete meta acks ok and nothing else`() {
        assertEquals("""{"recordingId":"$ID","ok":true}""", WearJson.metaAck(ID, ok = true))
    }

    @Test
    fun `an incomplete meta names exactly the parts to resend`() {
        val json = WearJson.metaAck(ID, ok = false, missing = listOf(part(1), part(3, Track.MIX)))

        assertEquals(
            """{"recordingId":"$ID","ok":false,"missing":[{"part":1,"track":"mono"},{"part":3,"track":"mix"}]}""",
            json,
        )
    }

    @Test
    fun `an unreadable meta acks the reason and no missing list`() {
        val json = WearJson.metaAck(ID, ok = false, reason = "malformed meta: boom")

        assertEquals("""{"recordingId":"$ID","ok":false,"reason":"malformed meta: boom"}""", json)
    }

    /** docs/05 "워치" row: id and name — and never the steps, nor anything ADR-016 deleted. */
    @Test
    fun `the workflow summary is the two fields the watch is allowed to see`() {
        val json = WearJson.workflows(listOf(WorkflowSummary("a", "회의"), WorkflowSummary("b", "메모")))

        assertEquals(
            """{"workflows":[{"id":"a","name":"회의"},{"id":"b","name":"메모"}]}""",
            json,
        )
    }

    @Test
    fun `no workflows is an empty list, not an absent key`() {
        assertEquals("""{"workflows":[]}""", WearJson.workflows(emptyList()))
    }
}
