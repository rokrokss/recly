package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintDropdown
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.SectionFootnote
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.TableRow
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.chatgpt.ChatGptConnection

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
            TableRow(current.account, subtitle = stringResource(R.string.chatgpt_using_plan), modifier = Modifier.testTag("chatgpt-account"), trailing = {
                BlueprintButton(stringResource(R.string.chatgpt_sign_out), signIn::signOut, tone = ButtonTone.QUIET, modifier = Modifier.testTag("chatgpt-sign-out"))
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
            Row(Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.m, vertical = Space.s), horizontalArrangement = Arrangement.End) {
                BlueprintButton(stringResource(R.string.chatgpt_manage_usage), { context.openUrl(CHATGPT_USAGE_URL) }, tone = ButtonTone.QUIET,
                    modifier = Modifier.testTag("chatgpt-usage"))
            }
            HairLine()
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
                reasonDetail(reason)?.let { Text(it, style = mono.small, color = palette.textMuted) }
            }
        }
    }
    // Not a failure: this device is signed out either way, and the sentence says where the rest is done.
    if (ui.revokeUnconfirmed) SectionFootnote(stringResource(R.string.chatgpt_revoke_unconfirmed))
    SectionFootnote(stringResource(R.string.chatgpt_footnote))
}

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
