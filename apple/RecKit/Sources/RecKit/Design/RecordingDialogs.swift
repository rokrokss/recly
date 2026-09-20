import SwiftUI

/// docs/03 "앱에서 지우기": one recording, two answers about Drive, and the default is the one that
/// can be undone — the files in Drive are the user's own and something downstream may already have
/// read the folder. What is still only on this device is said first, because that is the part of the
/// deletion nothing anywhere else can give back.
///
/// One dialog for both shells. [DisconnectDevice] is the whole of what they differ by — "Delete on
/// this Mac only" against "Delete on this phone only" — and that is a word no translation could
/// choose for them.
public struct DeleteDialog: View {
    private let request: DeleteRequest
    private let device: DisconnectDevice
    private let delete: (DeleteRequest, Bool) -> Void
    private let cancel: () -> Void

    @State private var deleteDrive = false
    /// docs/07 rule 3: the rows below are resolved outside SwiftUI, and reading the locale is what
    /// declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale

    public init(
        request: DeleteRequest,
        device: DisconnectDevice,
        delete: @escaping (DeleteRequest, Bool) -> Void,
        cancel: @escaping () -> Void
    ) {
        self.request = request
        self.device = device
        self.delete = delete
        self.cancel = cancel
    }

    public var body: some View {
        BlueprintDialog(title: loc("Delete ‘%@’?", request.title)) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { cancel() }
            BlueprintButton(loc("Delete"), tone: .danger) {
                delete(request, request.remote || deleteDrive)
            }
            .accessibilityIdentifier("delete-confirm")
        } content: {
            // docs/03: a recording another device made and uploaded has no local half to keep, so
            // there are not two answers to give — only the one thing the deletion reaches.
            if request.remote {
                BlueprintDialogText(
                    loc("Recorded on another device. Deleting removes it from Drive and from every device.")
                )
                .accessibilityIdentifier("delete-remote")
            } else {
                choices
            }
        }
    }

    /// docs/03: the two answers about Drive, and the count that is only ever about this device's own
    /// parts — neither of which a row this device did not record has.
    @ViewBuilder
    private var choices: some View {
        if request.unuploaded > 0 {
            BlueprintDialogText(
                loc(
                    "%@ part(s) have not reached Drive yet and are deleted with it.",
                    "\(request.unuploaded)"
                ),
                tone: .danger
            )
            .accessibilityIdentifier("delete-unuploaded")
        }
        BlueprintRadioRow(loc(device.deleteHereOnly), selected: !deleteDrive) {
            deleteDrive = false
        }
        .accessibilityIdentifier("delete-local-only")
        BlueprintRadioRow(loc("Also delete the Drive folder"), selected: deleteDrive) {
            deleteDrive = true
        }
        .accessibilityIdentifier("delete-with-drive")
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }

    private func loc(_ key: String, _ argument: String) -> String {
        RecKitStrings.localized(key, argument)
    }
}

/// docs/03: the name of a recording, asked again from the page that carries it. The post-stop
/// prompt (`RecordingView.NamingSheet` on the phone, `NamingSheet` on the Mac) asks for the same
/// name in the same words, and also asks how many people were in the room — that second question
/// belongs to the moment the recording ended and not to a rename, so this asks only the one.
///
/// One dialog for both shells, as [DeleteDialog] is: nothing about the question is the phone's or
/// the Mac's. docs/09 화면 원칙 5 — the title, the field with its one line under it, two answers.
public struct RenameDialog: View {
    private let rename: (String) -> Void
    private let cancel: () -> Void

    /// What has been typed. It starts at the name the recording already has, so a rename that only
    /// fixes a word is the word and nothing else.
    @State private var typed: String
    /// docs/07 rule 3: the lines here are resolved outside SwiftUI, and reading the locale is what
    /// declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale

    public init(
        title: String,
        rename: @escaping (String) -> Void,
        cancel: @escaping () -> Void
    ) {
        self.rename = rename
        self.cancel = cancel
        _typed = State(initialValue: title)
    }

    public var body: some View {
        BlueprintDialog(title: loc("Recording title")) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { cancel() }
            BlueprintButton(loc("Save"), tone: .primary) { rename(typed) }
                .accessibilityIdentifier("rename-save")
        } content: {
            BlueprintField(loc("Title"), text: $typed)
                .accessibilityIdentifier("rename-field")
            BlueprintDialogText(loc("Leave it empty to keep the timestamp name"), tone: .muted)
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// docs/03 "로그아웃 vs 연결 해제": revocation can affect other devices and clears this device's
/// upload queue. Recordings, workflows and keys stay; deleting audio is a separate list action.
///
/// One dialog for both shells, as [DeleteDialog] is, and for the same reason: these lines name the
/// device and nothing else about them differs.
public struct DisconnectDialog: View {
    private let prompt: DisconnectPrompt
    private let confirm: (Bool) -> Void
    private let cancel: () -> Void

    @Environment(\.locale) private var locale

    public init(
        prompt: DisconnectPrompt,
        confirm: @escaping (Bool) -> Void,
        cancel: @escaping () -> Void
    ) {
        self.prompt = prompt
        self.confirm = confirm
        self.cancel = cancel
    }

    public var body: some View {
        BlueprintDialog(title: loc("Disconnect Recly from Google?")) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { cancel() }
            BlueprintButton(loc("Disconnect"), tone: .danger) { confirm(false) }
                .disabled(!prompt.canConfirm)
                .accessibilityIdentifier("disconnect-confirm")
        } content: {
            BlueprintDialogText(loc("Recly loses Drive access on all devices connected to this Google account. Pending work pauses until you reconnect the same account. Recordings and settings stay."), tone: .muted)
            // docs/03: cleanup must not race a capture or a job that still writes account state.
            if let blocker = prompt.blocker {
                BlueprintDialogText(blocker, tone: .danger)
                    .accessibilityIdentifier("disconnect-blocked")
            }

        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }

}
