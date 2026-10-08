@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalTime::class)

package app.recly.android.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.recly.android.BuildConfig
import app.recly.android.R
import app.recly.android.settings.AppLanguage
import app.recly.android.settings.AppTheme
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintChip
import app.recly.android.ui.component.FillRow
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogLink
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintDropdown
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ProcessingButton
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.SectionFootnote
import app.recly.android.ui.component.SwitchRow
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.android.ui.component.TableRow
import java.util.Locale
import kotlin.time.ExperimentalTime
import recly.core.storage.StorageKind

/**
 * docs/11 A10 as docs/09 screen principle 4 draws it: a section table — account, language, theme, capture,
 * uploads, processing, privacy — closed by an honest block of what this build actually is.
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
    onAskToDisconnect: () -> Unit,
    onCancelDisconnect: () -> Unit,
    onDisconnect: (Boolean) -> Unit,
    onRevokeDebtSettled: () -> Unit,
    onStorage: (StorageKind) -> Unit,
    onPickFolder: (Uri) -> Unit,
    onRefreshStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    main.disconnect?.let { prompt ->
        DisconnectDialog(prompt = prompt, onCancel = onCancelDisconnect, onConfirm = onDisconnect)
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.tab_settings))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            // docs/03 "Storage location": where new recordings go, in chips as the theme is, and under
            // them the chosen storage's rows alone — Drive's as they always were, or the folder's one.
            // Drive stays connected after a switch, and its rows, with Disconnect, are back under its chip.
            Section(stringResource(R.string.settings_storage))
            LaunchedEffect(Unit) { onRefreshStorage() }
            // Outside the branch below, so a pick the system is still showing lands whatever is drawn.
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                tree?.let(onPickFolder)
            }
            FillRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = Space.m, vertical = 12.dp),
            ) {
                // A product name, never translated (docs/07 rule 1).
                BlueprintChip(
                    label = stringResource(R.string.settings_account),
                    selected = settings.storage != StorageKind.FOLDER,
                    onClick = { onStorage(StorageKind.DRIVE) },
                    enabled = !settings.storageBusy,
                    modifier = Modifier.testTag("storage-drive"),
                )
                BlueprintChip(
                    label = stringResource(R.string.storage_local_folder),
                    selected = settings.storage == StorageKind.FOLDER,
                    onClick = { onStorage(StorageKind.FOLDER) },
                    enabled = !settings.storageBusy,
                    modifier = Modifier.testTag("storage-folder"),
                )
            }
            val context = LocalContext.current
            val signInBlocker = DisconnectGuard.signInBlocker(main.disconnectPhase.owed)
            if (settings.storage == StorageKind.FOLDER) {
                // Drive's row in shape: where the folder is and the way to change it, or that there is
                // none to use and the way to pick one. The name is what the user picked it by.
                val reachable = settings.folder != null && settings.folderReachable
                TableRow(
                    title = settings.folder ?: stringResource(R.string.folder_none),
                    subtitle = if (settings.folder != null && !reachable) stringResource(R.string.folder_unreachable) else null,
                    modifier = Modifier.testTag("folder-status"),
                    trailing = {
                        BlueprintButton(
                            label = stringResource(if (reachable) R.string.folder_change else R.string.folder_choose),
                            onClick = { folderPicker.launch(null) },
                            // A folder in use only needs a way to change it; one that cannot be used
                            // is what stands between the recordings and their copy.
                            tone = if (reachable) ButtonTone.QUIET else ButtonTone.PRIMARY,
                            modifier = Modifier.testTag("folder-choose"),
                        )
                    },
                )
            } else if (main.driveConnected || main.disconnectPhase.owed || main.disconnecting) {
                // docs/06 Android: an account without the Drive grant — a consent screen that was
                // closed — is not connected, and the row says so with the way to connect, as the
                // iPhone's does. Only a disconnect still owed keeps the disconnect row without one.
                TableRow(title = main.email ?: stringResource(if (main.disconnecting) R.string.settings_account else R.string.drive_attention), trailing = {
                    // Quiet, not red: red is a deletion or the recording (UX decisions of 2026-10-08).
                    BlueprintButton(
                        stringResource(if (main.disconnecting) R.string.drive_disconnecting else R.string.drive_disconnect),
                        onClick = onAskToDisconnect, tone = ButtonTone.QUIET,
                        enabled = !main.busy && !main.disconnecting, modifier = Modifier.testTag("disconnect"))
                })
            } else {
                TableRow(
                    title = stringResource(R.string.signed_out),
                    subtitle = stringResource(R.string.drive_optional),
                    trailing = {
                        // An accent outline, as on the iPhone; the filled button is the Record tab's model card.
                        ProcessingButton(label = stringResource(R.string.drive_connect),
                            state = main.action, onClick = onSignIn,
                            tone = ButtonTone.ACCENT,
                            enabled = !main.busy && !main.loading && signInBlocker == null)
                    },
                )
            }
            if (settings.storage != StorageKind.FOLDER && main.revokeDebt && !main.disconnecting) {
                SectionFootnote(stringResource(R.string.disconnect_still_listed))
                BlueprintDialogLink(stringResource(R.string.disconnect_permissions), onClick = {
                    context.openUrl(GOOGLE_PERMISSIONS_URL)
                }, modifier = Modifier.padding(horizontal = Space.m))
                // docs/09 screen principle 8: the answer to the notice above it, end-aligned under it.
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                    BlueprintButton(stringResource(R.string.disconnect_removed), onRevokeDebtSettled,
                        tone = ButtonTone.QUIET,
                        modifier = Modifier.testTag("revoke-debt-settled"))
                }
            }
            // iPhone's `authNote`: a connection that failed, in the failure colour (docs/09 — red is
            // a failure). A closed picker or consent screen is the user's answer and says nothing.
            main.authNote?.takeIf { settings.storage != StorageKind.FOLDER }?.let {
                Text(
                    it.text(),
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                )
            }
            main.message?.takeIf { settings.storage != StorageKind.FOLDER }?.let {
                Text(
                    it.text(),
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
            settings.storageMessage?.let { SectionFootnote(it.text()) }

            // docs/07 rule 2: a per-device choice. A row that names the language it is in, and the
            // choices behind it: the list of languages grows, and a row stays one line however long
            // that list gets.
            Section(stringResource(R.string.settings_language))
            // What the row says and the menu marks is the language the app is in right now: with
            // nothing chosen the app follows the system, and the locale the resources resolved to is
            // the one the words on this very screen were loaded in.
            val language = AppLanguage.effective(LocalConfiguration.current.locales[0])
            TableRow(
                title = stringResource(R.string.settings_app_language),
                trailing = {
                    // Each language under its own name — a label that is never translated, so
                    // whoever cannot read the language the app is currently in can still find the
                    // one they want (docs/07 rule 1). A choice is applied the moment it is made
                    // (rule 3), so there is nothing to confirm.
                    BlueprintDropdown(
                        label = stringResource(R.string.settings_app_language),
                        options = AppLanguage.choices,
                        selected = language,
                        onSelect = onLanguage,
                        modifier = Modifier.testTag("language"),
                        title = { stringResource(it.labelRes()) },
                    )
                },
            )

            // docs/09 "Accessibility": the system's dark mode is the default and the only one the app has
            // an opinion about — this is the user's override of it, on this device alone, exactly
            // as the PC's Settings window offers it.
            Section(stringResource(R.string.settings_theme))
            FillRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = Space.m, vertical = 12.dp),
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
                        // Always here, so nothing to call attention to: the Record screen's own
                        // accent button is the one a refusal brings (2026-09-29).
                        tone = ButtonTone.QUIET,
                        modifier = Modifier.testTag("microphone-settings"),
                    )
                },
            )
            // docs/12 M8: the Mac asks before every meeting recording. A phone has no meeting
            // detection, so it asks once before the first one — and the subtitle says so, because
            // a reminder that behaves differently on two devices has to explain itself.
            SwitchRow(
                title = stringResource(R.string.settings_consent_reminder),
                checked = settings.consentReminder,
                onCheckedChange = onConsentReminder,
                modifier = Modifier.testTag("consent-reminder"),
            )

            Section(stringResource(R.string.settings_uploads))
            SwitchRow(
                title = stringResource(R.string.settings_wifi_only),
                checked = settings.wifiOnly,
                onCheckedChange = onWifiOnly,
            )

            ProcessingPanel()

            // docs/15 "Policy pages the user opens": Recly's own privacy policy, in the browser, right
            // before About as on the iPhone. Only the policy: the iPhone's "Allowed destinations" is
            // its transfer permission (docs/15), which this shell does not ask for.
            Section(stringResource(R.string.settings_privacy))
            val policyLocale = LocalConfiguration.current.locales[0]
            TableRow(
                title = stringResource(R.string.settings_privacy_policy),
                trailing = {
                    BlueprintButton(
                        label = stringResource(R.string.action_open),
                        onClick = { context.openUrl(privacyPolicyUrl(policyLocale)) },
                        // A way out that is always here, like the microphone's: quiet.
                        tone = ButtonTone.QUIET,
                        modifier = Modifier.testTag("privacy-policy"),
                    )
                },
            )

            // docs/09 trend 6: no mascot, no "handmade" line — the build, in monospace.
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
                // A heading in words over the list it heads, so in the sans: only the build itself
                // is monospace, as on the iPhone.
                Text(stringResource(R.string.settings_open_source), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                About(stringResource(R.string.settings_open_source_value))
            }
            HairLine()
        }
    }
}

/**
 * docs/03 "Sign out vs Disconnect": revocation can affect other devices and clears this phone's
 * upload queue. Recordings, settings and keys stay; deleting audio is a separate list action.
 */
