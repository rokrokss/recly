package recly.core.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class RecordingMetaTest {
    private val example = File("../spec/examples/recording.meta.json").readText()

    @Test
    fun parsesExample() {
        val meta = recJson.decodeFromString<RecordingMeta>(example)
        assertEquals("01J9ABCDEF0123456789ABCDEF", meta.recordingId)
        assertEquals(Source.DESKTOP, meta.source)
        assertEquals(Platform.MACOS, meta.platform)
        assertEquals(RecordingStatus.FINALIZED, meta.status)
        assertEquals(Codec.AAC_LC, meta.audio.codec)
        assertEquals(Container.M4A, meta.audio.container)
        assertEquals(3, meta.parts.size)
        assertEquals(listOf(Track.MIC, Track.SYS, Track.MIX), meta.tracks)
        assertEquals(1, meta.gaps.size)
        assertEquals("us.zoom.xos", meta.context?.app)
    }

    /**
     * `context.calendar` was removed before the release, and the meta files written while it existed
     * are still on disk. A leftover block is read past, not refused — the recording it belongs to
     * still has to upload.
     */
    @Test
    fun parsesMetaWrittenWithTheRemovedCalendarBlock() {
        val withCalendar = example.replaceFirst(
            "\"app\": \"us.zoom.xos\",",
            """
            "app": "us.zoom.xos",
            "calendar": { "title": "주간 회의", "startsAt": "2026-08-26T01:00:00Z",
                          "endsAt": "2026-08-26T02:00:00Z", "attendees": ["a@example.com"] },
            """.trimIndent(),
        )

        val meta = recJson.decodeFromString<RecordingMeta>(withCalendar)

        assertEquals("us.zoom.xos", meta.context?.app)
        assertEquals(3, meta.context?.participants)
    }

    @Test
    fun rejectsUnknownCodec() {
        assertFailsWith<SerializationException> {
            recJson.decodeFromString<RecordingMeta>(example.replaceFirst("\"aac-lc\"", "\"opus\""))
        }
    }

    @Test
    fun rejectsMissingParts() {
        val withoutParts = Json.parseToJsonElement(example).jsonObject
            .filterKeys { it != "parts" }
            .let { JsonObject(it) }
            .toString()
        assertFailsWith<SerializationException> { recJson.decodeFromString<RecordingMeta>(withoutParts) }
    }

    @Test
    fun rejectsUnknownContainer() {
        assertFailsWith<SerializationException> {
            recJson.decodeFromString<RecordingMeta>(example.replaceFirst("\"m4a\"", "\"ogg\""))
        }
    }
}
