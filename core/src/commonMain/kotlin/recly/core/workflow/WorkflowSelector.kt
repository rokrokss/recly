package recly.core.workflow

import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument

/** ADR-016 selection rules, in order. */
object WorkflowSelector {
    /**
     * 1. the workflow the user picked when the recording started, 2. this device's default,
     * 3. none — the recording stays `NO_WORKFLOW`.
     *
     * Both ids are resolved against the document and nothing else: the definitions are shared and
     * carry no flag about which device runs them (원칙 2), so a [chosen] id that no longer resolves
     * — renamed away, deleted on another device — falls through to [deviceDefault], and a
     * [deviceDefault] that no longer resolves selects nothing rather than guessing. The shells
     * answer that with "pick a default", which is the only honest thing to say about it.
     */
    fun select(doc: WorkflowsDocument, chosen: String?, deviceDefault: String?): Workflow? =
        doc.resolve(chosen) ?: doc.resolve(deviceDefault)

    private fun WorkflowsDocument.resolve(id: String?): Workflow? =
        id?.let { wanted -> workflows.firstOrNull { it.id == wanted } }
}
