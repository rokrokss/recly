@file:OptIn(ExperimentalTime::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.agent.AgentEvents
import app.recly.windows.agent.AgentEventsAccount
import app.recly.windows.agent.AgentEventsPhase
import app.recly.windows.agent.AgentEventsSubscription
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.detect.MicAccess
import app.recly.windows.detect.MicrophoneAccess
import app.recly.windows.helper.CaptureHelper
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.settings.AppTheme
import app.recly.windows.ui.component.BlueprintDialogLink
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.BlueprintDropdown
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.SectionHeader
import app.recly.windows.ui.component.SELECTION_MARK
import app.recly.windows.ui.component.SectionFootnote
import app.recly.windows.ui.component.SwitchRow
import app.recly.windows.ui.component.TableRow
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlin.time.ExperimentalTime
import recly.core.storage.StorageKind

/**
 * docs/09 screen principle 4, over docs/14 "App": a section table — the storage and its account (docs/03,
 * docs/06), the language (docs/07), the theme override (docs/09 "Accessibility": motion and contrast are
 * the system's alone, and there is no accessibility section), capture and its self-test, startup, the
 * agent connection, and the honest block of what this build actually is.
 */
@Composable
fun SettingsWindow(model: ShellModel, strings: Strings) {
    val palette = blueprint
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ScreenHeader(title = strings[Str.WINDOW_SETTINGS])
        HairLine()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Storage(model, strings)
            Language(model, strings)
            Appearance(model, strings)
            Capture(model, strings)
            Startup(model, strings)
            Data(model, strings)
            model.processing?.let { ProcessingPanel(it, strings, preparationAllowed = !model.recording && model.transition == null) }
            model.agentEvents?.let { AgentConnection(model, it, strings) }
            About(model, strings)
        }
    }
}

/**
 * docs/03 "Storage location": where new recordings go — Google Drive, or a folder the user picked on this
 * PC — in chips as the theme is, saved the moment one is tapped. Under them only the chosen storage's
 * rows: the Drive block exactly as it was, or the one local folder row in the same shape. Drive stays
 * connected after a switch — the earlier recordings are still in it — and its Disconnect is back under
 * the Google Drive chip.
 */
@Composable
private fun Storage(model: ShellModel, strings: Strings) {
    // Asked again whenever this window comes to the front, its opening included: the folder may be on
    // a drive that was pulled out while the window was behind.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) model.refreshStorage() }
    Section(strings[Str.SETTINGS_STORAGE])
    ChipRow(
        // The Drive chip is the product name the account block was headed with (docs/07 rule 1).
        options = listOf(
            StorageKind.DRIVE to strings[Str.SETTINGS_ACCOUNT],
            StorageKind.FOLDER to strings[Str.STORAGE_LOCAL_FOLDER],
        ),
        selected = model.storage,
        onSelect = model::selectStorage,
    )
    if (model.storage == StorageKind.FOLDER) LocalFolderRow(model, strings) else Account(model, strings)
}

/**
 * The local folder in the Drive row's shape: where it is and a way to change it, nothing picked yet, or
 * a folder that cannot be reached any more and has to be picked again.
 */
@Composable
private fun LocalFolderRow(model: ShellModel, strings: Strings) {
    val folder = model.localFolder
    val reachable = folder != null && model.localFolderAvailable
    TableRow(
        title = folder ?: strings[Str.STORAGE_NO_FOLDER],
        subtitle = if (folder != null && !reachable) strings[Str.STORAGE_FOLDER_UNREACHABLE] else null,
        trailing = {
            BlueprintButton(
                strings[if (reachable) Str.STORAGE_CHANGE_FOLDER else Str.STORAGE_CHOOSE_FOLDER],
                model::chooseLocalFolder,
            )
        },
    )
}

/** docs/06: the Drive rows, under the Google Drive chip. */
@Composable
private fun Account(model: ShellModel, strings: Strings) {
    val signInBlocker = DisconnectGuard.signInBlocker(model.disconnectPhase.owed)
    if (model.signedIn || model.disconnectPhase.owed || model.disconnecting) {
        TableRow(title = strings[if (model.disconnecting) Str.SETTINGS_ACCOUNT else if (model.signedIn) Str.SETTINGS_SIGNED_IN else Str.DRIVE_ATTENTION], trailing = {
            BlueprintButton(strings[if (model.disconnecting) Str.DRIVE_DISCONNECTING else Str.DRIVE_DISCONNECT],
                model::askToDisconnect, tone = ButtonTone.DANGER, enabled = !model.disconnecting)
        })
    } else {
        TableRow(
            title = strings[Str.SETTINGS_SIGNED_OUT],
            subtitle = strings[if (model.clientConfigured) Str.DRIVE_OPTIONAL else Str.SETTINGS_NO_CLIENT],
            trailing = {
                ProcessingButton(label = strings[Str.SIGN_IN], state = model.action, strings = strings,
                    onClick = model::signIn, tone = ButtonTone.PRIMARY,
                    enabled = model.clientConfigured && signInBlocker == null)
            },
        )
    }
    if (model.revokeDebt && !model.disconnecting) {
        SectionFootnote(strings[Str.DISCONNECT_STILL_LISTED])
        BlueprintDialogLink(strings[Str.DISCONNECT_PERMISSIONS], model::openAccountPermissions,
            modifier = Modifier.padding(horizontal = Space.m))
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m), horizontalArrangement = Arrangement.End) {
            BlueprintButton(strings[Str.DISCONNECT_REMOVED], model::revokeDebtSettled, tone = ButtonTone.QUIET)
        }
    }
}

