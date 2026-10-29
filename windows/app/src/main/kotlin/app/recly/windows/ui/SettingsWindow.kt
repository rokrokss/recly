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
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
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
import app.recly.windows.ui.component.ink
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import app.recly.windows.ui.theme.ProcessingState
import kotlin.time.ExperimentalTime
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.SummaryPreferences
import recly.core.notes.NotesPreferences
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
            model.chatGpt?.let {
                ChatGpt(it, strings)
                NotesSettings(it, strings)
            }
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
        // Off while a chip's save is in flight, so the chips never mark one the core does not hold yet.
        enabled = !model.storageBusy,
    )
    // Why the last chip did not take, under the chips, as the phone says it.
    model.storageMessage?.let { SectionFootnote(it.text(strings)) }
    if (model.storage == StorageKind.FOLDER) LocalFolderRow(model, strings) else Account(model, strings)
}

/**
 * The local folder in the Drive row's shape: where it is and a way to change it, nothing picked yet, or
 * a folder that cannot be reached any more and has to be picked again. The folder by its name; its path is a
 * technical value, so only the tooltip and a screen reader have it (2026-10-11, N8).
 */
@Composable
private fun LocalFolderRow(model: ShellModel, strings: Strings) {
    val folder = model.localFolder
    val reachable = folder != null && model.localFolderAvailable
    TableRow(
        title = folder?.let(::folderName) ?: strings[Str.STORAGE_NO_FOLDER],
        titleDetail = folder,
        subtitle = if (folder != null && !reachable) strings[Str.STORAGE_FOLDER_UNREACHABLE] else null,
        trailing = {
            BlueprintButton(
                strings[if (reachable) Str.STORAGE_CHANGE_FOLDER else Str.STORAGE_CHOOSE_FOLDER],
                model::chooseLocalFolder,
            )
        },
    )
}

/**
 * A folder's own name — the last part of its [path], with either separator — or the path itself where there is no
 * name, a drive's root (`D:\`).
 */
internal fun folderName(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
        .takeUnless { it.isEmpty() || it.endsWith(':') } ?: path

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
                // An accent outline, as on the phones (D26): the one filled button of the shell is the recording's.
                ProcessingButton(label = strings[Str.SIGN_IN], state = model.action, strings = strings,
                    onClick = model::signIn, tone = ButtonTone.ACCENT,
                    enabled = model.clientConfigured && signInBlocker == null)
            },
        )
    }
    // D21: what the last connect or disconnect came to, under the row that did it — a failure in the danger colour.
    model.driveNote?.let { note ->
        Text(
            note.message.text(strings),
            modifier = Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = Space.s),
            style = MaterialTheme.typography.bodySmall,
            color = note.tone.ink(),
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
        subtitle = strings[Str.SETTINGS_CONSENT_REMINDER_NOTE],
        checked = model.consentReminder,
        onCheckedChange = model::toggleConsentReminder,
    )
    // docs/14 "Permissions": there is no prompt, so silence is all that is recorded while the switch is off. The row is
    // always here, quiet, with the page that holds the switch (D5); while it is off it says where, in the warning ink.
    // Read again whenever this window comes to the front: the switch is flipped in Windows Settings, not here.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) model.refreshMicrophone() }
    TableRow(
        title = strings[Str.SETTINGS_MICROPHONE],
        subtitle = if (model.micAccess == MicAccess.DENIED) strings[MicrophoneAccess.GUIDANCE] else null,
        subtitleColor = if (model.micAccess == MicAccess.DENIED) blueprint.warningInk else null,
        trailing = {
            BlueprintButton(strings[Str.SETTINGS_OPEN_MICROPHONE], model::openMicrophoneSettings, tone = ButtonTone.QUIET)
        },
    )
    if (model.helperUnavailable) {
        SettingsCard {
            // A message, at 12 in its tone, as every message in Settings (2026-10-10).
            Text(strings[Str.HELPER_UNAVAILABLE], style = MaterialTheme.typography.bodySmall, color = blueprint.warningInk)
        }
        HairLine()
    }
}

