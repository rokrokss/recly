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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.detect.MicAccess
import app.recly.windows.detect.MicrophoneAccess
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.text
import app.recly.windows.settings.AppTheme
import app.recly.windows.ui.component.TextLink
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.BlueprintDropdown
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.ProcessingButton
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.SectionHeader
import app.recly.windows.ui.component.SectionFootnote
import app.recly.windows.ui.component.SwitchRow
import app.recly.windows.ui.component.TableRow
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import app.recly.windows.ui.theme.ProcessingState
import kotlin.time.ExperimentalTime
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.SummaryFormat
import recly.core.chatgpt.SummaryPreferences
import recly.core.storage.StorageKind

/**
 * docs/09 screen principle 4, over docs/14 "App": a section table — the storage and its account (docs/03,
 * docs/06), the language (docs/07), the theme override (docs/09 "Accessibility": motion and contrast are
 * the system's alone, and there is no accessibility section), capture with the start at login (as the Mac
 * has it, 2026-10-10), and the honest block of what this build actually is.
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
            model.processing?.let { ProcessingPanel(it, strings, preparationAllowed = !model.recording && model.transition == null) }
            model.chatGpt?.let { ChatGpt(it, strings) }
            model.processing?.let { ProcessingSettingsFile(it, strings) }
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
            // Quiet, not red (2026-10-08): red is for what cannot be undone, and Drive can be connected again.
            BlueprintButton(strings[if (model.disconnecting) Str.DRIVE_DISCONNECTING else Str.DRIVE_DISCONNECT],
                model::askToDisconnect, tone = ButtonTone.QUIET, enabled = !model.disconnecting)
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
        TextLink(strings[Str.DISCONNECT_PERMISSIONS], model::openAccountPermissions,
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
 * docs/14 "Detection" · ADR-011: detect, ask, record — automatic recording is the user's to turn on. A
 * capture helper that works says nothing here; one that is missing or silent on Windows is one line that
 * says what to do, and the rest of what is known about it is in the log (`shell.ready`).
 */
@Composable
private fun Capture(model: ShellModel, strings: Strings) {
    Section(strings[Str.SETTINGS_RECORDING])
    // docs/12 "Runner": first in the section, as on the Mac — a section of its own for one switch is gone (2026-10-10).
    SwitchRow(
        title = strings[Str.SETTINGS_LAUNCH_AT_LOGIN],
        subtitle = if (model.launchAtLoginSupported) null else strings[Str.SETTINGS_LAUNCH_UNSUPPORTED],
        checked = model.launchAtLogin,
        onCheckedChange = model::toggleLaunchAtLogin,
        enabled = model.launchAtLoginSupported,
    )
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
    if (model.helperUnavailable) {
        SettingsCard {
            // A message, at 12 in its tone, as every message in Settings (2026-10-10).
            Text(strings[Str.HELPER_UNAVAILABLE], style = MaterialTheme.typography.bodySmall, color = blueprint.warningInk)
        }
        HairLine()
    }
}

/**
 * docs/09 "Summary view" · docs/15 §10: the user's own ChatGPT plan, for summaries — in the Drive rows' shape, with
 * the Drive connect button's tones. Nothing at all where it is not offered.
 */
