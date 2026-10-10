package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintDropdown
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.SectionFootnote
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.TableRow
import app.recly.android.ui.component.TextLink
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.SummaryFormat
import recly.core.chatgpt.SummaryPreferences

/**
 * docs/09 "Summary view" · docs/15 §10: the user's own ChatGPT plan, for summaries — the Drive account's rows in
 * shape. Not drawn at all where ChatGPT is not offered.
 */
@Composable
fun ChatGptSection() {
    val palette = blueprint
    val context = LocalContext.current
    val signIn = remember { ChatGptSignIn.get(context) }
    val ui by signIn.state.collectAsState()
    val connection by produceState<ChatGptConnection>(ChatGptConnection.Unavailable) {
        CoreModule.get(context).core.chatGpt.observe().collect { value = it }
    }
    LaunchedEffect(Unit) { signIn.refresh() }
    if (ui.welcome) WelcomeDialog(onManageUsage = { signIn.dismissWelcome(); context.openUrl(CHATGPT_USAGE_URL) }, onDone = signIn::dismissWelcome)
    val current = connection
    if (current is ChatGptConnection.Unavailable) return

    SectionHeader(stringResource(R.string.chatgpt_title), Modifier.padding(horizontal = Space.m))
    HairLine()
    // The same accent outline as Connect Drive: the way in, whether it is the first time or again.
    val continueButton: @Composable () -> Unit = {
        ProcessingButton(stringResource(R.string.chatgpt_continue), state = ui.action, onClick = { signIn.signIn { context.openSignIn(it) } },
            tone = ButtonTone.ACCENT, enabled = !ui.signingIn, modifier = Modifier.testTag("chatgpt-continue"))
    }
    when (current) {
        is ChatGptConnection.SignedIn -> {
            // Manage usage is a page at OpenAI, so a link under the plan line rather than a button of its own row (docs/09, 2026-10-09).
            TableRow(current.account, subtitle = stringResource(R.string.chatgpt_using_plan), modifier = Modifier.testTag("chatgpt-account"), trailing = {
                BlueprintButton(stringResource(R.string.chatgpt_sign_out), signIn::signOut, tone = ButtonTone.QUIET, modifier = Modifier.testTag("chatgpt-sign-out"))
            }, under = {
                TextLink(stringResource(R.string.chatgpt_manage_usage), { context.openUrl(CHATGPT_USAGE_URL) }, Modifier.testTag("chatgpt-usage"),
                    style = MaterialTheme.typography.bodySmall)
            })
            // Offline, the plan's list is not known; nothing to choose from then.
            if (current.models.isNotEmpty()) {
                TableRow(stringResource(R.string.chatgpt_model), trailing = {
                    BlueprintDropdown(
                        label = stringResource(R.string.chatgpt_model),
                        options = current.models,
                        selected = current.models.firstOrNull { it.id == current.model } ?: current.models.first(),
                        onSelect = { signIn.selectModel(it.id) },
                        modifier = Modifier.testTag("chatgpt-model"),
                        // OpenAI's own names for its models, as it lists them: never translated.
                        title = { it.label },
                    )
                })
            }
        }

        is ChatGptConnection.Expired -> TableRow(current.account, subtitle = stringResource(R.string.chatgpt_expired),
            subtitleColor = palette.warningInk, trailing = continueButton)

        else -> TableRow(stringResource(R.string.chatgpt_use_plan), subtitle = stringResource(R.string.chatgpt_use_plan_note), trailing = continueButton)
    }
    // While the browser has the sign-in: what to do there, and the way out of it here.
    if (ui.signingIn) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.chatgpt_finish_in_browser), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
            BlueprintButton(stringResource(R.string.action_cancel), signIn::cancel, tone = ButtonTone.QUIET, minWidth = MinTouch,
                modifier = Modifier.testTag("chatgpt-cancel"))
        }
    }
    ui.failure?.let { reason ->
        // A failure in the failure colour; under it what OpenAI or the core said, as it came.
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.chatgpt_sign_in_failed), style = MaterialTheme.typography.bodySmall, color = palette.danger)
            val spoken = spokenReason(reason)
            if (spoken != null) {
                Text(coreMessage(spoken).text(), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
            } else {
                reasonDetail(reason)?.let { Text(it, style = mono.small.copy(textDirection = TextDirection.Ltr), color = palette.textMuted) }
            }
        }
    }
    // What every summary is asked for on this device — set signed in or out; only running a summary needs the sign-in.
    SummaryPreferenceRows(signIn)
    // Not a failure: this device is signed out either way, and the sentence says where the rest is done.
    if (ui.revokeUnconfirmed) SectionFootnote(stringResource(R.string.chatgpt_revoke_unconfirmed))
    SectionFootnote(stringResource(R.string.chatgpt_footnote))
}

