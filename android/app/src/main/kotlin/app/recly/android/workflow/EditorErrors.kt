package app.recly.android.workflow

import app.recly.android.R
import app.recly.android.core.UiMessage
import recly.core.workflow.WorkflowParser

/** Everything the parser complained about, sorted into the fields the editor can point at. */
data class EditorErrors(
    val name: UiMessage? = null,
    val minDuration: UiMessage? = null,
    /** By step id. */
    val steps: Map<String, StepErrors> = emptyMap(),
    /** What no field owns — the document's own fields, another workflow, an unknown complaint. */
    val banner: List<UiMessage> = emptyList(),
) {
    val isEmpty: Boolean
        get() = name == null && minDuration == null && steps.isEmpty() && banner.isEmpty()

    companion object {
        /**
         * `SaveResult.Invalid` carries the parser's own English sentences — docs/02 is the one place
         * the rules live and they are not restated here. This reads the sentence structure
         * (`workflow <id>: step '<step>' …`) to decide which field is at fault; anything it does not
         * recognise goes to the banner verbatim rather than being dropped.
         *
         * A recognised rule becomes one of this app's own strings, so the editor speaks the user's
         * language (docs/07); an unrecognised one stays in the parser's words.
         */
        fun of(errors: List<String>, workflowId: String): EditorErrors {
            var name: UiMessage? = null
            var minDuration: UiMessage? = null
            val steps = mutableMapOf<String, StepErrors>()
            val banner = mutableListOf<UiMessage>()
            val prefix = "workflow $workflowId: "

            errors.forEach { error ->
                if (!error.startsWith(prefix)) {
                    // Another workflow, or a document-level field: nothing on this screen to blame.
                    banner += UiMessage.Text(error)
                    return@forEach
                }
                val detail = error.removePrefix(prefix)
                when {
                    detail.startsWith("name must be") -> name = UiMessage.Res(R.string.error_name)
                    detail.startsWith("minDurationSec") ->
                        minDuration = UiMessage.Res(R.string.error_min_duration)

                    detail.startsWith("steps must be") -> banner += UiMessage.Res(R.string.error_steps_count)
                    else -> {
                        val stepId = STEP_ID.find(detail)?.groupValues?.get(1)
                        if (stepId == null) banner += UiMessage.Text(detail)
                        else steps[stepId] = steps.getOrElse(stepId) { StepErrors() }.plus(detail)
                    }
                }
            }
            return EditorErrors(name, minDuration, steps, banner)
        }

        /** Matches both `step 'up' …` and `step id 'up' …`. */
        private val STEP_ID = Regex("step (?:id )?'([^']+)'")
    }
}

data class StepErrors(
    val folder: UiMessage? = null,
    val url: UiMessage? = null,
    val secretRef: UiMessage? = null,
    val maxAttempts: UiMessage? = null,
    val initialDelay: UiMessage? = null,
    val maxDelay: UiMessage? = null,
    /**
     * The docs/08 order constraint this step breaks, as the parser's own token — the screen turns
     * it into a sentence, because unlike the fields above this one is shown before any save.
     */
    val order: String? = null,
    val other: List<UiMessage> = emptyList(),
) {
    internal fun plus(detail: String): StepErrors = when {
        detail.contains(WorkflowParser.TRANSCRIBE_NEEDS_UPLOAD) ->
            copy(order = WorkflowParser.TRANSCRIBE_NEEDS_UPLOAD)

        detail.contains("folder must be") -> copy(folder = UiMessage.Res(R.string.error_folder))
        detail.contains("unknown template variable") ->
            copy(folder = UiMessage.Res(R.string.error_folder_variable, listOf(templateVar(detail))))

        detail.contains("url must be") -> copy(url = UiMessage.Res(R.string.error_url))
        detail.contains("secretRef") -> copy(secretRef = UiMessage.Res(R.string.error_secret_ref))
        detail.contains("retry.maxAttempts") -> copy(maxAttempts = UiMessage.Res(R.string.error_max_attempts))
        detail.contains("retry.initialDelaySec") -> copy(initialDelay = UiMessage.Res(R.string.error_initial_delay))
        detail.contains("retry.maxDelaySec") -> copy(maxDelay = UiMessage.Res(R.string.error_max_delay))
        else -> copy(other = other + UiMessage.Text(detail))
    }

    val isEmpty: Boolean
        get() = this == StepErrors()
}

/** The variable the parser quoted, so the message can name it. */
private fun templateVar(detail: String): String =
    Regex("'(\\{\\{[^']*}})'").find(detail)?.groupValues?.get(1) ?: UNNAMED_VARIABLE

private const val UNNAMED_VARIABLE = "{{?}}"
