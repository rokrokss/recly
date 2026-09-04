package app.recly.windows.ui

import app.recly.windows.settings.AppTheme

/**
 * The switches `Main` accepts on the command line so that the three windows can be photographed on
 * a machine whose tray icon is not reachable (docs/lanes/UX-L3 "Done condition"). They are *screen*
 * overrides only: nothing here is written to `java.util.prefs`, so a run of the packaged app for a
 * screenshot leaves the developer's own settings exactly as they were.
 *
 * ```
 * Recly --show-popup --show-editor --show-settings --theme=dark --high-contrast --lang=ko --width=640
 * Recly --show-delete --show-disconnect --show-consent --step=1
 * ```
 */
data class DevFlags(
    val popup: Boolean = false,
    val editor: Boolean = false,
    val settings: Boolean = false,
    /**
     * The three dialogs, which are answers to a click rather than windows of their own — a delete
     * asked about the newest recording, the disconnect warning, and the consent reminder (which is
     * a start that stops in front of it).
     */
    val delete: Boolean = false,
    val disconnect: Boolean = false,
    val consent: Boolean = false,
    /** Null leaves the setting alone; otherwise it stands in for it for this run. */
    val theme: AppTheme? = null,
    val highContrast: Boolean? = null,
    /** `ko` or `en`, standing in for the system's language — not for the stored choice. */
    val language: String? = null,
    /** The editor window's width, for the two ends of the fluid type scale. */
    val editorWidth: Int? = null,
    /** Which step's inspector the editor opens on — the trigger's when nothing says otherwise. */
    val step: Int? = null,
) {
    /** A popup opened by a flag stays open when it loses focus; one opened from the tray does not. */
    val pinned: Boolean get() = popup
}

/**
 * Whether the dialog on screen is the question or a photograph of it.
 *
 * The three `--show-…` dialogs are the only screens in this app whose answer is destructive, and a
 * screenshot run is a real install: `--show-consent` used to be `start(null)`, which opens a real
 * capture the moment the consent reminder is switched off; the delete dialog's confirm deletes a
 * real recording; the disconnect dialog's revokes the developer's own Google grant. So a dialog
 * raised by a flag is raised in [PREVIEW], where the whole of either answer is to close it.
 *
 * It never goes back to [LIVE]. A run that was started for a screenshot stays one, and a mode that
 * could be left by accident is a mode that guarantees nothing.
 */
enum class DialogMode {
    LIVE,
    PREVIEW,
    ;

    /** Whether answering the dialog does the thing the dialog is about. */
    val acts: Boolean get() = this == LIVE
}

fun devFlags(args: Array<String>): DevFlags = args.fold(DevFlags()) { flags, arg ->
    val value = arg.substringAfter('=', missingDelimiterValue = "")
    when (arg.substringBefore('=')) {
        "--show-popup" -> flags.copy(popup = true)
        "--show-editor" -> flags.copy(editor = true)
        "--show-settings" -> flags.copy(settings = true)
        "--show-delete" -> flags.copy(delete = true)
        "--show-disconnect" -> flags.copy(disconnect = true)
        "--show-consent" -> flags.copy(consent = true)
        "--theme" -> flags.copy(theme = AppTheme.of(value))
        "--high-contrast" -> flags.copy(highContrast = true)
        "--lang" -> flags.copy(language = value.takeIf { it.isNotEmpty() })
        "--width" -> flags.copy(editorWidth = value.toIntOrNull())
        "--step" -> flags.copy(step = value.toIntOrNull())
        else -> flags
    }
}