/**
 * docs/07 rule 2·3: a per-machine choice, and this window follows it the moment it changes.
 *
 * A row that names the language it is in, with the rest in a dropdown behind it: the list of
 * languages grows, and a row stays one line however long that list gets.
 */
@Composable
private fun Language(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_LANGUAGE])
    TableRow(
        title = strings[Str.SETTINGS_APP_LANGUAGE],
        trailing = {
            // Each language under its own name — a label that is never translated, so whoever
            // cannot read the language the app is currently in can still find the one they want
            // (docs/07 rule 1). What is marked is the language this window is in.
            BlueprintDropdown(
                label = strings[Str.SETTINGS_APP_LANGUAGE],
                options = AppLanguage.choices.map { (language, label) -> language to strings[label] },
                selected = model.language,
                onSelect = model::selectLanguage,
            )
        },
    )
}

/** docs/09: the system's dark mode, or the user's override of it. */
@Composable
private fun Appearance(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_THEME])
    ChipRow(
        options = AppTheme.entries.map { it to strings[it.label] },
        selected = model.theme,
        onSelect = model::selectTheme,
    )
}

/**
 * docs/14 "Detection" · ADR-011: detect, ask, record — automatic recording is the user's to turn on. And
 * the two facts a support question always starts with: whether there is a capture helper, and what
 * it says about the machine it is on.
 */
@Composable
private fun Capture(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_RECORDING])
    // docs/12 M8: the reminder is on by default and this is where it goes off — and back on, which
    // the dialog's own "Do not ask again" cannot do.
    SwitchRow(
        title = strings[Str.SETTINGS_CONSENT_REMINDER],
        checked = model.consentReminder,
        onCheckedChange = model::toggleConsentReminder,
    )
    // docs/14 "Permissions": there is no prompt, so silence is all that is recorded while this is off — and
    // a row that only says where the switch is leaves the user to find it. Its own row rather than a
    // line under the reminder, because it has something to be done about it: the page itself, which
    // is how the Mac answers the same refusal.
    if (model.micAccess == MicAccess.DENIED) {
        TableRow(
            title = strings[MicrophoneAccess.GUIDANCE],
            trailing = {
                BlueprintButton(strings[Str.SETTINGS_OPEN_MICROPHONE], model::openMicrophoneSettings)
            },
        )
    }
    TableRow(
        title = if (model.helperMissing) {
            strings[Str.SETTINGS_HELPER_MISSING, strings[ShellModel.HELPER_MISSING], CaptureHelper.OVERRIDE_ENV]
        } else {
            model.helperVersion?.let { strings[Str.SETTINGS_HELPER_VERSION, it] }
                ?: strings[Str.SETTINGS_HELPER_SILENT]
        },
        subtitle = model.selfTest?.text(strings),
        trailing = {
            // deliverable 3: `--self-test`, from the one place a packaged app can offer it.
            if (!model.helperMissing) {
                BlueprintButton(strings[Str.SETTINGS_SELF_TEST], model::runSelfTest)
            }
        },
    )
}

@Composable
private fun Startup(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_STARTUP])
    SwitchRow(
        title = strings[Str.SETTINGS_LAUNCH_AT_LOGIN],
        subtitle = if (model.launchAtLoginSupported) null else strings[Str.SETTINGS_LAUNCH_UNSUPPORTED],
        checked = model.launchAtLogin,
        onCheckedChange = model::toggleLaunchAtLogin,
        enabled = model.launchAtLoginSupported,
    )
}

/**
 * docs/14 "Agent connection": recly-events run for the user, off by default and only where recordings
 * go to Google Drive. On, it says in one line under the switch how the server is, then asks for what
 * `serve` needs — recly-events' own Google sign-in and an OpenAI tunnel — and keeps the set-up guide in
 * view until an agent subscribes.
 */
