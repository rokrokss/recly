package app.recly.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.ButtonTone

@Composable
internal fun WorkflowProtectionDialogs(
    state: WorkflowsUiState,
    onDiscard: (Boolean) -> Unit,
    onDeleteKey: (Boolean) -> Unit,
) {
    if (state.discardTarget != null) {
        BlueprintDialog(
            title = stringResource(R.string.discard_title),
            onDismissRequest = { onDiscard(false) },
            actions = {
                BlueprintButton(stringResource(R.string.keep_editing), { onDiscard(false) })
                BlueprintButton(stringResource(R.string.discard_changes), { onDiscard(true) }, tone = ButtonTone.DANGER)
            },
        ) { BlueprintDialogText(stringResource(R.string.discard_body)) }
    }
    state.keyDelete?.let { request ->
        BlueprintDialog(
            title = stringResource(R.string.delete_key_title, request.name),
            onDismissRequest = { onDeleteKey(false) },
            actions = {
                BlueprintButton(stringResource(R.string.action_cancel), { onDeleteKey(false) })
                BlueprintButton(stringResource(R.string.action_delete), { onDeleteKey(true) }, tone = ButtonTone.DANGER)
            },
        ) {
            BlueprintDialogText(stringResource(if (request.workflows.isEmpty()) R.string.delete_key_unused else R.string.delete_key_body))
            if (request.workflows.isNotEmpty()) {
                BlueprintDialogText(stringResource(R.string.delete_key_used_by, request.workflows.joinToString(", ")))
            }
        }
    }
}
