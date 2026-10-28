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
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintDialogText
import app.recly.windows.ui.component.BlueprintRadioRow
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.DialogTone
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.MinTouch

/**
 * docs/03 "Deleting in the app": one recording, two answers about Drive, and the default is the one that can
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
    // Nothing of it reached Drive or the local folder: there is no second answer to choose between (P5).
    val asks = !request.remote && request.hasDriveFolder
    BlueprintDialog(
        title = strings[Str.DELETE_TITLE, request.title.text(strings)],
        onDismissRequest = onCancel,
        theme = theme,
        height = if (asks) DELETE_HEIGHT else REMOTE_DELETE_HEIGHT,
        // Without the two answers, only as tall as the line or two it says.
        fitContent = !asks,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(
                label = strings[Str.DELETE],
                onClick = { onDelete(request, deleteDrive) },
                tone = ButtonTone.DANGER,
                minWidth = MinTouch,
            )
        },
    ) {
        if (request.remote) {
            // docs/03: what it costs, not a question — the Drive folder is the only copy there is,
            // and it is the one every device reads.
            BlueprintDialogText(strings[Str.DELETE_REMOTE_BODY])
        } else {
            if (request.unuploaded > 0) {
                BlueprintDialogText(
                    strings[if (request.folder) Str.DELETE_FOLDER_UNUPLOADED else Str.DELETE_UNUPLOADED],
                    tone = DialogTone.DANGER,
                )
            }
            if (!asks) return@BlueprintDialog
            BlueprintRadioRow(
                label = strings[Str.DELETE_LOCAL_ONLY],
                selected = !deleteDrive,
                onSelect = { deleteDrive = false },
            )
            // docs/03 "Storage location": the same answer about the local folder, and the same default.
            BlueprintRadioRow(
                label = strings[if (request.folder) Str.DELETE_WITH_FOLDER else Str.DELETE_WITH_DRIVE],
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
    // The stop's prompt in the same words: `Recording title` over a field called `Title` (P7).
    BlueprintDialog(
        title = strings[Str.RECORDING_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        height = RENAME_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(
                label = strings[Str.SAVE],
                onClick = { onSave(request, title) },
                tone = ButtonTone.PRIMARY,
            )
        },
    ) {
        BlueprintTextField(
            value = title,
            onValueChange = { title = it },
            label = strings[Str.LEDGER_TITLE],
            // A title is something a person types, not a field of data.
            monospace = false,
        )
    }
}

/**
 * docs/14 "Permissions": a start refused because "Let desktop apps access your microphone" is off — the Mac's
 * permission panel in this shell's words: what is needed, where it is switched on, and the page itself.
 */
@Composable
fun MicrophoneDialog(
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.STATUS_MIC_DENIED],
        onDismissRequest = onClose,
        theme = theme,
        height = METERED_HEIGHT,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CLOSE], onClose, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(strings[Str.SETTINGS_OPEN_MICROPHONE], onOpenSettings, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintDialogText(strings[Str.MIC_GUIDANCE])
    }
}

/**
 * docs/03 "Sign out vs Disconnect": revocation can affect other devices and clears this PC's
 * upload queue. Recordings and keys stay; deleting audio is a separate list action.
 */
@Composable
fun DisconnectDialog(
    prompt: DisconnectPrompt,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.DISCONNECT_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        // One explanation and, only when needed, the reason confirmation is blocked.
        height = DISCONNECT_HEIGHT,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            // The confirming action, filled in the accent, not red (2026-10-08): nothing is deleted, and Drive
            // can be connected again. The Settings row that opens this stays quiet.
            BlueprintButton(
                label = strings[Str.SETTINGS_DISCONNECT],
                onClick = { onConfirm(false) },
                tone = ButtonTone.PRIMARY,
                enabled = prompt.canConfirm,
            )
        },
    ) {
        BlueprintDialogText(strings[Str.DISCONNECT_OTHER_DEVICES], tone = DialogTone.MUTED)
        // docs/12: a capture that is running has no job yet, so the core's Busy guard does not cover
        // it. Say what is in the way — a wait, in the warning tone, not a failure (2026-10-10); never stop it for them.
        prompt.blocker?.let { BlueprintDialogText(strings[it], tone = DialogTone.WARNING) }

    }
}

/**
 * The speech model over a metered connection (the capture helper's `--network-cost`): about 1 GB is
 * the user's to spend, so the download waits for the answer. An unknown connection is not asked about.
 */
@Composable
fun MeteredDownloadDialog(
    strings: Strings,
    /** The model's size as this shell writes it ([ByteFormat]), or null when the engine does not say. */
    size: String?,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.PROCESSING_METERED_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        height = METERED_HEIGHT,
        // A title, one line and the answers: only as tall as that.
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(strings[Str.PROCESSING_DOWNLOAD], onDownload, tone = ButtonTone.PRIMARY)
        },
    ) {
        size?.let { BlueprintDialogText(strings[Str.PROCESSING_MODEL_SIZE, it], tone = DialogTone.MUTED) }
    }
}

/**
 * docs/09 "AI view": said once, after the first sign-in on this PC — where the notes' usage goes, and
 * where to see it.
 */
@Composable
fun ChatGptWelcomeDialog(
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onManageUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.CHATGPT_WELCOME_TITLE],
        onDismissRequest = onDismiss,
        theme = theme,
        height = METERED_HEIGHT,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CHATGPT_MANAGE_USAGE], onManageUsage, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.CHATGPT_GOT_IT], onDismiss, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintDialogText(strings[Str.CHATGPT_WELCOME_BODY])
    }
}

/**
 * docs/08 "Organizing": OpenAI's terms ask for express consent before the plan is used in the background — asked right
 * after a ChatGPT sign-in, and at a recording start from this app while it is missing. Continue agrees; Sign out is
 * Settings' own sign-out. Closing it answers nothing, and it is asked again at the next start.
 */
@Composable
fun OrganizeConsentDialog(
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onContinue: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.ORGANIZE_CONSENT_TITLE],
        onDismissRequest = onDismiss,
        theme = theme,
        height = CONSENT_HEIGHT,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CHATGPT_SIGN_OUT], onSignOut, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.ORGANIZE_CONSENT_CONTINUE], onContinue, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintDialogText(strings[Str.ORGANIZE_CONSENT_BODY])
    }
}

/** A title, a paragraph and the answers, before the window takes the card's own height. */
private val CONSENT_HEIGHT: Dp = 240.dp

/** Where the window opens before it takes the card's own height ([BlueprintDialog] `fitContent`). */
private val METERED_HEIGHT: Dp = 160.dp

/** One warning line, two answers about Drive, and the two buttons. */
private val DELETE_HEIGHT: Dp = 280.dp

/** The hint, the field it is about, and the two buttons. */
private val RENAME_HEIGHT: Dp = 220.dp

/** docs/03: another device's recording has no answer to give — one line, and the two buttons. */
private val REMOTE_DELETE_HEIGHT: Dp = 220.dp

private val DISCONNECT_HEIGHT: Dp = 260.dp
