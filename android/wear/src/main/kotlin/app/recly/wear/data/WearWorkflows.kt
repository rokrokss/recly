package app.recly.wear.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import recly.core.sync.WorkflowSummary

/**
 * The reading half of the phone's `WearJson.workflows` (docs/05 "워치" row). Hand-parsed for the same
 * reason the phone hand-builds it: two fields and a list are not worth a serialization plugin, and
 * the shape is a contract between two modules that never share a class.
 *
 * Nothing here throws. A summary the watch cannot read must not cost the user the ability to
 * record — the picker falls back to "Default", which is a workflow choice the phone accepts anyway.
 */
object WearWorkflows {

    private val json = Json

    fun parse(bytes: ByteArray?): List<WorkflowSummary> = parse(bytes?.decodeToString())

    fun parse(text: String?): List<WorkflowSummary> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val workflows = root["workflows"] as? JsonArray ?: return emptyList()
        return workflows.mapNotNull { summary(it as? JsonObject ?: return@mapNotNull null) }
    }

    /**
     * A field an older phone still sends — `enabled`, `sources` — is read past rather than refused:
     * ADR-016 deleted what they meant, and a watch that dropped the whole workflow over one would
     * have an empty picker until the phone updates.
     */
    private fun summary(entry: JsonObject): WorkflowSummary? {
        val id = entry.string("id") ?: return null
        val name = entry.string("name") ?: return null
        return WorkflowSummary(id = id, name = name)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