@Composable
private fun DisconnectDialog(
    prompt: DisconnectPrompt,
    onCancel: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    BlueprintDialog(
        title = stringResource(R.string.disconnect_title),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                tone = ButtonTone.QUIET,
                minWidth = MinTouch,
            )
            // Not red: nothing is deleted — recordings and settings stay (UX decisions of 2026-10-08).
            BlueprintButton(
                label = stringResource(R.string.settings_disconnect),
                onClick = { onConfirm(false) },
                tone = ButtonTone.PRIMARY,
                enabled = prompt.canConfirm,
                modifier = Modifier.testTag("disconnect-confirm"),
            )
        },
    ) {
        BlueprintDialogText(stringResource(R.string.disconnect_other_devices), tone = DialogTone.MUTED)
        // docs/12: a capture that is running has no job yet, so the core's Busy guard does not
        // cover it. Say what is in the way; never stop it for them.
        prompt.blocker?.let { BlueprintDialogText(stringResource(it), tone = DialogTone.DANGER) }
    }
}

/** docs/03: where a user takes the grant away themselves, linked from Drive settings. */
private const val GOOGLE_PERMISSIONS_URL = "https://myaccount.google.com/permissions"

/**
 * docs/15 "Policy pages the user opens": the iPhone's `PrivacyLinks.recly` — the Korean page when the app
 * is in Korean, the English one in every other language.
 */
