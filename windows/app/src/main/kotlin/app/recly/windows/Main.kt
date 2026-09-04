@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.windows

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.recly.windows.core.Host
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.settings.AppTheme
import app.recly.windows.ui.Consent
import app.recly.windows.ui.DeleteDialog
import app.recly.windows.ui.DevFlags
import app.recly.windows.ui.DisconnectDialog
import app.recly.windows.ui.ImportDialog
import app.recly.windows.ui.RecordingsWindow
import app.recly.windows.ui.RenameDialog
import app.recly.windows.ui.SettingsWindow
import app.recly.windows.ui.ShellModel
import app.recly.windows.ui.TrayEntry
import app.recly.windows.ui.TrayPopup
import app.recly.windows.ui.WorkflowDeleteDialog
import app.recly.windows.ui.WorkflowEditorWindow
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintCheckRow
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintDialogLink
import app.recly.windows.ui.component.BlueprintDialogText
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.DialogTone
import app.recly.windows.ui.devFlags
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.highContrastOf
import app.recly.windows.ui.theme.observeSystemHighContrast
import app.recly.windows.ui.trayMenu
import java.awt.KeyboardFocusManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * docs/14 N1 · deliverable 1: a tray app with no main window. The tray is the way in; docs/09 화면
 * 원칙 6 makes what it opens a Compose popup window rather than an AWT menu, because the state nodes,
 * the ledger and the workflow picker are shapes and an AWT menu item is one run of system text.
 *
 * The language table is collected here and handed down (docs/07 rule 3): a new one recomposes the
 * whole application, which is what rebuilds the AWT tray menu and retitles the open windows.
 */
