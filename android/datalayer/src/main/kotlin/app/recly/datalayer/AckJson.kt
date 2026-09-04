package app.recly.datalayer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import recly.core.model.Track

/** One part of one recording — what an ack and a `missing` entry both name. */
data class PartRef(val part: Int, val track: Track)

/**
 * What the phone sends back on [WearJson.ACK_PART] and [WearJson.ACK_META] (docs/03 "워치 → 폰 전송
 * 계약"). The watch acts on nothing else: an ack is its licence to delete its only copy of the
 * audio, so every field it decides on is read from here and none of it is inferred.
 */
sealed interface AckMessage {

    val recordingId: String

    val ok: Boolean

    /** Present when [ok] is false: why, so a resend can be told from a fatal refusal. */
    val reason: String?

    data class Part(
        override val recordingId: String,
        val ref: PartRef,
        override val ok: Boolean,
        override val reason: String? = null,
    ) : AckMessage

    /**
     * [missing] is the phone asking for exactly those parts again. Empty with `ok` false and a
     * [reason] is fatal for this recording; empty with `ok` true is the end of the transfer.
     */
    data class Meta(
        override val recordingId: String,
        override val ok: Boolean,
        override val reason: String? = null,
        val missing: List<PartRef> = emptyList(),
    ) : AckMessage
}

/**
 * The reading half of [WearJson.partAck] and [WearJson.metaAck], as `WearWorkflows` is of
 * `WearJson.workflows`: hand-parsed for the same reason the phone hand-builds it, and pinned to the
 * builder by `AckJsonTest` rather than by two people reading the same doc.
 *
 * Nothing here throws. An ack the watch cannot read is an ack it did not get, and the transfer's
 * own five-minute timeout is already the answer to that (docs/11 W4) — where guessing at a
 * half-understood `ok` would cost the user the recording.
 */
object AckJson {

    private val json = Json

    /** Null for a path this is not, or a body that is not the shape that path promises. */
    fun parse(path: String, payload: ByteArray): AckMessage? = parse(path, payload.decodeToString())

    fun parse(path: String, text: String): AckMessage? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val recordingId = root.string("recordingId") ?: return null
        val ok = root.boolean("ok") ?: return null
        val reason = root.string("reason")
        return when (path) {
            WearJson.ACK_PART -> AckMessage.Part(
                recordingId = recordingId,
                ref = root.partRef() ?: return null,
                ok = ok,
                reason = reason,
            )

            WearJson.ACK_META -> AckMessage.Meta(
                recordingId = recordingId,
                ok = ok,
                reason = reason,
                // A `missing` entry this build cannot read is dropped, not the whole ack: the
                // remaining entries still name parts the phone wants, and dropping the ack would
                // strand the recording until the timeout instead of resending what was understood.
                missing = (root["missing"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    (entry as? JsonObject)?.partRef()
                },
            )

            else -> null
        }
    }

    private fun JsonObject.partRef(): PartRef? {
        val part = (this["part"] as? JsonPrimitive)?.intOrNull ?: return null
        val wire = string("track") ?: return null
        val track = Track.entries.firstOrNull { it.wire == wire } ?: return null
        return PartRef(part, track)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
