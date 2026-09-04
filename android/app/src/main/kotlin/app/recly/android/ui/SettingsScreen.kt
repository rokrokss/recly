@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.recly.android.BuildConfig
import app.recly.android.R
import app.recly.android.settings.AppLanguage
import app.recly.android.settings.AppTheme
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintCheckRow
import app.recly.android.ui.component.BlueprintChip
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogLink
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintRadioRow
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.SwitchRow
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.android.ui.component.TableRow
import kotlin.time.ExperimentalTime

/**
 * docs/11 A10 as docs/09 화면 원칙 4 draws it: a section table — account, language, capture,
 * uploads, workflows — closed by an honest block of what this build actually is.
 */
@Composable
fun SettingsScreen(
    main: MainUiState,
    settings: SettingsUiState,
    onWifiOnly: (Boolean) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onTheme: (AppTheme) -> Unit,
    onConsentReminder: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onReauthorize: () -> Unit,
    onSignOut: () -> Unit,
    onAskToDisconnect: () -> Unit,
    onCancelDisconnect: () -> Unit,
    onDisconnect: (Boolean) -> Unit,
    onRevokeDebtSettled: () -> Unit,
    onExportWorkflows: () -> Unit,
    onImportWorkflows: () -> Unit,
    onCancelImport: () -> Unit,
    onConfirmImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    main.disconnect?.let { prompt ->
        DisconnectDialog(prompt = prompt, onCancel = onCancelDisconnect, onConfirm = onDisconnect)
    }
    settings.transfer.confirm?.let { picked ->
        ImportDialog(picked = picked, onCancel = onCancelImport, onConfirm = onConfirmImport)
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.tab_settings))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_account))
            // docs/06: while a disconnect is still owed the account slot is not free, and the row
            // says why rather than leaving a dead button.
            val signInBlocker = DisconnectGuard.signInBlocker(main.disconnectPhase.owed)
            if (main.email == null) {
                TableRow(
                    title = stringResource(R.string.signed_out),
                    subtitle = signInBlocker?.let { stringResource(it) },
                    trailing = {
                        ProcessingButton(
                            label = stringResource(R.string.sign_in),
                            state = main.action,
                            onClick = onSignIn,
                            tone = ButtonTone.PRIMARY,
                            enabled = !main.busy && !main.loading && signInBlocker == null,
                        )
                    },
                )
            } else {
                TableRow(
                    title = stringResource(R.string.settings_account),
                    subtitle = main.email,
                    trailing = {
                        BlueprintButton(
                            label = stringResource(R.string.sign_out),
                            onClick = onSignOut,
                            tone = ButtonTone.QUIET,
                            enabled = !main.busy && !main.disconnectPhase.owed,
                        )
                    },
                )
                TableRow(
                    title = stringResource(R.string.reauthorize_drive),
                    trailing = {
                        ProcessingButton(
                            label = stringResource(R.string.settings_sync_run),
                            state = main.action,
                            onClick = onReauthorize,
                            enabled = !main.busy,
                        )
                    },
                )
            }
            // docs/03 · docs/06: a second row and not a second meaning for the first one. Signing
            // out is this phone; disconnecting takes the grant away from every device. It outlives
            // the account when the local half failed — that retry is the only way to finish it.
            if (main.email != null || main.disconnectPhase.owed) {
                TableRow(
                    title = stringResource(R.string.settings_disconnect),
                    subtitle = stringResource(R.string.settings_disconnect_hint),
                    trailing = {
                        BlueprintButton(
                            label = stringResource(R.string.settings_disconnect),
                            onClick = onAskToDisconnect,
                            tone = ButtonTone.DANGER,
                            enabled = !main.busy,
                            modifier = Modifier.testTag("disconnect"),
                        )
                    },
                )
            }
            // docs/03: a revoke that failed leaves the grant standing, and it is Google's page —
            // not this app — that takes it down. So the row outlives the disconnect, the phase and
            // even the signed-in state, and only the user closes it.
            if (main.revokeDebt) {
                val context = LocalContext.current
                TableRow(
                    title = stringResource(R.string.disconnect_still_listed),
                    subtitle = GOOGLE_PERMISSIONS_URL,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            BlueprintButton(
                                label = stringResource(R.string.disconnect_permissions),
                                onClick = { context.openUrl(GOOGLE_PERMISSIONS_URL) },
                                tone = ButtonTone.PRIMARY,
                            )
                            BlueprintButton(
                                label = stringResource(R.string.disconnect_removed),
                                onClick = onRevokeDebtSettled,
                                tone = ButtonTone.QUIET,
                                modifier = Modifier.testTag("revoke-debt-settled"),
                            )
                        }
                    },
                    modifier = Modifier.testTag("revoke-debt"),
                )
            }
            main.message?.let {
                Text(
                    it.text(),
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }

            // docs/07 rule 2: a per-device choice. A row that names the language it is in, and the
            // choices behind it: the list of languages grows, and a row stays one line however long
            // that list gets.
            Section(stringResource(R.string.settings_language))
            var pickingLanguage by rememberSaveable { mutableStateOf(false) }
            // What the row says and the dialog marks is the language the app is in right now: with
            // nothing chosen the app follows the system, and the locale the resources resolved to is
            // the one the words on this very screen were loaded in.
            val language = AppLanguage.effective(LocalConfiguration.current.locales[0])
            TableRow(
                title = stringResource(R.string.settings_language),
                modifier = Modifier
                    .clickable(role = Role.Button) { pickingLanguage = true }
                    .testTag("language"),
                trailing = {
                    Text(
                        stringResource(language.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textMuted,
                    )
                },
            )
            if (pickingLanguage) {
                BlueprintDialog(
                    title = stringResource(R.string.settings_language),
                    onDismissRequest = { pickingLanguage = false },
                    actions = {
                        // Nothing to cancel: a choice is applied the moment it is made (rule 3), so
                        // the one answer here closes a question that has already been answered.
                        BlueprintButton(
                            label = stringResource(R.string.action_close),
                            onClick = { pickingLanguage = false },
                            tone = ButtonTone.QUIET,
                        )
                    },
                ) {
                    // Each language under its own name — a label that is never translated, so
                    // whoever cannot read the language the app is currently in can still find the
                    // one they want (docs/07 rule 1).
                    AppLanguage.choices.forEach { choice ->
                        BlueprintRadioRow(
                            label = stringResource(choice.labelRes()),
                            selected = language == choice,
                            onSelect = {
                                pickingLanguage = false
                                onLanguage(choice)
                            },
                            modifier = Modifier.testTag("language-${choice.name.lowercase()}"),
                        )
                    }
                }
            }

            // docs/09 "접근성": the system's dark mode is the default and the only one the app has
            // an opinion about — this is the user's override of it, on this device alone, exactly
            // as the PC's Settings window offers it.
            Section(stringResource(R.string.settings_theme))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = Space.m, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                AppTheme.entries.forEach { choice ->
                    BlueprintChip(
                        label = stringResource(choice.labelRes()),
                        selected = settings.theme == choice,
                        onClick = { onTheme(choice) },
                        modifier = Modifier.testTag("theme-${choice.name.lowercase()}"),
                    )
                }
            }

            Section(stringResource(R.string.settings_capture))
            // docs/13 deliverable 1: the microphone is what this app is for, and the system dialog
            // stops opening once it has been refused twice — so the way back on stands here
            // whether or not anything has been refused yet, as it does on the iPhone.
            val settingsContext = LocalContext.current
            TableRow(
                title = stringResource(R.string.settings_microphone),
                trailing = {
                    BlueprintButton(
                        label = stringResource(R.string.action_open_settings),
                        onClick = { settingsContext.openAppSettings() },
                        modifier = Modifier.testTag("microphone-settings"),
                    )
                },
            )
            // docs/12 M8: the Mac asks before every meeting recording. A phone has no meeting
            // detection, so it asks once before the first one — and the subtitle says so, because
            // a reminder that behaves differently on two devices has to explain itself.
            SwitchRow(
                title = stringResource(R.string.settings_consent_reminder),
                subtitle = stringResource(R.string.settings_consent_reminder_hint),
                checked = settings.consentReminder,
                onCheckedChange = onConsentReminder,
                modifier = Modifier.testTag("consent-reminder"),
            )

            Section(stringResource(R.string.settings_uploads))
            SwitchRow(
                title = stringResource(R.string.settings_wifi_only),
                subtitle = stringResource(R.string.settings_wifi_only_hint),
                checked = settings.wifiOnly,
                onCheckedChange = onWifiOnly,
            )

            // docs/05: definitions are this device's own, so moving them to another device is a
            // file the user carries — and the hint says what the file does *not* carry with it.
            Section(stringResource(R.string.settings_workflows))
            TransferSection(
                state = settings.transfer,
                onExport = onExportWorkflows,
                onImport = onImportWorkflows,
            )

            // docs/09 트렌드 6: no mascot, no "handmade" line — the build, in monospace.
            Section(stringResource(R.string.settings_about))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = Space.m, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                About(
                    stringResource(
                        R.string.settings_about_app,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        Build.VERSION.SDK_INT,
                    ),
                )
                About(stringResource(R.string.settings_about_device, rememberDeviceId()))
                About(stringResource(R.string.settings_open_source))
                About(stringResource(R.string.settings_open_source_value))
            }
            HairLine()
        }
    }
}