internal fun privacyPolicyUrl(locale: Locale): String =
    "https://recly.dev/policy/" + if (locale.language == "ko") "privacy-policy.ko" else "privacy-policy"

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
    when (this) {
        AppLanguage.ENGLISH -> R.string.settings_language_en
        AppLanguage.KOREAN -> R.string.settings_language_ko
        AppLanguage.JAPANESE -> R.string.settings_language_ja
        AppLanguage.CHINESE_SIMPLIFIED -> R.string.settings_language_zh_hans
        AppLanguage.CHINESE_TRADITIONAL -> R.string.settings_language_zh_hant
        AppLanguage.SPANISH -> R.string.settings_language_es
        AppLanguage.FRENCH -> R.string.settings_language_fr
        AppLanguage.GERMAN -> R.string.settings_language_de
        AppLanguage.PORTUGUESE_BRAZIL -> R.string.settings_language_pt_br
        AppLanguage.PORTUGUESE_PORTUGAL -> R.string.settings_language_pt_pt
        AppLanguage.ARABIC -> R.string.settings_language_ar
        AppLanguage.HINDI -> R.string.settings_language_hi
        AppLanguage.RUSSIAN -> R.string.settings_language_ru
        AppLanguage.ITALIAN -> R.string.settings_language_it
        AppLanguage.POLISH -> R.string.settings_language_pl
        AppLanguage.TURKISH -> R.string.settings_language_tr
        AppLanguage.FILIPINO -> R.string.settings_language_fil
        AppLanguage.BENGALI -> R.string.settings_language_bn
        AppLanguage.URDU -> R.string.settings_language_ur
        AppLanguage.SWAHILI -> R.string.settings_language_sw
        AppLanguage.VIETNAMESE -> R.string.settings_language_vi
        AppLanguage.PERSIAN -> R.string.settings_language_fa
        AppLanguage.THAI -> R.string.settings_language_th
        AppLanguage.SYSTEM -> R.string.settings_language_en
    }

/** docs/09 "Accessibility": the three answers the theme setting offers, the PC's own three. */
private fun AppTheme.labelRes(): Int = when (this) {
    AppTheme.SYSTEM -> R.string.theme_system
    AppTheme.LIGHT -> R.string.theme_light
    AppTheme.DARK -> R.string.theme_dark
}