@Composable
private fun ChatGpt(chatGpt: ChatGptViewModel, strings: Strings) {
    // Once each time the window opens: the plan's models are a request to OpenAI.
    LaunchedEffect(Unit) { chatGpt.refresh() }
    val connection = chatGpt.connection
    if (connection == ChatGptConnection.Unavailable) return
    Section(strings[Str.CHATGPT_TITLE])
    val signIn: @Composable () -> Unit = {
        // Disabled rather than offered twice: a window reopened in the middle of a sign-in has a new button.
        ProcessingButton(label = strings[Str.CHATGPT_CONTINUE], state = chatGpt.action, strings = strings,
            onClick = chatGpt::signIn, tone = ButtonTone.PRIMARY, enabled = chatGpt.action != ProcessingState.PROCESSING)
    }
    when (connection) {
        ChatGptConnection.Unavailable -> Unit
        ChatGptConnection.SignedOut ->
            TableRow(title = strings[Str.CHATGPT_USE_PLAN], subtitle = strings[Str.CHATGPT_USE_PLAN_NOTE], trailing = signIn)
        is ChatGptConnection.Expired ->
            TableRow(title = connection.account, subtitle = strings[Str.CHATGPT_EXPIRED], subtitleColor = blueprint.warningInk,
                trailing = signIn)
        is ChatGptConnection.SignedIn -> {
            // ChatGPT's usage page is a web page, so it is a link under the plan line rather than a row of its own.
            TableRow(
                title = connection.account,
                subtitle = strings[Str.CHATGPT_USING_PLAN],
                below = { TextLink(strings[Str.CHATGPT_MANAGE_USAGE], chatGpt::openUsage, style = MaterialTheme.typography.bodySmall) },
                trailing = { BlueprintButton(strings[Str.CHATGPT_SIGN_OUT], chatGpt::signOut, tone = ButtonTone.QUIET) },
            )
            if (connection.models.isNotEmpty()) {
                TableRow(title = strings[Str.CHATGPT_MODEL], trailing = {
                    BlueprintDropdown(
                        label = strings[Str.CHATGPT_MODEL],
                        options = connection.models.map { it.id to it.label },
                        selected = connection.model.orEmpty(),
                        onSelect = chatGpt::selectModel,
                    )
                })
            }
        }
    }
    if (chatGpt.listening) {
        SettingsCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(strings[Str.CHATGPT_FINISH_IN_BROWSER], style = MaterialTheme.typography.bodyMedium, color = blueprint.text,
                    modifier = Modifier.weight(1f))
                BlueprintButton(strings[Str.CANCEL], chatGpt::cancelSignIn, tone = ButtonTone.QUIET)
            }
        }
        HairLine()
    }
    when (val notice = chatGpt.notice) {
        is ChatGptNotice.SignInFailed -> {
            SettingsCard(spacing = 2.dp) {
                Text(strings[Str.CHATGPT_SIGN_IN_FAILED], style = MaterialTheme.typography.bodySmall, color = blueprint.danger)
                // A diagnostic is data, in monospace as the ledger shows one; a sentence is a sentence.
                signInFailureLine(notice.reason)?.let {
                    Text(it.text(strings), style = if (it is UiMessage.Text) mono.small.copy(textDirection = TextDirection.Ltr) else MaterialTheme.typography.bodySmall,
                        color = blueprint.textMuted)
                }
            }
            HairLine()
        }
        // Not red: this PC holds nothing any more, and ChatGPT's settings can remove Recly.
        ChatGptNotice.RevokeUnconfirmed -> {
            SettingsCard {
                Text(strings[Str.CHATGPT_REVOKE_UNCONFIRMED], style = MaterialTheme.typography.bodySmall, color = blueprint.warningInk)
            }
            HairLine()
        }
        null -> Unit
    }
    SummaryPreferenceRows(chatGpt, strings)
    SectionFootnote(strings[Str.CHATGPT_FOOTNOTE])
}

/**
 * docs/08 "Summaries": what every summary on this PC is shaped as and who it is for — under the model, and there
 * signed out too, since only running a summary needs the sign-in. The format is saved when it is chosen; the two
 * texts when their field is left.
 *
 * Shorter since 2026-10-10: the format offers all five always, and My format's field is there only while the format
 * is My format — taking the cursor when it is chosen with nothing written yet, and saying until then that summaries
 * use General.
 */
@Composable
private fun SummaryPreferenceRows(chatGpt: ChatGptViewModel, strings: Strings) {
    val preferences = chatGpt.preferences
    val customFocus = remember { FocusRequester() }
    var focusCustom by remember { mutableStateOf(false) }
    TableRow(title = strings[Str.SUMMARY_FORMAT], trailing = {
        BlueprintDropdown(
            label = strings[Str.SUMMARY_FORMAT],
            options = SummaryFormat.entries.map { it to strings[summaryFormatLabel(it)] },
            selected = preferences.format,
            onSelect = { format ->
                focusCustom = format == SummaryFormat.CUSTOM && preferences.customFormat.isBlank()
                chatGpt.selectFormat(format)
            },
        )
    })
    if (preferences.format == SummaryFormat.CUSTOM) {
        SettingsCard {
            PreferenceField(
                saved = preferences.customFormat,
                onSave = chatGpt::saveCustomFormat,
                label = strings[Str.SUMMARY_FORMAT_CUSTOM],
                placeholder = strings[Str.SUMMARY_CUSTOM_PLACEHOLDER],
                hint = strings[Str.SUMMARY_CUSTOM_NOTE],
                emptyHint = strings[Str.SUMMARY_CUSTOM_EMPTY],
                max = SummaryPreferences.CUSTOM_MAX,
                returnSaves = false,
                lines = CUSTOM_LINES,
                focusRequester = customFocus,
            )
        }
        HairLine()
        LaunchedEffect(focusCustom) {
            if (focusCustom) {
                runCatching { customFocus.requestFocus() }
                focusCustom = false
            }
        }
    }
    SettingsCard {
        PreferenceField(
            saved = preferences.aboutMe,
            onSave = chatGpt::saveAboutMe,
            label = strings[Str.SUMMARY_ABOUT],
            placeholder = strings[Str.SUMMARY_ABOUT_PLACEHOLDER],
            hint = strings[Str.SUMMARY_ABOUT_NOTE],
            max = SummaryPreferences.ABOUT_MAX,
            returnSaves = true,
            lines = ABOUT_LINES,
        )
    }
    HairLine()
}

