package app.recly.windows.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintCheckRow
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintDialogLink
import app.recly.windows.ui.component.BlueprintDialogText
import app.recly.windows.ui.component.BlueprintRadioRow
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.DialogTone
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * docs/03 "앱에서 지우기": one recording, two answers about Drive, and the default is the one that can
 * be undone — the files in Drive are the user's own and something downstream may already have read
 * the folder. What is still only on this PC is said first, because that is the part of the deletion
 * nothing anywhere else can give back.
 */
@Composable
fun DeleteDialog(
    request: DeleteRequest,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onDelete: (DeleteRequest, Boolean) -> Unit,
) {
    // A recording another device uploaded is only in Drive, so the answer is already given: there is
    // no local copy the "leave it in Drive" branch would keep.
    var deleteDrive by remember(request.recordingId) { mutableStateOf(request.remote) }
    BlueprintDialog(
        title = strings[Str.DELETE_TITLE, request.title.text(strings)],
        onDismissRequest = onCancel,
        theme = theme,
        height = if (request.remote) REMOTE_DELETE_HEIGHT else DELETE_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(
                label = strings[Str.DELETE],
                onClick = { onDelete(request, deleteDrive) },
                tone = ButtonTone.DANGER,
            )
        },
    ) {
        if (request.remote) {
            // docs/03: what it costs, not a question — the Drive folder is the only copy there is,
            // and it is the one every device reads.
            BlueprintDialogText(strings[Str.DELETE_REMOTE_BODY], tone = DialogTone.DANGER)
        } else {
            if (request.unuploaded > 0) {
                BlueprintDialogText(
                    strings[Str.DELETE_UNUPLOADED, request.unuploaded],
                    tone = DialogTone.DANGER,
                )
            }
            BlueprintRadioRow(
                label = strings[Str.DELETE_LOCAL_ONLY],
                selected = !deleteDrive,
                onSelect = { deleteDrive = false },
            )
            BlueprintRadioRow(
                label = strings[Str.DELETE_WITH_DRIVE],
                selected = deleteDrive,
                onSelect = { deleteDrive = true },
            )
        }
    }
}

/**
 * docs/03: the name of one recording, changed after the fact. It is the prompt the stop asks, in
 * the same words — minus the question about the room, which is a hint for the transcribe step and
 * not a name, and nothing here is going to run that step again.
 */
@Composable
fun RenameDialog(
    request: RenameRequest,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onSave: (RenameRequest, String) -> Unit,
) {
    var title by remember(request.recordingId) { mutableStateOf(request.title) }
    BlueprintDialog(
        title = strings[Str.DETAIL_RENAME],
        onDismissRequest = onCancel,
        theme = theme,
        height = RENAME_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(
                label = strings[Str.SAVE],
                onClick = { onSave(request, title) },
                tone = ButtonTone.PRIMARY,
            )
        },
    ) {
        BlueprintDialogText(strings[Str.TITLE_HINT], tone = DialogTone.MUTED)
        BlueprintTextField(
            value = title,
            onValueChange = { title = it },
            label = strings[Str.RECORDING_TITLE],
            // A title is something a person types, not a field of data.
            monospace = false,
        )
    }
}

/**
 * docs/03 "로그아웃 vs 연결 해제": the four things that are true of a disconnect and are not true of a
 * sign-out, and the one separate question — the recordings, which this never takes by default
 * (principle 3: nothing is deleted before it has been acknowledged somewhere else).
 */
