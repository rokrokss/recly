@file:OptIn(ExperimentalTime::class)

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.detect.MicAccess
import app.recly.windows.detect.MicrophoneAccess
import app.recly.windows.helper.CaptureHelper
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.settings.AppTheme
import app.recly.windows.settings.RecordingMode
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintDropdown
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.SectionHeader
import app.recly.windows.ui.component.SwitchRow
import app.recly.windows.ui.component.TableRow
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlin.time.ExperimentalTime

/**
 * docs/09 화면 원칙 4, over docs/14 "앱": a section table — the account (docs/06), the language
 * (docs/07), the theme override (docs/09 "접근성": motion and contrast are the system's alone, and
 * there is no accessibility section), capture and its self-test, startup, and the honest block of
 * what this build actually is.
 */
@Composable
fun SettingsWindow(model: ShellModel, strings: Strings) {
    val palette = blueprint
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ScreenHeader(title = strings[Str.WINDOW_SETTINGS])
        HairLine()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Account(model, strings)
            Language(model, strings)
            Appearance(model, strings)
            Capture(model, strings)
            Startup(model, strings)
            Data(model, strings)
            Workflows(model, strings)
            About(model, strings)
        }
    }
}

@Composable
private fun Account(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_ACCOUNT])
    // docs/06: while a disconnect is still owed the account slot is not free, and the row says so
    // rather than offering a sign-in — or a sign-out — that would be refused.
    val signInBlocker = DisconnectGuard.signInBlocker(model.disconnectPhase.owed)
    TableRow(
        title = strings[if (model.signedIn) Str.SETTINGS_SIGNED_IN else Str.SETTINGS_SIGNED_OUT],
        // docs/06 4: the Desktop client id is per-developer and never committed.
        subtitle = signInBlocker?.let { strings[it] }
            ?: if (model.clientConfigured) null else strings[Str.SETTINGS_NO_CLIENT],
        trailing = {
            if (model.signedIn) {
                BlueprintButton(
                    strings[Str.SIGN_OUT],
                    model::signOut,
                    tone = ButtonTone.QUIET,
                    enabled = signInBlocker == null,
                )
            } else {
                ProcessingButton(
                    label = strings[Str.SIGN_IN],
                    state = model.action,
                    strings = strings,
                    onClick = model::signIn,
                    tone = ButtonTone.PRIMARY,
                    enabled = model.clientConfigured && signInBlocker == null,
                )
            }
        },
    )
    // docs/03 "로그아웃 vs 연결 해제": two rows, not one switch — signing out is this PC, and
    // disconnecting takes the grant away from every device the account is on. It outlives the
    // signed-in state, because a disconnect whose clean-up failed is still owed.
    if (model.signedIn || model.disconnectPhase.owed) {
        TableRow(
            title = strings[Str.SETTINGS_DISCONNECT],
            subtitle = strings[Str.SETTINGS_DISCONNECT_HINT],
            trailing = {
                ProcessingButton(
                    label = strings[Str.SETTINGS_DISCONNECT],
                    state = model.action,
                    strings = strings,
                    onClick = model::askToDisconnect,
                    tone = ButtonTone.DANGER,
                )
            },
        )
    }
    // docs/03: a revoke that failed leaves the grant standing, and it is Google's page — not this
    // app — that takes it down. So the line outlives the disconnect, the phase and even the signed-in
    // state, and only the user closes it.
    if (model.revokeDebt) {
        TableRow(
            title = strings[Str.DISCONNECT_STILL_LISTED],
            subtitle = ShellModel.GOOGLE_PERMISSIONS_URL,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    BlueprintButton(
                        strings[Str.DISCONNECT_PERMISSIONS],
                        model::openAccountPermissions,
                        tone = ButtonTone.PRIMARY,
                    )
                    BlueprintButton(
                        strings[Str.DISCONNECT_REMOVED],
                        model::revokeDebtSettled,
                        tone = ButtonTone.QUIET,
                    )
                }
            },
        )
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
        title = strings[Str.SETTINGS_LANGUAGE],
        trailing = {
            // Each language under its own name — a label that is never translated, so whoever
            // cannot read the language the app is currently in can still find the one they want
            // (docs/07 rule 1). What is marked is the language this window is in.
            BlueprintDropdown(
                label = strings[Str.SETTINGS_LANGUAGE],
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
 * docs/14 "감지" · ADR-011: detect, ask, record — automatic recording is the user's to turn on. And
 * the two facts a support question always starts with: whether there is a capture helper, and what
 * it says about the machine it is on.
 */
@Composable
private fun Capture(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_RECORDING])
    // docs/14 "캡처": the mode is picked before a recording and cannot change during one — the track
    // set is written into the meta at the start. The Mac's popover offers the same two chips.
    TableRow(
        title = strings[Str.SETTINGS_CAPTURE_MODE],
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                RecordingMode.entries.forEach { mode ->
                    BlueprintChip(
                        label = strings[mode.label],
                        selected = model.recordingMode == mode,
                        onClick = { model.selectRecordingMode(mode) },
                        enabled = !model.recording,
                    )
                }
            }
        },
    )
    // docs/12 M8: the reminder is on by default and this is where it goes off — and back on, which
    // the dialog's own "Do not ask again" cannot do.
    SwitchRow(
        title = strings[Str.SETTINGS_CONSENT_REMINDER],
        checked = model.consentReminder,
        onCheckedChange = model::toggleConsentReminder,
    )
    // docs/14 "권한": there is no prompt, so silence is all that is recorded while this is off — and
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

@Composable
private fun Data(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_DATA])
    SettingsCard {
        // docs/09: a path is data, so it is monospace and it is shown rather than described.
        Mono(model.dataDir)
        BlueprintButton(strings[Str.SETTINGS_OPEN_FOLDER], model::openDataDir, tone = ButtonTone.QUIET)
    }
    HairLine()
}

/**
 * docs/05 "워크플로우 내보내기 · 가져오기": definitions are this PC's own, so moving them to another
 * device is a file the user carries — and the hint says what the file does *not* carry with it.
 */
@Composable
private fun Workflows(model: ShellModel, strings: Strings) {
    val palette = blueprint
    Section(strings[Str.SETTINGS_WORKFLOWS])
    SettingsCard {
        Text(
            strings[Str.SETTINGS_WORKFLOWS_KEYS_HINT],
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ProcessingButton(
                label = strings[Str.SETTINGS_EXPORT_WORKFLOWS],
                state = model.action,
                strings = strings,
                onClick = model::exportWorkflows,
            )
            ProcessingButton(
                label = strings[Str.SETTINGS_IMPORT_WORKFLOWS],
                state = model.action,
                strings = strings,
                onClick = model::importWorkflows,
            )
        }
    }
    HairLine()
}

/** docs/09 트렌드 6: no mascot and no "handmade" line — what this build actually is, in monospace. */
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

/** A row of the choices for a setting that has three of them and will not grow — the theme. */
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