@Composable
private fun AgentConnection(model: ShellModel, agent: AgentEvents, strings: Strings) {
    // Asked again whenever this window comes to the front: the Drive this PC uploads to may have been
    // reconnected while it was behind.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) agent.sectionShown() }
    Section(strings[Str.AGENT_SECTION])
    // A build without the program, or a storage recly-events cannot watch: the switch says which, in
    // the place of its second line, and cannot be turned.
    val note = when (agent.phase) {
        AgentEventsPhase.Unavailable -> Str.AGENT_UNAVAILABLE
        AgentEventsPhase.NotDrive -> Str.AGENT_NOT_DRIVE
        else -> null
    }
    SwitchRow(
        title = strings[Str.AGENT_TOGGLE],
        subtitle = note?.let { strings[it] },
        checked = agent.enabled && note == null,
        onCheckedChange = agent::toggle,
        enabled = note == null,
    )
    if (!agent.enabled || note != null) return
    AgentStatus(agent, strings)
    AgentGoogle(agent, strings)
    AgentTunnel(agent, strings)
    if (!agent.subscribed) {
        SectionFootnote(strings[Str.AGENT_FOOTNOTE])
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s)) {
            BlueprintButton(strings[Str.AGENT_GUIDE], model::openAgentGuide, tone = ButtonTone.QUIET)
        }
    }
}

/**
 * One line under the switch, and only what the rows below do not already say: nothing while set-up
 * is incomplete or the sign-in is what is wrong. The square loader only while something is under way;
 * a running server says that it works, or what to do next — never what is merely possible.
 */
@Composable
private fun AgentStatus(agent: AgentEvents, strings: Strings) {
    when (val phase = agent.phase) {
        AgentEventsPhase.Off, AgentEventsPhase.Unavailable, AgentEventsPhase.NotDrive,
        AgentEventsPhase.NeedsSetup, AgentEventsPhase.GoogleEnded -> Unit
        AgentEventsPhase.SigningIn -> AgentWorking(strings[Str.AGENT_STATUS_SIGNING_IN])
        AgentEventsPhase.Starting -> AgentWorking(strings[Str.AGENT_STATUS_STARTING])
        AgentEventsPhase.Connecting -> AgentWorking(strings[Str.AGENT_STATUS_CONNECTING])
        // Another account sees none of Recly's files, so the agent hears nothing: the row says so.
        is AgentEventsPhase.Running -> if (agent.account != AgentEventsAccount.DIFFERENT) {
            AgentLine(
                strings[
                    when (phase.subscription) {
                        AgentEventsSubscription.ACTIVE -> Str.AGENT_STATUS_SUBSCRIBED
                        AgentEventsSubscription.NONE -> Str.AGENT_STATUS_NOT_SUBSCRIBED
                        AgentEventsSubscription.ENDED -> Str.AGENT_STATUS_SUBSCRIPTION_ENDED
                    },
                ],
            )
        }
        AgentEventsPhase.TunnelError -> AgentLine(strings[Str.AGENT_STATUS_TUNNEL_ERROR], danger = true)
        AgentEventsPhase.Elsewhere -> AgentLine(strings[Str.AGENT_STATUS_ELSEWHERE])
        AgentEventsPhase.GaveUp -> AgentLine(strings[Str.AGENT_STATUS_GAVE_UP], danger = true)
    }
}

/**
 * recly-events' own Google sign-in — a consent of its own, not the Drive connection that uploads —
 * named for what it is, and whether it is the account Recly uploads to. A working sign-in has no
 * button.
 */
@Composable
private fun AgentGoogle(agent: AgentEvents, strings: Strings) {
    val (subtitle, button) = when {
        !agent.googleSignedIn -> Str.AGENT_GOOGLE_SIGN_IN_HINT to Str.AGENT_GOOGLE_SIGN_IN
        agent.googleEnded -> Str.AGENT_GOOGLE_ENDED to Str.AGENT_GOOGLE_SIGN_IN_AGAIN
        agent.account == AgentEventsAccount.DIFFERENT -> Str.AGENT_GOOGLE_DIFFERENT to Str.AGENT_GOOGLE_SIGN_IN_AGAIN
        agent.account == AgentEventsAccount.SAME -> Str.AGENT_GOOGLE_SAME to null
        else -> Str.AGENT_GOOGLE_SIGNED_IN to null
    }
    val danger = button == Str.AGENT_GOOGLE_SIGN_IN_AGAIN
    TableRow(
        title = strings[Str.AGENT_GOOGLE],
        subtitle = strings[subtitle],
        subtitleColor = if (danger) blueprint.danger else null,
        trailing = if (button == null) {
            null
        } else {
            {
                BlueprintButton(
                    strings[button],
                    agent::connectGoogle,
                    tone = ButtonTone.QUIET,
                    enabled = agent.phase != AgentEventsPhase.SigningIn,
                )
            }
        },
    )
}