fun main(args: Array<String>) {
    val dev = devFlags(args)
    application {
        val model = remember {
            ShellModel(
                localization = dev.language?.let { Localization(systemLanguage = { it }) } ?: Localization(),
            )
        }
        val strings by model.localization.strings.collectAsState()
        // A dialog is composed where no window is (`application {}`), so `ReclyDesktopTheme` cannot
        // be wrapped around the call — it is handed to the dialog and applied inside its own window.
        val themed: @Composable (@Composable () -> Unit) -> Unit = { body -> Themed(model, dev) { body() } }
        val scope = rememberCoroutineScope()
        val quit = { scope.launch { model.shutdown(); exitApplication() }; Unit }
        LaunchedEffect(model) { model.load() }
        LaunchedEffect(dev) {
            model.popupOpen = model.popupOpen || dev.popup
            model.editorOpen = model.editorOpen || dev.editor
            model.settingsOpen = model.settingsOpen || dev.settings
        }
        // The two that are not windows need the core open behind them, which `load` is still doing
        // when the flags are read: the disconnect warning counts recordings.
        //
        // Every one of them goes through a `preview…` entry rather than the live one, because all
        // three of these dialogs have a destructive answer on a machine that is a real install
        // (`DialogMode`): `--show-consent` was a `start`, which with the reminder switched off opens
        // a capture instead of raising anything.
        LaunchedEffect(dev, model.ready) {
            if (!model.ready) return@LaunchedEffect
            if (dev.disconnect && model.disconnectPrompt == null) model.previewDisconnect()
            if (dev.consent && model.consentRequest == null) model.previewConsent()
        }
        // The delete dialog is about a recording, so it waits for the ledger to have one.
        LaunchedEffect(dev, model.recents) {
            if (dev.delete && model.deleteRequest == null) {
                model.recents.firstOrNull()?.let { model.previewDelete(it) }
            }
        }

        // Read once: the taskbar's theme is a per-machine setting, and a tray icon that re-read
        // the registry on every recompose would be paying for a value that does not change.
        val lightTaskbar = remember { taskbarIsLight() }
        Tray(
            icon = StatusIcon(model.recording, model.alerts.isNotEmpty(), lightTaskbar),
            tooltip = strings[Str.TRAY_TOOLTIP, model.status.text(strings)],
            // A click on the tray icon opens the window; the menu is what is left when it cannot.
            onAction = { model.popupOpen = true },
            menu = { TrayMenu(model, strings, quit) },
        )

        if (model.popupOpen) {
            Window(
                onCloseRequest = { model.popupOpen = false },
                title = APP_NAME,
                state = rememberWindowState(
                    width = POPUP_WIDTH.dp,
                    height = POPUP_HEIGHT.dp,
                    position = WindowPosition.Aligned(trayCorner()),
                ),
                undecorated = true,
                resizable = false,
                alwaysOnTop = true,
                onKeyEvent = { event ->
                    (event.type == KeyEventType.KeyDown && event.key == Key.Escape)
                        .also { if (it) model.popupOpen = false }
                },
            ) {
                if (!dev.pinned) CloseWhenItLosesFocus { model.popupOpen = false }
                Themed(model, dev) { TrayPopup(model, strings, quit) }
            }
        }

        if (model.editorOpen) {
            Window(
                onCloseRequest = { model.editorOpen = false },
                title = strings[Str.WINDOW_WORKFLOWS],
                state = rememberWindowState(
                    width = (dev.editorWidth ?: EDITOR_WIDTH).dp,
                    height = EDITOR_HEIGHT.dp,
                ),
            ) {
                model.workflowsModel?.let { workflows ->
                    Themed(model, dev) {
                        WorkflowEditorWindow(
                            model = workflows,
                            strings = strings,
                            openFirst = dev.editor,
                            openStep = dev.step,
                        )
                    }
                }
            }
        }

        // docs/08 "결과 파일": what the transcribe step wrote, for the recordings the popup lists.
        if (model.recordingsOpen) {
            Window(
                onCloseRequest = { model.recordingsOpen = false },
                title = strings[Str.WINDOW_RECORDINGS],
                state = rememberWindowState(width = RECORDINGS_WIDTH.dp, height = RECORDINGS_HEIGHT.dp),
            ) {
                Themed(model, dev) { RecordingsWindow(model, strings) }
            }
        }

        // docs/03: the recording has ended and is waiting for its name before it becomes a job.
        model.titlePrompt?.let {
            TitlePrompt(model, strings, themed)
        }

        // docs/12 M8: the consent reminder before the first recording. The recording waits here, and
        // cancelling means no recording.
        if (model.consentRequest != null) {
            ConsentPrompt(model, strings, themed)
        }

        // docs/03 "앱에서 지우기" · "연결 해제": both are questions with a destructive answer, and both
        // are asked from here rather than from a window — the popup that opened one may be gone by
        // the time it is answered.
        model.deleteRequest?.let { request ->
            DeleteDialog(
                request = request,
                strings = strings,
                theme = themed,
                onCancel = model::cancelDelete,
                onDelete = model::delete,
            )
        }

        // docs/03: and the name of a recording, asked from the detail window it is shown in — from
        // here for the same reason, the window can be closed while the dialog is still up.
        model.renameRequest?.let { request ->
            RenameDialog(
                request = request,
                strings = strings,
                theme = themed,
                onCancel = model::cancelRename,
                onSave = model::rename,
            )
        }

        // docs/05 "워크플로우 가져오기": asked from here for the same reason — the settings window
        // that started the import may be closed by the time the file has been picked.
        model.workflowsModel?.importConfirm?.let { picked ->
            ImportDialog(
                picked = picked,
                strings = strings,
                theme = themed,
                onCancel = model::cancelImport,
                onConfirm = model::confirmImport,
            )
        }

        // ADR-016: and the same for a workflow delete, which is the other write with nothing behind
        // it — the document is this PC's own, so there is no copy anywhere to restore it from.
        model.workflowsModel?.let { workflows ->
            workflows.deleteConfirm?.let { item ->
                WorkflowDeleteDialog(
                    item = item,
                    strings = strings,
                    theme = themed,
                    onCancel = workflows::cancelDelete,
                    onDelete = { scope.launch { workflows.delete(item) } },
                )
            }
        }

        model.disconnectPrompt?.let { prompt ->
            DisconnectDialog(
                prompt = prompt,
                strings = strings,
                theme = themed,
                onCancel = model::cancelDisconnect,
                onPermissions = model::openAccountPermissions,
                onConfirm = model::disconnect,
            )
        }

        if (model.settingsOpen) {
            Window(
                onCloseRequest = { model.settingsOpen = false },
                title = strings[Str.WINDOW_SETTINGS],
                state = rememberWindowState(width = SETTINGS_WIDTH.dp, height = SETTINGS_HEIGHT.dp),
            ) {
                Themed(model, dev) { SettingsWindow(model, strings) }
            }
        }
    }
}

