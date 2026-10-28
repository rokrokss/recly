@file:OptIn(ExperimentalTime::class)

package app.recly.datalayer

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whether the watch is recording, for the phone (docs/03 "Watch → phone transfer contract"): the first part only
 * arrives a segment in, and the phone shows the recording from its start (docs/11 "While the paired Galaxy Watch
 * records").
 *
 * A `DataClient` data item at [PATH], standing while the watch records and deleted when it stops — "not recording"
 * is the item's absence, never a payload. A data item rather than a message for the reason [WearLanguage] is one:
 * it waits for a phone out of reach, and a phone app that starts later still finds it. The payload is
 * `{"recordingId":"…","startedAt":"…"}`, hand-written, as the acks are.
 *
 * It arrives from another device, so [decode] is a whitelist: a payload that is not this shape is no state at
 * all, and the phone keeps what it shows.
 */
object WearRecording {

    /** Beside the transfer's `/rec/…` paths — and a data item, so the channel grammar never sees it. */
    const val PATH: String = "/rec/recording"

    /** The recording the watch is making, and when it started. */
    data class State(val recordingId: String, val startedAt: Instant)

    private val json = Json

    /** [startedAt] in docs/01's shape — UTC, three fraction digits — as every other timestamp the two devices pass. */
    fun encode(recordingId: String, startedAt: Instant): ByteArray = json.encodeToString(
        buildJsonObject {
            put("recordingId", recordingId)
            put("startedAt", ISO_UTC.format(java.time.Instant.ofEpochMilli(startedAt.toEpochMilliseconds())))
        },
    ).encodeToByteArray()

    /**
     * The state, or null for a payload that is not one. The id is held to the transfer's own check
     * ([TransferPath]), since the phone files parts under the same id; a field this build does not read is
     * left alone, as [AckJson] leaves one.
     */
    fun decode(payload: ByteArray): State? {
        if (payload.size > MAX_BYTES) return null
        val root = runCatching { json.parseToJsonElement(payload.decodeToString()) as? JsonObject }.getOrNull()
            ?: return null
        val recordingId = root.string("recordingId")?.takeIf { TransferPath.isId(it) } ?: return null
        val startedAt = root.string("startedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return State(recordingId, startedAt)
    }

    /** A 64-character id and a timestamp come to about a hundred and twenty; anything far past that is not this. */
    private const val MAX_BYTES = 256

    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