/**
 * docs/09 "AI view" · docs/15 §10: the user's own ChatGPT plan, for notes and questions — in the Drive rows'
 * shape, with the Drive connect button's tones: the sign-in, Manage usage, the model and the footnote, and nothing of
 * what the notes are asked for, which is Settings → Notes' ([NotesSettings]). Nothing at all where it is not offered.
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
                BlueprintButton(strings[Str.CANCEL], chatGpt::cancelSignIn, tone = ButtonTone.QUIET, minWidth = MinTouch)
            }
        }
        HairLine()
    }
    when (val notice = chatGpt.notice) {
        is ChatGptNotice.SignInFailed -> {
            SettingsCard(spacing = Space.xxs) {
                Text(strings[Str.CHATGPT_SIGN_IN_FAILED], style = MaterialTheme.typography.bodySmall, color = blueprint.danger)
                // A diagnostic is data, in monospace as the ledger shows one; a sentence is a sentence.
                signInFailureLine(notice.reason)?.let {
                    Text(it.text(strings), style = if (it is UiMessage.Text) mono.small.copy(textDirection = TextDirection.Ltr) else MaterialTheme.typography.bodySmall,
                        color = blueprint.textMuted)
                }
            }
            HairLine()
        }
        // Not a warning: this PC holds nothing any more, and ChatGPT's settings can remove Recly. A quiet footnote
        // under the account row, as on the Mac (2026-10-11, N7).
        ChatGptNotice.RevokeUnconfirmed -> SectionFootnote(strings[Str.CHATGPT_REVOKE_UNCONFIRMED])
        null -> Unit
    }
    SectionFootnote(strings[Str.CHATGPT_FOOTNOTE])
}

/**
 * docs/09 "AI view": Settings → Notes, right after ChatGPT and there signed out too, since only organizing needs the
 * sign-in — what every recording's notes on this PC are asked for, whichever provider makes them: the instructions, up
 * to three sections of the user's own, and About you. Each field is saved when its editing ends, a section when the
 * cursor leaves it — one left without a name goes then — and Remove at once.
 */