/**
 * docs/09: dark comes from the system unless the settings window overrides it, and high contrast
 * comes from the system alone. Every window is wrapped in this rather than one theme around the
 * application, because the type scale is interpolated from the width of the window it is in.
 */
@Composable
private fun Themed(model: ShellModel, dev: DevFlags, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val theme = dev.theme ?: model.theme
    // Observed, not read once: AWT fires a property change when the user flips the Windows
    // setting, and an open window follows it. Null off Windows.
    val systemContrast = observeSystemHighContrast()
    ReclyDesktopTheme(
        dark = when (theme) {
            AppTheme.SYSTEM -> systemDark
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        },
        highContrast = dev.highContrast ?: highContrastOf(systemContrast),
        content = content,
    )
}

/**
 * What makes it a popup rather than a window: clicking anywhere *else* puts it away — and else means
 * another application, not this one.
 *
 * The Mac drew the same line in e9838fb: opening Details, Settings, Workflows or the delete question
 * from the popover and then clicking in it took the popover with it, because a click on the app's own
 * window is still a click outside the popover. So the focus loss is not the answer on its own — what
 * took the focus is ([popupClosesOnFocusLoss]). Escape and the tray icon are unaffected: both close
 * it through `popupOpen` directly.
 *
 * Asked on a timer rather than once, because the focus loss is the last event this popup gets: the
 * window that took it keeps the focus while the user works in it, and the click that finally leaves
 * the application moves the focus between two windows that are not this one.
 */
@Composable
private fun FrameWindowScope.CloseWhenItLosesFocus(close: () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    // Only after it has *had* focus: a window is unfocused for the frame before the OS gives it to
    // it, and closing on that one would mean the popup never appears at all.
    var everFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            everFocused = true
            return@LaunchedEffect
        }
        if (!everFocused) return@LaunchedEffect
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        // Never read straight away: the focus is nowhere at all for a moment while the OS hands it
        // from one window to the next, and reading it during the handoff reads the gap.
        do {
            delay(FOCUS_SETTLES_MS)
        } while (!popupClosesOnFocusLoss(focus.activeWindow, window))
        close()
    }
}

/**
 * Whether a popup that has lost the focus should close: only when nothing of this app's own has it.
 * AWT's focus manager knows this JVM's windows and no others, so an active window that is not the
 * popup itself is one of ours — Details, Settings, Workflows, or a dialog raised over the popup — and
 * the popup stays where it is, the way the Mac's popover does.
 *
 * The two are compared by identity alone, which is why neither is typed `java.awt.Window`: a test
 * cannot build one of those without a display.
 */
internal fun popupClosesOnFocusLoss(active: Any?, popup: Any?): Boolean = active == null || active === popup

/** Long enough for the OS to have finished handing the focus over. */
private const val FOCUS_SETTLES_MS = 150L

/**
 * Which corner the tray icon is in. AWT does not report the icon's own position, so this is the
 * corner the platform keeps its tray in — the Windows taskbar's notification area is bottom-right,
 * the macOS menu bar is along the top.
 */
private fun trayCorner(): Alignment = if (Host.isWindows) Alignment.BottomEnd else Alignment.TopEnd

/** [trayMenu] decides what the menu says; this only puts it on the tray. */
@Composable
private fun MenuScope.TrayMenu(model: ShellModel, strings: Strings, quit: () -> Unit) {
    trayMenu(model, strings, quit).forEach { entry ->
        when (entry) {
            is TrayEntry.Item -> Item(entry.label, enabled = entry.enabled, onClick = entry.onClick)
            TrayEntry.Separator -> Separator()
        }
    }
}

