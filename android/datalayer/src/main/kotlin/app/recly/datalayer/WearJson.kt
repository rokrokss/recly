package app.recly.datalayer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import recly.core.model.Part
import recly.core.sync.WorkflowSummary
import recly.core.transfer.Ack

/**
 * Everything the phone puts on the wire towards the watch. Three payloads, all tiny, all
 * hand-built: `@Serializable` classes would mean the serialization compiler plugin in this module
 * for three objects that exist only as a message format, and the format is easier to read written
 * out.
 *
 * The watch parses these — [AckJson] is the other half, and it is tested against what this builds —
 * so the field names here are a contract with docs/11 A8, not an implementation detail.
 */
object WearJson {

    /** The message paths the acks go out on. */
    const val ACK_PART: String = "/rec/ack"
    const val ACK_META: String = "/rec/ack-meta"

    /** The data item the workflow summary is published to. */
    const val WORKFLOWS: String = "/rec/workflows"

    private val json = Json

    /**
     * docs/03 "워치 → 폰 전송 계약": `{recordingId, part, track, ok}` is what the watch waits for
     * before it deletes its copy. `reason` is added when it is not ok, so a resend can be told
     * apart from a path the phone will never accept.
     */
    fun partAck(path: TransferPath.PartFile, ack: Ack): String = json.encodeToString(
        buildJsonObject {
            put("recordingId", path.recordingId)
            put("part", path.part)
            put("track", path.track.wire)
            put("ok", ack.ok)
            ack.reason?.let { put("reason", it) }
        },
    )

    /**
     * The meta ack. `ok` false with a `missing` list is the watch's cue to resend exactly those
     * parts and send the meta again; `ok` false with only a `reason` is fatal for that recording.
     */
    fun metaAck(
        recordingId: String,
        ok: Boolean,
        reason: String? = null,
        missing: List<Part> = emptyList(),
    ): String = json.encodeToString(
        buildJsonObject {
            put("recordingId", recordingId)
            put("ok", ok)
            reason?.let { put("reason", it) }
            if (missing.isNotEmpty()) {
                putJsonArray("missing") {
                    missing.forEach { part ->
                        addJsonObject {
                            put("part", part.part)
                            put("track", part.track.wire)
                        }
                    }
                }
            }
        },
    )

    /**
     * docs/05 "워치" row: the watch is told the id and the name — never the steps, because it never
     * runs one and never touches Drive, and (ADR-016) nothing else, because a definition carries no
     * flag about which device runs it. Which workflow this watch starts with is the watch's own
     * local pick, not something the phone publishes.
     */
    fun workflows(summary: List<WorkflowSummary>): String = json.encodeToString(
        buildJsonObject {
            putJsonArray("workflows") {
                summary.forEach { workflow ->
                    addJsonObject {
                        put("id", workflow.id)
                        put("name", workflow.name)
                    }
                }
            }
        },
    )
}