@Composable
private fun NotesSettings(chatGpt: ChatGptViewModel, strings: Strings) {
    if (chatGpt.connection == ChatGptConnection.Unavailable) return
    Section(strings[Str.NOTES_TAB])
    SettingsCard {
        PreferenceField(
            saved = chatGpt.notesInstructions,
            onSave = chatGpt::saveNotesInstructions,
            label = strings[Str.NOTES_INSTRUCTIONS],
            placeholder = strings[Str.NOTES_INSTRUCTIONS_PLACEHOLDER],
            hint = strings[Str.NOTES_INSTRUCTIONS_NOTE],
            max = NotesPreferences.INSTRUCTIONS_MAX,
            returnSaves = false,
            lines = INSTRUCTIONS_LINES,
        )
    }
    HairLine()
    SettingsCard {
        Text(strings[Str.NOTES_SECTIONS], style = MaterialTheme.typography.labelSmall, color = blueprint.textMuted)
        // The section Add section made, whose name takes the cursor once it is drawn.
        var focusKey by remember { mutableStateOf<Int?>(null) }
        chatGpt.sections.forEach { section ->
            key(section.key) {
                SectionFields(section, chatGpt, strings, focus = focusKey == section.key, onFocused = { focusKey = null })
            }
        }
        if (chatGpt.sections.size < NotesPreferences.SECTIONS_MAX) {
            BlueprintButton(strings[Str.NOTES_SECTION_ADD], { focusKey = chatGpt.addSection() }, tone = ButtonTone.QUIET)
        }
        Text(strings[Str.NOTES_SECTIONS_NOTE], style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
    }
    HairLine()
    SettingsCard {
        PreferenceField(
            saved = chatGpt.preferences.aboutMe,
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
 * One of My sections: its name on one line — Return ends it — what goes in it on up to three, and a quiet Remove
 * that names it for a reader. Its editing ends when the cursor leaves both fields, the window goes behind another or
 * closes, and the section is saved then as it is ([ChatGptViewModel.endSection]).
 */
@Composable
private fun SectionFields(section: SectionDraft, chatGpt: ChatGptViewModel, strings: Strings, focus: Boolean, onFocused: () -> Unit) {
    var name by remember(section.name) { mutableStateOf(section.name) }
    var description by remember(section.description) { mutableStateOf(section.description) }
    val latestName by rememberUpdatedState(name)
    val latestDescription by rememberUpdatedState(description)
    val focusManager = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) chatGpt.endSection(section.key, latestName, latestDescription) }
    // Gone with the window; a section Remove took is not there any more, and nothing is saved for it.
    DisposableEffect(section.key) { onDispose { chatGpt.endSection(section.key, latestName, latestDescription) } }
    LaunchedEffect(focus) {
        if (focus) {
            runCatching { nameFocus.requestFocus() }
            onFocused()
        }
    }
    Column(
        Modifier.fillMaxWidth().onFocusChanged {
            if (editing && !it.hasFocus) chatGpt.endSection(section.key, latestName, latestDescription)
            editing = it.hasFocus
        },
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        BlueprintTextField(
            value = name,
            onValueChange = { typed -> name = typed.replace(LINE_BREAK, " ").take(NotesPreferences.SECTION_NAME_MAX) },
            label = strings[Str.NOTES_SECTION_NAME],
            modifier = Modifier.onPreviewKeyEvent { event ->
                (event.type == KeyEventType.KeyDown && event.key == Key.Enter).also { if (it) focusManager.clearFocus() }
            },
            placeholder = strings[Str.NOTES_SECTION_NAME_PLACEHOLDER],
            focusRequester = nameFocus,
        )
        BlueprintTextField(
            value = description,
            onValueChange = { description = it.take(NotesPreferences.DESCRIPTION_MAX) },
            label = strings[Str.NOTES_SECTION_DESCRIPTION],
            singleLine = false,
            minLines = DESCRIPTION_LINES.first,
            maxLines = DESCRIPTION_LINES.last,
            placeholder = strings[Str.NOTES_SECTION_DESCRIPTION_PLACEHOLDER],
        )
        BlueprintButton(
            strings[Str.NOTES_SECTION_REMOVE],
            { chatGpt.removeSection(section.key) },
            modifier = Modifier.align(Alignment.End).semantics {
                contentDescription = name.takeIf { it.isNotBlank() }?.let { strings[Str.VOCABULARY_REMOVE, it] } ?: strings[Str.NOTES_SECTION_REMOVE]
            },
            tone = ButtonTone.QUIET,
        )
    }
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
    // Going to another window ends the editing — notes asked for there use what was typed here — and so does
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
    SettingsCard(spacing = Space.xxs) {
        // The version, and the build — the MSI's own install version — as the phones and the Macs show theirs (2026-10-10).
        Mono(strings[Str.SETTINGS_ABOUT_APP, "${OAuthConfig.APP_VERSION} (build ${OAuthConfig.BUILD})", system()])
        Mono(strings[Str.SETTINGS_ABOUT_DEVICE, model.deviceId])
        Text(
            strings[Str.SETTINGS_OPEN_SOURCE],
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
        Mono(strings[Str.SETTINGS_OPEN_SOURCE_VALUE])
        // The privacy policy the phones link, in the app's language where there is one (D23).
        TextLink(strings[Str.SETTINGS_PRIVACY_POLICY], { model.openPrivacyPolicy(strings.language) },
            modifier = Modifier.padding(top = Space.s), style = MaterialTheme.typography.bodySmall)
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
            .padding(horizontal = Space.m, vertical = Space.sm),
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
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(blueprint.surface)
            .padding(horizontal = Space.m, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEach { (value, label) ->
            BlueprintChip(label = label, selected = selected == value, onClick = { onSelect(value) }, enabled = enabled)
        }
    }
    HairLine()
}

/** Notes instructions: two lines open, eight before it scrolls; a section's contents and About you: one, up to three. */
private val INSTRUCTIONS_LINES = 2..8
private val DESCRIPTION_LINES = 1..3
private val ABOUT_LINES = 1..3

private val LINE_BREAK = Regex("\\r\\n|\\r|\\n")

private fun system(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