/**
 * A text kept as it is typed and saved when the editing ends — the field left, Return where [returnSaves], the window
 * put behind another or closed — with no Save of its own. Empty is a value like any other, and [emptyHint] says what
 * an empty one means. It shows the first of [lines], grows to the last and then scrolls; where Return saves, a line
 * break pasted in is a space.
 */
@Composable
internal fun PreferenceField(
    saved: String,
    onSave: (String) -> Unit,
    label: String,
    placeholder: String,
    hint: String,
    max: Int,
    returnSaves: Boolean,
    lines: IntRange,
    emptyHint: String? = null,
    focusRequester: FocusRequester? = null,
) {
    var text by remember(saved) { mutableStateOf(saved) }
    val latest by rememberUpdatedState(text)
    val focus = LocalFocusManager.current
    // Going to another window ends the editing — a summary asked for there uses what was typed here — and so does
    // this one closing. Saving what is already kept saves nothing.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) onSave(latest) }
    DisposableEffect(Unit) { onDispose { onSave(latest) } }
    BlueprintTextField(
        value = text,
        onValueChange = { typed -> text = (if (returnSaves) typed.replace(LINE_BREAK, " ") else typed).take(max) },
        label = label,
        modifier = Modifier
            .onFocusChanged { if (!it.hasFocus && text != saved) onSave(text) }
            .onPreviewKeyEvent { event ->
                (returnSaves && event.type == KeyEventType.KeyDown && event.key == Key.Enter).also { if (it) focus.clearFocus() }
            },
        hint = if (text.isBlank() && emptyHint != null) emptyHint else hint,
        singleLine = lines.last == 1,
        minLines = lines.first,
        maxLines = lines.last,
        monospace = false,
        placeholder = placeholder,
        focusRequester = focusRequester,
    )
}

/**
 * docs/09 trend 6: no mascot and no "handmade" line — what this build actually is, in monospace — and at its end the
 * way to the folder this PC keeps it all in. The folder's path is not shown (2026-10-10): docs/09 keeps technical
 * values out of the UI, and the button opens it.
 */
@Composable
private fun About(model: ShellModel, strings: Strings) {
    val palette = blueprint
    Section(strings[Str.SETTINGS_ABOUT])
    SettingsCard(spacing = 2.dp) {
        // The version, and the build — the MSI's own install version — as the phones and the Macs show theirs (2026-10-10).
        Mono(strings[Str.SETTINGS_ABOUT_APP, "${OAuthConfig.APP_VERSION} (build ${OAuthConfig.BUILD})", system()])
        Mono(strings[Str.SETTINGS_ABOUT_DEVICE, model.deviceId])
        Text(
            strings[Str.SETTINGS_OPEN_SOURCE],
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
        Mono(strings[Str.SETTINGS_OPEN_SOURCE_VALUE])
        BlueprintButton(strings[Str.SETTINGS_OPEN_DATA_FOLDER], model::openDataDir, tone = ButtonTone.QUIET,
            modifier = Modifier.align(Alignment.End).padding(top = Space.s))
    }
    HairLine()
}

@Composable
private fun Mono(line: String) {
    // Data — the build, the device — left to right in every language (2026-10-10).
    Text(line, style = mono.small.copy(textDirection = TextDirection.Ltr), color = blueprint.textMuted)
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

/** My format: two lines open, eight before it scrolls; About you: one, up to three (2026-10-10). */
private val CUSTOM_LINES = 2..8
private val ABOUT_LINES = 1..3

private val LINE_BREAK = Regex("\\r\\n|\\r|\\n")

private fun system(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