/**
 * docs/05 "Secrets": a saved tunnel is a row that says so, as a saved transcription key is; the
 * fields come back only to take a new one, and a key left empty keeps the saved one.
 */
@Composable
private fun AgentTunnel(agent: AgentEvents, strings: Strings) {
    var changing by remember { mutableStateOf(false) }
    var tunnelId by remember(agent.tunnelId) { mutableStateOf(agent.tunnelId) }
    var tunnelKey by remember { mutableStateOf("") }
    if (agent.tunnelId.isNotEmpty() && agent.tunnelKeySaved && !changing) {
        TableRow(
            title = strings[Str.AGENT_TUNNEL],
            subtitle = "$SELECTION_MARK ${strings[Str.PROCESSING_KEY_ON_DEVICE]}",
            subtitleColor = blueprint.success,
            trailing = {
                BlueprintButton(
                    strings[Str.AGENT_TUNNEL_CHANGE],
                    {
                        tunnelId = agent.tunnelId
                        tunnelKey = ""
                        changing = true
                    },
                    tone = ButtonTone.QUIET,
                )
            },
        )
        return
    }
    SettingsCard {
        Text(strings[Str.AGENT_TUNNEL], style = MaterialTheme.typography.bodyMedium, color = blueprint.text)
        BlueprintTextField(tunnelId, { tunnelId = it }, strings[Str.AGENT_TUNNEL_ID], placeholder = "tunnel_…")
        BlueprintTextField(
            tunnelKey,
            { tunnelKey = it },
            strings[Str.AGENT_TUNNEL_KEY],
            placeholder = if (agent.tunnelKeySaved) strings[Str.AGENT_TUNNEL_KEY_KEEP] else "sk-…",
            secret = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            if (changing) {
                BlueprintButton(
                    strings[Str.CANCEL],
                    {
                        tunnelKey = ""
                        changing = false
                    },
                    tone = ButtonTone.QUIET,
                )
            }
            BlueprintButton(
                strings[Str.SAVE],
                {
                    agent.saveTunnel(tunnelId, tunnelKey) { changing = false }
                    tunnelKey = ""
                },
                tone = ButtonTone.QUIET,
                // An ID, and a key unless one is saved already.
                enabled = tunnelId.isNotBlank() && (agent.tunnelKeySaved || tunnelKey.isNotBlank()),
            )
        }
        if (agent.saveFailed) {
            Text(strings[Str.AGENT_SAVE_FAILED], style = MaterialTheme.typography.bodySmall, color = blueprint.danger)
        }
    }
    HairLine()
}

@Composable
private fun AgentLine(text: String, danger: Boolean = false) {
    SettingsCard {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (danger) blueprint.danger else blueprint.text)
    }
    HairLine()
}

@Composable
private fun AgentWorking(text: String) {
    SettingsCard { LoadingText(text, MaterialTheme.typography.bodyMedium, blueprint.text) }
    HairLine()
}

@Composable
private fun Data(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_DATA])
    SettingsCard {
        // docs/09: a path is data, so it is monospace and it is shown rather than described.
        Mono(model.dataDir)
        BlueprintButton(strings[Str.SETTINGS_OPEN_FOLDER], model::openDataDir, tone = ButtonTone.QUIET,
            modifier = Modifier.align(Alignment.End))
    }
    HairLine()
}

/** docs/09 trend 6: no mascot and no "handmade" line — what this build actually is, in monospace. */
@Composable
private fun About(model: ShellModel, strings: Strings) {
    val palette = blueprint
    Section(strings[Str.SETTINGS_ABOUT])
    SettingsCard(spacing = 2.dp) {
        Mono(strings[Str.SETTINGS_ABOUT_APP, OAuthConfig.APP_VERSION, system()])
        Mono(strings[Str.SETTINGS_ABOUT_DEVICE, model.deviceId])
        Text(
            strings[Str.SETTINGS_OPEN_SOURCE],
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
        Mono(strings[Str.SETTINGS_OPEN_SOURCE_VALUE])
    }
    HairLine()
}

@Composable
private fun Mono(line: String) {
    Text(line, style = mono.small, color = blueprint.textMuted)
}

/**
 * The block a setting's own controls sit in, under the [Section] header that names it: the surface,
 * the page's margins, and one rhythm down it.
 */
@Composable
private fun SettingsCard(spacing: Dp = Space.s, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(blueprint.surface)
            .padding(horizontal = Space.m, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun Section(title: String) {
    SectionHeader(title, Modifier.padding(horizontal = Space.m))
    HairLine()
}

/** A row of the choices for a setting that has a few of them and will not grow — the storage, the theme. */
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(blueprint.surface)
            .padding(horizontal = Space.m, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEach { (value, label) ->
            BlueprintChip(label = label, selected = selected == value, onClick = { onSelect(value) })
        }
    }
    HairLine()
}

private fun system(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