/**
 * docs/05 "워크플로우 내보내기 · 가져오기": the two buttons, and above them the one thing about the
 * file a user has to know before they carry it anywhere — the keys are not in it.
 */
@Composable
private fun TransferSection(
    state: WorkflowTransferUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val palette = blueprint
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = Space.m, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(
            stringResource(R.string.settings_workflows_keys_hint),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ProcessingButton(
                label = stringResource(R.string.settings_export_workflows),
                state = state.exporting,
                onClick = onExport,
                modifier = Modifier.testTag("export-workflows"),
            )
            ProcessingButton(
                label = stringResource(R.string.settings_import_workflows),
                state = state.importing,
                onClick = onImport,
                modifier = Modifier.testTag("import-workflows"),
            )
        }
    }
    // docs/09 화면 원칙 5: what happened is said where it happened, under the thing that did it.
    state.message?.let {
        Text(
            it.text(),
            modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.failed) palette.danger else palette.textMuted,
        )
    }
    HairLine()
}

/**
 * docs/05 "워크플로우 가져오기": there is no merge, so the one question worth asking is asked before
 * anything is written — and it is asked with the number the file actually holds.
 */
@Composable
private fun ImportDialog(picked: PickedWorkflows, onCancel: () -> Unit, onConfirm: () -> Unit) {
    BlueprintDialog(
        title = stringResource(R.string.workflows_import_title),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                tone = ButtonTone.QUIET,
            )
            BlueprintButton(
                label = stringResource(R.string.settings_import_workflows),
                onClick = onConfirm,
                tone = ButtonTone.DANGER,
                modifier = Modifier.testTag("import-confirm"),
            )
        },
    ) {
        BlueprintDialogText(
            stringResource(R.string.workflows_import_body, picked.workflows),
            tone = DialogTone.DANGER,
        )
        BlueprintDialogText(stringResource(R.string.settings_workflows_keys_hint))
    }
}