@Composable
fun DisconnectDialog(
    prompt: DisconnectPrompt,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onPermissions: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var alsoDelete by remember { mutableStateOf(false) }
    BlueprintDialog(
        title = strings[Str.DISCONNECT_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        // The warnings, a question and a link: this is the tallest thing the app asks.
        height = DISCONNECT_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(
                label = strings[Str.SETTINGS_DISCONNECT],
                onClick = { onConfirm(alsoDelete) },
                tone = ButtonTone.DANGER,
                enabled = prompt.canConfirm,
            )
        },
    ) {
        // docs/03: what a disconnect takes away. Only the audio that exists nowhere else takes the
        // red — several red paragraphs would leave the colour meaning nothing, and it is the same
        // line the delete dialog puts in red for the same reason.
        BlueprintDialogText(strings[Str.DISCONNECT_OTHER_DEVICES])
        // Nothing is owed to Drive: "0 recordings have not reached Drive yet" is a warning about
        // nothing, in the one colour this dialog keeps for what cannot be given back. The delete
        // dialog leaves the same line out for the same reason.
        if (prompt.unuploaded > 0) {
            BlueprintDialogText(
                strings[Str.DISCONNECT_UNUPLOADED, prompt.unuploaded],
                tone = DialogTone.DANGER,
            )
        }
        BlueprintDialogText(strings[Str.DISCONNECT_LOCAL])
        // docs/12: a capture that is running has no job yet, so the core's Busy guard does not cover
        // it. Say what is in the way; never stop it for them.
        prompt.blocker?.let { BlueprintDialogText(strings[it], tone = DialogTone.DANGER) }
        BlueprintCheckRow(
            label = strings[Str.DISCONNECT_ALSO_DELETE],
            checked = alsoDelete,
            onCheckedChange = { alsoDelete = it },
        )
        // docs/03: a user who only wants this one device off the account has another way, and it is
        // Google's own page rather than anything this app can do for them.
        BlueprintDialogLink(strings[Str.DISCONNECT_PERMISSIONS], onPermissions)
    }
}

/**
 * docs/05 "워크플로우 가져오기": there is no merge, so the one question worth asking is asked before
 * anything is written — and it is asked with the number the file actually holds.
 */
@Composable
fun ImportDialog(
    picked: PickedWorkflows,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.WORKFLOWS_IMPORT_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        height = IMPORT_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(
                label = strings[Str.SETTINGS_IMPORT_WORKFLOWS],
                onClick = onConfirm,
                tone = ButtonTone.DANGER,
            )
        },
    ) {
        BlueprintDialogText(
            strings[Str.WORKFLOWS_IMPORT_BODY, picked.workflows],
            tone = DialogTone.DANGER,
        )
        BlueprintDialogText(strings[Str.SETTINGS_WORKFLOWS_KEYS_HINT])
    }
}

/**
 * ADR-016: a workflow deleted here is gone from this PC and there is no sync to bring it back, so
 * the question is asked before the write — the same question, in the same words, that Android asks.
 * The in-use warning is part of it rather than part of the row: it is what the answer costs, and
 * the row is not where the answer is given.
 */
@Composable
fun WorkflowDeleteDialog(
    item: WorkflowItem,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.DELETE_TITLE, item.name.ifBlank { strings[Str.UNNAMED] }],
        onDismissRequest = onCancel,
        theme = theme,
        height = WORKFLOW_DELETE_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.DELETE], onDelete, tone = ButtonTone.DANGER)
        },
    ) {
        BlueprintDialogText(strings[Str.WORKFLOWS_DELETE_BODY])
        if (item.isDeviceDefault) {
            BlueprintDialogText(
                strings[Str.WORKFLOW_DELETE_IN_USE],
                tone = DialogTone.DANGER,
            )
        }
    }
}

/** One warning line, two answers about Drive, and the two buttons. */
private val DELETE_HEIGHT: Dp = 280.dp

/** The hint, the field it is about, and the two buttons. */
private val RENAME_HEIGHT: Dp = 220.dp

/** docs/03: another device's recording has no answer to give — one line, and the two buttons. */
private val REMOTE_DELETE_HEIGHT: Dp = 220.dp

private val DISCONNECT_HEIGHT: Dp = 400.dp

/** The import replace question and the hint under it, plus the two buttons. */
private val IMPORT_HEIGHT: Dp = 240.dp

/** What a workflow delete costs, plus the default warning when there is one, and the two buttons. */
private val WORKFLOW_DELETE_HEIGHT: Dp = 240.dp
