package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BadgeTone
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LedgerStatus
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.StatusBadge
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/**
 * docs/11 A6, deliverable 1: the definitions on this phone, and — ADR-016 — the one of them it
 * runs, which is a local choice and the only thing the row can set. Editing anything opens
 * [WorkflowEditorScreen]; this screen only ever marks the one in use and deletes.
 */
@Composable
fun WorkflowsScreen(
    state: WorkflowsUiState,
    onAdd: () -> Unit,
    onOpen: (WorkflowItem) -> Unit,
    onSetDefault: (WorkflowItem) -> Unit,
    onConfirmDelete: (WorkflowItem?) -> Unit,
    onDelete: (WorkflowItem) -> Unit,
    /**
     * The secrets screen, with the name already in the form — the key a row is missing, or null
     * from the header, where there is no one name to fill in.
     */
    onSecrets: (String?) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint

    state.confirmDelete?.let { item ->
        BlueprintDialog(
            title = stringResource(R.string.workflows_delete_title, item.name),
            onDismissRequest = { onConfirmDelete(null) },
            actions = {
                BlueprintButton(
                    label = stringResource(R.string.action_cancel),
                    onClick = { onConfirmDelete(null) },
                    tone = ButtonTone.QUIET,
                )
                BlueprintButton(
                    label = stringResource(R.string.action_delete),
                    onClick = { onDelete(item) },
                    tone = ButtonTone.DANGER,
                )
            },
        ) {
            BlueprintDialogText(stringResource(R.string.workflows_delete_body))
            // ADR-016: deleting it is allowed, and it costs this phone the workflow it runs.
            if (item.isDeviceDefault) {
                BlueprintDialogText(
                    stringResource(R.string.workflow_delete_in_use),
                    tone = DialogTone.DANGER,
                )
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.tab_workflows),
            meta = state.items.size.toString(),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    BlueprintButton(
                        label = stringResource(R.string.workflows_secrets),
                        onClick = { onSecrets(null) },
                        tone = ButtonTone.QUIET,
                    )
                    BlueprintButton(
                        label = stringResource(R.string.workflows_add),
                        onClick = onAdd,
                        tone = ButtonTone.PRIMARY,
                    )
                }
            },
        )
        HairLine()

        state.message?.let { message ->
            Text(
                message.text(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissMessage)
                    .padding(horizontal = Space.m, vertical = Space.s),
                style = MaterialTheme.typography.bodySmall,
                color = palette.danger,
            )
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.items, key = { it.id }) { item ->
                WorkflowRow(
                    item = item,
                    onOpen = { onOpen(item) },
                    onSetDefault = { onSetDefault(item) },
                    onDelete = { onConfirmDelete(item) },
                    onAddSecret = { onSecrets(item.missingSecrets.firstOrNull()) },
                )
            }
            if (state.items.isEmpty() && !state.loading) {
                item {
                    Text(
                        stringResource(R.string.workflows_empty),
                        modifier = Modifier.padding(Space.m),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * ADR-016: name, steps, and the one thing a row decides — whether this phone runs it. The mark and
 * the button are the same control seen from its two states, so exactly one of them shows.
 */
@Composable
private fun WorkflowRow(
    item: WorkflowItem,
    onOpen: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onAddSecret: () -> Unit,
) {
    val palette = blueprint
    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // docs/09 "접근성": the row opens the editor, and "row, button" says nothing about
                // which of the three things on it a tap would do.
                .clickable(onClickLabel = stringResource(R.string.workflow_open), onClick = onOpen)
                .padding(horizontal = Space.m, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        // A long name ends rather than pushing the in-use badge off the row.
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isDeviceDefault) {
                        StatusBadge(
                            LedgerStatus(stringResource(R.string.workflow_in_use), BadgeTone.ACCENT),
                        )
                    }
                }
                Text(
                    if (item.steps.isEmpty()) {
                        stringResource(R.string.workflow_no_steps)
                    } else {
                        item.steps.map { stringResource(it) }.joinToString(" → ")
                    },
                    style = mono.small,
                    color = palette.textMuted,
                    maxLines = 1,
                )
                if (item.missingSecrets.isNotEmpty()) {
                    Text(
                        stringResource(R.string.workflow_missing_secrets, item.missingSecrets.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                    )
                    // docs/05 "새 기기": the definition arrived and the key did not, so the line
                    // that says so is also the way to fix it — the form opens with the missing name
                    // already in it (the iPhone's own row shortcut). Under the sentence rather than
                    // beside `Use` and `Delete`: a fourth button across would leave the name a
                    // syllable wide at a large font size.
                    BlueprintButton(
                        label = stringResource(R.string.secrets_add),
                        onClick = onAddSecret,
                        modifier = Modifier.testTag("workflow-add-secret"),
                    )
                }
            }
            if (!item.isDeviceDefault) {
                BlueprintButton(
                    label = stringResource(R.string.workflow_use),
                    onClick = onSetDefault,
                    tone = ButtonTone.QUIET,
                )
            }
            BlueprintButton(
                label = stringResource(R.string.action_delete),
                onClick = onDelete,
                tone = ButtonTone.DANGER,
            )
        }
        HairLine()
    }
}
