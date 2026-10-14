import AppKit
import RecKit
import SwiftUI

/// docs/12 M8 · ADR-011: the recording-consent reminder, as a [BlueprintPanel] — the iPhone's `ConsentDialog` line for
/// line (the question, the reminder, the jurisdiction link and `Do not ask again`), in this app's catalog, where the
/// wording is the one Android's and Windows' consent tests read. It used to be an `NSAlert`, which brought the
/// platform's own shape and buttons into a design that has its own (2026-10-15).
///
/// Cancel means something: it is a question, and answering "no" leaves the recording unstarted
/// ([ConsentGate]). Return is `I told them`, Esc is Cancel, as they were on the alert.
struct ConsentPanel: View {
    /// Wikipedia's summary of recording-consent law (`MenuModel.consentGuidance`).
    let guidance: URL
    let answer: (ConsentGate.Answer) -> Void

    @State private var suppress = false

    var body: some View {
        BlueprintDialog(title: loc("Did you tell the participants about the recording?")) {
            BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) {
                answer(ConsentGate.Answer(confirmed: false, suppress: suppress))
            }
            .keyboardShortcut(.cancelAction)
            BlueprintButton(loc("I told them · Start recording"), tone: .primary) {
                answer(ConsentGate.Answer(confirmed: true, suppress: suppress))
            }
            .keyboardShortcut(.defaultAction)
            .accessibilityIdentifier("consent-confirm")
        } content: {
            // docs/research/02 §Consent · law. The reminder and nothing else (2026-10-08 §11): the rules by
            // jurisdiction are behind the link under it, not in the question.
            BlueprintDialogText(loc("consent.body"))
            // A link and not a third button: the question the panel is asking is still open.
            BlueprintDialogLink(loc("Recording-consent rules by jurisdiction")) {
                NSWorkspace.shared.open(guidance)
            }
            BlueprintCheckRow(loc("Do not ask again"), isOn: $suppress)
        }
    }
}

/// A permission the system holds and the app cannot retry its way past (docs/12 "Permissions"): what is missing, where
/// to turn it on, and the deep link to that pane. Two answers (docs/09 screen principle 5); true opens the pane.
struct PermissionPanel: View {
    let title: String
    let message: String
    let answer: (Bool) -> Void

    var body: some View {
        BlueprintDialog(title: title) {
            BlueprintButton(loc("Close"), tone: .quiet, minWidth: minTouch) { answer(false) }
                .keyboardShortcut(.cancelAction)
            BlueprintButton(loc("Open System Settings"), tone: .primary) { answer(true) }
                .keyboardShortcut(.defaultAction)
        } content: {
            BlueprintDialogText(message)
        }
    }
}

/// A failure with nothing to do about it in the app — said, and acknowledged.
struct NoticePanel: View {
    let title: String
    let message: String
    let done: () -> Void

    var body: some View {
        BlueprintDialog(title: title) {
            BlueprintButton(loc("OK"), tone: .primary, minWidth: minTouch, action: done)
                .keyboardShortcut(.defaultAction)
        } content: {
            BlueprintDialogText(message)
        }
    }
}