/**
 * docs/03 "로그아웃 vs 연결 해제": the things that are true of a disconnect and are not true of a
 * sign-out, and the one separate question — the recordings, which this never takes by default
 * (principle 3: nothing is deleted before it has been acknowledged somewhere else).
 */
@Composable
private fun DisconnectDialog(
    prompt: DisconnectPrompt,
    onCancel: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var alsoDelete by rememberSaveable { mutableStateOf(false) }
    BlueprintDialog(
        title = stringResource(R.string.disconnect_title),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                tone = ButtonTone.QUIET,
            )
            BlueprintButton(
                label = stringResource(R.string.settings_disconnect),
                onClick = { onConfirm(alsoDelete) },
                tone = ButtonTone.DANGER,
                enabled = prompt.canConfirm,
                modifier = Modifier.testTag("disconnect-confirm"),
            )
        },
    ) {
        // docs/03: what a disconnect takes away. Only the audio that exists nowhere else takes the
        // record red — several red paragraphs would leave the colour meaning nothing, and this is
        // the same line the delete dialog puts in red for the same reason.
        BlueprintDialogText(stringResource(R.string.disconnect_other_devices))
        // Nothing on this phone alone is nothing to warn about: the line is the audio Drive has
        // never seen, and at zero it would be a red sentence about no recordings at all (the same
        // guard the delete dialog and both Apple shells have).
        if (prompt.unuploaded > 0) {
            BlueprintDialogText(
                pluralStringResource(
                    R.plurals.disconnect_unuploaded,
                    prompt.unuploaded,
                    prompt.unuploaded,
                ),
                tone = DialogTone.DANGER,
                modifier = Modifier.testTag("disconnect-unuploaded"),
            )
        }
        BlueprintDialogText(stringResource(R.string.disconnect_local))
        // docs/12: a capture that is running has no job yet, so the core's Busy guard does not
        // cover it. Say what is in the way; never stop it for them.
        prompt.blocker?.let { BlueprintDialogText(stringResource(it), tone = DialogTone.DANGER) }
        BlueprintCheckRow(
            label = stringResource(R.string.disconnect_also_delete),
            checked = alsoDelete,
            onCheckedChange = { alsoDelete = it },
            modifier = Modifier.testTag("disconnect-also-delete"),
        )
        // docs/03: a user who only wants this one device off the account has another way, and it
        // is Google's own page rather than anything this app can do for them.
        BlueprintDialogLink(
            label = stringResource(R.string.disconnect_permissions),
            onClick = { context.openUrl(GOOGLE_PERMISSIONS_URL) },
        )
    }
}

/** docs/03: where a user takes the grant away themselves, which the dialog has to point at. */
private const val GOOGLE_PERMISSIONS_URL = "https://myaccount.google.com/permissions"

@Composable
private fun Section(title: String) {
    SectionHeader(title, Modifier.padding(horizontal = Space.m))
    HairLine()
}

@Composable
private fun About(line: String) {
    Text(line, style = mono.small, color = blueprint.textMuted)
}

/**
 * The language's own name. Only the two [AppLanguage.choices] offers are ever drawn:
 * [AppLanguage.SYSTEM] is the store's "nothing chosen" and the screen names the language the app
 * resolved to instead.
 */
private fun AppLanguage.labelRes(): Int =
    if (this == AppLanguage.KOREAN) R.string.settings_language_ko else R.string.settings_language_en

/** docs/09 "접근성": the three answers the theme setting offers, the PC's own three. */
private fun AppTheme.labelRes(): Int = when (this) {
    AppTheme.SYSTEM -> R.string.theme_system
    AppTheme.LIGHT -> R.string.theme_light
    AppTheme.DARK -> R.string.theme_dark
}