@Composable
private fun TitlePrompt(
    model: ShellModel,
    strings: Strings,
    themed: @Composable (@Composable () -> Unit) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    // docs/03: nil is "unknown", which writes nothing at all rather than guessing.
    var participants by remember { mutableStateOf<Int?>(null) }
    BlueprintDialog(
        title = strings[Str.RECORDING_TITLE],
        // Closing the window is a skip — the job may not be left waiting on a window that is no
        // longer there.
        onDismissRequest = model::skipTitle,
        theme = themed,
        height = TITLE_HEIGHT.dp,
        actions = {
            BlueprintButton(strings[Str.SKIP], model::skipTitle, tone = ButtonTone.QUIET)
            BlueprintButton(
                label = strings[Str.SAVE],
                onClick = { model.saveTitle(title, participants) },
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
        // docs/03: and how many people were in the room — the hint the `transcribe` step trusts over
        // the workflow's own `speakers` (docs/08). The same question, in the same order, as the
        // phones and the Mac ask it.
        BlueprintDialogText(strings[Str.RECORDING_PARTICIPANTS], tone = DialogTone.MUTED)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            PARTICIPANT_CHOICES.forEach { choice ->
                BlueprintChip(
                    label = when (choice) {
                        null -> strings[Str.PARTICIPANTS_UNKNOWN]
                        // docs/08 caps the hint at 10 speakers; "6+" asks for six and lets the
                        // provider find more.
                        PARTICIPANTS_MANY -> strings[Str.PARTICIPANTS_MANY]
                        else -> choice.toString()
                    },
                    selected = participants == choice,
                    onClick = { participants = choice },
                )
            }
        }
    }
}

/** docs/03: 2 · 3 · 4 · 5 · 6+ · unknown, and unknown — `null` — is where the dialog starts. */
private val PARTICIPANT_CHOICES = listOf(null, 2, 3, 4, 5, 6)

private const val PARTICIPANTS_MANY = 6

/**
 * docs/12 M8 · ADR-011: the question every desktop recording is asked once, in the Mac's own words
 * ([Consent]). docs/09 화면 원칙 5 caps a dialog at two buttons, so the guidance is a link inside the
 * body rather than a third — and "do not ask again" is an option, not an answer.
 */
@Composable
private fun ConsentPrompt(
    model: ShellModel,
    strings: Strings,
    themed: @Composable (@Composable () -> Unit) -> Unit,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    BlueprintDialog(
        title = strings[Consent.QUESTION],
        // The window's close button is the cancel — this is a question, and "no" has to be possible.
        onDismissRequest = model::consentCancelled,
        theme = themed,
        width = CONSENT_WIDTH.dp,
        height = CONSENT_HEIGHT.dp,
        actions = {
            BlueprintButton(strings[Consent.CANCEL], model::consentCancelled, tone = ButtonTone.QUIET)
            BlueprintButton(
                label = strings[Consent.CONFIRM],
                onClick = { model.consentConfirmed(dontAskAgain) },
                tone = ButtonTone.PRIMARY,
            )
        },
    ) {
        // The jurisdictions are prose about the law, not a table of data: sans, like every other
        // sentence this app says (docs/09 "타이포").
        BlueprintDialogText(strings[Consent.BODY])
        BlueprintDialogLink(strings[Consent.LINK_TEXT], model::openConsentGuidance)
        BlueprintCheckRow(
            label = strings[Consent.SUPPRESS],
            checked = dontAskAgain,
            onCheckedChange = { dontAskAgain = it },
        )
    }
}

/**
 * The tray icon, drawn rather than bundled: the app mark's monochrome template (docs/09
 * "앱 아이콘", docs/design/icon.svg) — an outer node with an inner square that turns red while
 * recording (docs/12 "상태 아이콘", the same rule on both desktops). A vector needs no asset
 * pipeline; the .ico the installer carries is the same mark, exported by scripts/render-icons.swift.
 *
 * Windows does not template-tint a tray icon, so the ink has to be chosen for the taskbar it sits
 * in: the dark palette on a dark taskbar (also the macOS menu bar on the development host, where
 * a dark mark was an icon nobody could find), the light palette on a light one (Sol icon r1).
 */
private class StatusIcon(
    private val recording: Boolean,
    /** docs/10: something in the queue is waiting for the user. The Mac's menu bar badges the same. */
    private val blocked: Boolean,
    private val lightTaskbar: Boolean,
) : Painter() {
    override val intrinsicSize: Size = Size(GRID, GRID)

    override fun DrawScope.onDraw() {
        // The template's 22 grid: outer 16 at (3,3) radius 2 stroke 1.5, inner 6 at (8,8) radius 1.
        val unit = size.minDimension / GRID
        val stroke = 1.5f * unit
        val ink = if (lightTaskbar) INK_ON_LIGHT else INK_ON_DARK
        val record = if (lightTaskbar) RECORD_ON_LIGHT else RECORD_ON_DARK
        drawRoundRect(
            color = ink,
            // A stroke is centred on its path, so the rectangle is inset by half of it.
            topLeft = Offset(3f * unit + stroke / 2f, 3f * unit + stroke / 2f),
            size = Size(16f * unit - stroke, 16f * unit - stroke),
            cornerRadius = CornerRadius(2f * unit),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = if (recording) record else ink,
            topLeft = Offset(8f * unit, 8f * unit),
            size = Size(6f * unit, 6f * unit),
            cornerRadius = CornerRadius(1f * unit),
        )
        // docs/09 "모든 상태는 색 + 텍스트" as far as 22 pixels allow: a second square, in the warning
        // token and in the corner, so "something is waiting for you" is a *shape* that appears and
        // not only a hue. The same badge RecMac puts on its menu bar item.
        if (blocked) {
            drawRoundRect(
                color = if (lightTaskbar) WARNING_ON_LIGHT else WARNING_ON_DARK,
                topLeft = Offset(13f * unit, 13f * unit),
                size = Size(7f * unit, 7f * unit),
                cornerRadius = CornerRadius(1f * unit),
            )
        }
    }

    private companion object {
        // The template's own grid, which is also the size the tray is asked for.
        const val GRID = 22f
        // docs/09 tokens: ink and record for each ground.
        val INK_ON_DARK = Color(0xFFF2F2F0)
        val RECORD_ON_DARK = Color(0xFFFA4D56)
        val INK_ON_LIGHT = Color(0xFF111111)
        val RECORD_ON_LIGHT = Color(0xFFDA1E28)
        val WARNING_ON_DARK = Color(0xFFF1C21B)
        val WARNING_ON_LIGHT = Color(0xFFB28600)
    }
}

/**
 * Whether the Windows taskbar is light. `SystemUsesLightTheme` is the taskbar's own switch —
 * `AppsUseLightTheme` (what `isSystemInDarkTheme` reads) is the one for app windows, and the two
 * are set independently. Anything but a readable `0x1` — no Windows, no key, `reg` failing — is
 * treated as dark, the default of every Windows 11 install.
 */
private fun taskbarIsLight(): Boolean {
    if (!Host.isWindows) return false
    return runCatching {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "SystemUsesLightTheme",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Regex("SystemUsesLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output)
            ?.groupValues?.get(1)?.toInt(16) == 1
    }.getOrDefault(false)
}

/** The product name, never translated (docs/07): the window titles and the popup header. */
internal const val APP_NAME: String = "Recly"

/** The consent body is three jurisdictions and a paragraph; a 460dp card would be a column. */
private const val CONSENT_WIDTH = 560
private const val CONSENT_HEIGHT = 380

/** A hint, one field, and the six participant chips under it. */
private const val TITLE_HEIGHT = 360

private const val POPUP_WIDTH = 520
private const val POPUP_HEIGHT = 560
private const val EDITOR_WIDTH = 1100
private const val EDITOR_HEIGHT = 760
private const val SETTINGS_WIDTH = 640
private const val SETTINGS_HEIGHT = 900
private const val RECORDINGS_WIDTH = 900
private const val RECORDINGS_HEIGHT = 620
