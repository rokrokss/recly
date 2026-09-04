package app.recly.windows.ui

import app.recly.windows.detect.MeetingDetectionRule
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text

/** One line of the tray menu. [Separator] is the rule between two groups of them. */
sealed interface TrayEntry {
    data class Item(
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : TrayEntry

    data object Separator : TrayEntry
}

/**
 * The AWT menu, as a list rather than a composition (docs/14 N1 · deliverable 1). `Main.kt` turns
 * each entry into a menu item; keeping the decisions here means the menu a given shell state and a
 * given language produce can be looked at without a tray — which is how `TrayMenuTest` proves that
 * choosing English rebuilds it (docs/07 rule 3).
 *
 * **It is the fallback now, not the UI.** docs/09 화면 원칙 6 puts the state nodes, the ledger and the
 * workflow picker in a window ([TrayPopup]), because an AWT menu item is one run of system text and
 * cannot draw any of them. What stays here is what has to work even if that window will not open on
 * some machine: what the app is doing, the way in to the window, start/stop, and quit.
 *
 * Every label is resolved from [strings] on the way out, so a language change produces a different
 * list and Compose replaces the AWT items with it.
 */
fun trayMenu(model: ShellModel, strings: Strings, quit: () -> Unit): List<TrayEntry> = buildList {
    // The first line is the status, and it is not clickable — the Mac's menu opens the same way.
    add(TrayEntry.Item(model.status.text(strings), enabled = false) {})
    // Its diagnostic, indented under it: an AWT menu item has one line and no font of its own, so
    // a second disabled item is the whole of what a tray can do with it.
    model.statusDetail?.let { add(TrayEntry.Item("    $it", enabled = false) {}) }
    // docs/06: a job parked in NEEDS_AUTH is unblocked by signing in, and the popup is the only
    // other place that offers it — on a machine where that window will not open, this is the whole
    // of what the user has.
    if (model.needsAuth || !model.signedIn) {
        val label = if (model.needsAuth) ShellModel.NEEDS_AUTH_NOTICE else Str.TRAY_SIGN_IN
        add(TrayEntry.Item(strings[label], onClick = model::signIn))
    }
    add(TrayEntry.Separator)

    add(TrayEntry.Item(strings[Str.TRAY_OPEN]) { model.popupOpen = true })
    if (model.recording) {
        add(TrayEntry.Item(strings[Str.TRAY_STOP], onClick = model::stop))
    } else {
        // ADR-016: a recording runs the workflow this PC is set to, which is what the popup's Start
        // does as well. A recording waiting for its name would lose its job to a second one.
        val startable = model.ready && !model.helperMissing && model.titlePrompt == null
        add(TrayEntry.Item(strings[Str.TRAY_START], enabled = startable) { model.start(null) })
        // docs/14 "감지": an AWT balloon has no buttons, so clicking the balloon is the whole of its
        // interaction and **the reliable way to take an offer is this item** ([MeetingNotifier]).
        // It comes and goes with the offer, which is what keeps a stale one off the menu.
        if (model.meetingOffer == MeetingDetectionRule.Prompt.START) {
            add(TrayEntry.Item(strings[Str.TRAY_START_DETECTED], onClick = model::startDetected))
        }
    }
    add(TrayEntry.Separator)

    // docs/12: the quit finalizes and queues the recording that is running before it goes
    // ([ShellModel.shutdown]), so under a capture it is a different sentence — the Mac's footer
    // swaps the same two labels.
    add(TrayEntry.Item(strings[if (model.recording) Str.TRAY_QUIT_SAVING else Str.TRAY_QUIT], onClick = quit))
}
