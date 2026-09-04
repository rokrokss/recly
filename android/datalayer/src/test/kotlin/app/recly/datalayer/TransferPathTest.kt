package app.recly.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.model.Track

private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val ID = "01J9ABCDEF0123456789ABCDEF"
private const val BASE = "20260826T010000Z_watch_01J9ABCD"

class TransferPathTest {

    @Test
    fun `a part path round-trips`() {
        val path = TransferPath.PartFile(ID, 3, Track.MONO, SHA, "${BASE}_p003_mono.m4a")

        assertEquals("/rec/part/$ID/3/mono/$SHA/${BASE}_p003_mono.m4a", path.serialize())
        assertEquals(path, TransferPath.parse(path.serialize()))
    }

    @Test
    fun `a meta path round-trips`() {
        val path = TransferPath.Meta(ID)

        assertEquals("/rec/meta/$ID", path.serialize())
        assertEquals(path, TransferPath.parse(path.serialize()))
    }

    @Test
    fun `every track the desktop can send is understood`() {
        Track.entries.forEach { track ->
            val name = "${BASE}_p001_${track.name.lowercase()}.m4a"
            val path = TransferPath.PartFile(ID, 1, track, SHA, name)
            assertEquals(path, TransferPath.parse(path.serialize()), track.name)
        }
    }

    @Test
    fun `a recording id that could escape the recordings directory is rejected`() {
        assertNull(TransferPath.parse("/rec/part/../../etc/1/mono/$SHA/${BASE}_p001_mono.m4a"))
        assertNull(TransferPath.parse("/rec/meta/.."))
        assertNull(TransferPath.parse("/rec/meta/a b"))
        assertNull(TransferPath.parse("/rec/meta/"))
    }

    @Test
    fun `a malformed part path is rejected rather than half understood`() {
        val name = "${BASE}_p001_mono.m4a"
        // Too few and too many fields — including the file name the old grammar left off.
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA"))
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/$name/extra"))
        // Not a track.
        assertNull(TransferPath.parse("/rec/part/$ID/1/stereo/$SHA/$name"))
        // Not a part number: zero, negative, non-numeric, and one that does not round-trip.
        assertNull(TransferPath.parse("/rec/part/$ID/0/mono/$SHA/${BASE}_p000_mono.m4a"))
        assertNull(TransferPath.parse("/rec/part/$ID/-1/mono/$SHA/$name"))
        assertNull(TransferPath.parse("/rec/part/$ID/one/mono/$SHA/$name"))
        assertNull(TransferPath.parse("/rec/part/$ID/001/mono/$SHA/$name"))
        // Not a sha256: wrong length, and a non-hex character.
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/${SHA.drop(1)}/$name"))
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/${SHA.dropLast(1)}z/$name"))
    }

    /**
     * The name and the rest of the path describe the same file or the path is not understood: a
     * `pNNN` that disagrees with `{part}` would file the bytes where the meta will not look.
     */
    @Test
    fun `a file name that disagrees with the part number is rejected`() {
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/${BASE}_p002_mono.m4a"))
        // Zero-padded to three, so a part the name cannot express is not accepted either.
        assertNull(TransferPath.parse("/rec/part/$ID/1234/mono/$SHA/${BASE}_p1234_mono.m4a"))
    }

    @Test
    fun `a file name that disagrees with the track is rejected`() {
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/${BASE}_p001_mic.m4a"))
        assertNull(TransferPath.parse("/rec/part/$ID/1/sys/$SHA/${BASE}_p001_mix.m4a"))
    }

    /** spec/recording.meta.schema.json `parts[].file`: nothing else becomes a file on this phone. */
    @Test
    fun `a file name that is not the schema's is rejected`() {
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/.."))
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/p001_mono.m4a"))
        // A name with the right shape but the wrong extension, source, or timestamp.
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/${BASE}_p001_mono.wav"))
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/20260826T010000Z_pixel_01J9ABCD_p001_mono.m4a"))
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/2026-08-26T010000Z_watch_01J9ABCD_p001_mono.m4a"))
        // Trailing rubbish: the pattern is the whole name, not a prefix of it.
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/${BASE}_p001_mono.m4a.exe"))
        assertNull(TransferPath.parse("/rec/part/$ID/1/mono/$SHA/"))
    }

    @Test
    fun `a path outside the app's own prefix is not ours`() {
        assertNull(TransferPath.parse("/rec/ack"))
        assertNull(TransferPath.parse("/other/part/$ID/1/mono/$SHA/${BASE}_p001_mono.m4a"))
        assertNull(TransferPath.parse(""))
    }
}
