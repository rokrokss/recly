package app.recly.windows.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintDialogText
import app.recly.windows.ui.component.ButtonTone
import kotlinx.coroutines.launch

@Composable
internal fun WorkflowProtectionDialogs(model: WorkflowsModel, strings: Strings) {
    val scope = rememberCoroutineScope()
    if (model.discardSecret != null) {
        BlueprintDialog(
            title = strings[Str.DISCARD_TITLE], onDismissRequest = { model.answerDiscard(false) },
            actions = {
                BlueprintButton(strings[Str.KEEP_EDITING], { model.answerDiscard(false) })
                BlueprintButton(strings[Str.DISCARD_CHANGES], { model.answerDiscard(true) }, tone = ButtonTone.DANGER)
            },
        ) { BlueprintDialogText(strings[Str.DISCARD_BODY]) }
    }
    model.keyDelete?.let { request ->
        val answer: (Boolean) -> Unit = { confirmed -> scope.launch { model.answerDeleteSecret(confirmed) } }
        BlueprintDialog(
            title = strings[Str.DELETE_KEY_TITLE, request.name], onDismissRequest = { answer(false) },
            actions = {
                BlueprintButton(strings[Str.CANCEL], { answer(false) })
                BlueprintButton(strings[Str.DELETE], { answer(true) }, tone = ButtonTone.DANGER)
            },
        ) {
            BlueprintDialogText(strings[if (request.workflows.isEmpty()) Str.DELETE_KEY_UNUSED else Str.DELETE_KEY_BODY])
            if (request.workflows.isNotEmpty()) BlueprintDialogText(strings[Str.DELETE_KEY_USED_BY, request.workflows.joinToString(", ")])
        }
    }
}