/**
 * docs/08 "Summaries": Summary format, My format and About you. The format is saved the moment it is chosen, as
 * the model is; the two fields when editing ends — focus leaves, Done, the screen or the app goes — with no Save.
 * Empty is an answer. The rows wait for what is stored, so a field never starts from a guess.
 *
 * The format offers all five; My format's field is there only while it is the format, and chosen with no words yet it
 * takes the focus and its note says General is used meanwhile.
 */
@Composable
private fun SummaryPreferenceRows(signIn: ChatGptSignIn) {
    val context = LocalContext.current
    val stored by produceState<SummaryPreferences?>(null) {
        CoreModule.get(context).core.summaries.observePreferences().collect { value = it }
    }
    val preferences = stored ?: return
    // My format was just chosen with nothing written for it: its field asks for the words at once.
    var writeFormat by remember { mutableStateOf(false) }
    TableRow(stringResource(R.string.summary_format), trailing = {
        BlueprintDropdown(
            label = stringResource(R.string.summary_format),
            options = SummaryFormat.entries,
            selected = preferences.format,
            onSelect = { format ->
                writeFormat = format == SummaryFormat.CUSTOM && preferences.customFormat.isBlank()
                signIn.updatePreferences { it.copy(format = format) }
            },
            modifier = Modifier.testTag("summary-format"),
            title = { stringResource(it.formatLabel()) },
        )
    })
    if (preferences.format == SummaryFormat.CUSTOM) PreferenceField(
        saved = preferences.customFormat,
        max = SummaryPreferences.CUSTOM_MAX,
        label = stringResource(R.string.summary_format_custom),
        placeholder = stringResource(R.string.summary_custom_placeholder),
        supporting = stringResource(if (preferences.customFormat.isBlank()) R.string.summary_custom_empty else R.string.summary_custom_note),
        lines = CUSTOM_LINES..CUSTOM_MAX_LINES,
        newlines = true,
        onSave = { text -> signIn.updatePreferences { it.copy(customFormat = text) } },
        tag = "summary-custom",
        focus = writeFormat,
        onFocused = { writeFormat = false },
    )
    PreferenceField(
        saved = preferences.aboutMe,
        max = SummaryPreferences.ABOUT_MAX,
        label = stringResource(R.string.summary_about),
        placeholder = stringResource(R.string.summary_about_placeholder),
        supporting = stringResource(R.string.summary_about_note),
        lines = 1..ABOUT_MAX_LINES,
        newlines = false,
        onSave = { text -> signIn.updatePreferences { it.copy(aboutMe = text) } },
        tag = "summary-about",
    )
}

/**
 * One text setting as a table row: its name over the field and a quiet line under it, cut at [max] characters as it
 * is typed. What was typed is saved when editing ends and differs from [saved] — the core trims it. The box is
 * [lines] tall, growing with the text; Return adds a line where [newlines], and is Done — the end of editing —
 * where not. [focus] puts the cursor in it as it appears, and [onFocused] says that happened.
 */
@Composable
private fun PreferenceField(
    saved: String,
    max: Int,
    label: String,
    placeholder: String,
    supporting: String,
    lines: IntRange,
    newlines: Boolean,
    onSave: (String) -> Unit,
    tag: String,
    focus: Boolean = false,
    onFocused: () -> Unit = {},
) {
    var text by remember(saved) { mutableStateOf(saved) }
    val focusManager = LocalFocusManager.current
    val current by rememberUpdatedState(text)
    val kept by rememberUpdatedState(saved)
    val save by rememberUpdatedState(onSave)
    val commit = { if (current.trim() != kept) save(current) }
    // The screen going (another tab) and the app going (Home, the switcher) both end the editing.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) commit() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            commit()
        }
    }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    Column(Modifier.fillMaxWidth().background(blueprint.surface)) {
        BlueprintField(
            text,
            { text = it.take(max) },
            modifier = Modifier.fillMaxWidth().padding(Space.m),
            label = label,
            placeholder = placeholder,
            supporting = supporting,
            keyboardOptions = if (newlines) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            fieldModifier = Modifier
                .focusRequester(requester)
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                }
                .testTag(tag),
            minLines = lines.first,
            maxLines = lines.last,
        )
        HairLine()
    }
}

/** My format's box: two lines to start with, eight before it scrolls; About you wraps to three. */
private const val CUSTOM_LINES = 2
private const val CUSTOM_MAX_LINES = 8
private const val ABOUT_MAX_LINES = 3

/** docs/09 "Summary view": once, after the first sign-in on this device. */
@Composable
private fun WelcomeDialog(onManageUsage: () -> Unit, onDone: () -> Unit) {
    BlueprintDialog(
        title = stringResource(R.string.chatgpt_welcome_title),
        onDismissRequest = onDone,
        actions = {
            BlueprintButton(stringResource(R.string.chatgpt_manage_usage), onManageUsage, tone = ButtonTone.QUIET)
            BlueprintButton(stringResource(R.string.chatgpt_got_it), onDone, tone = ButtonTone.PRIMARY, modifier = Modifier.testTag("chatgpt-got-it"))
        },
    ) { BlueprintDialogText(stringResource(R.string.chatgpt_welcome_body)) }
}
