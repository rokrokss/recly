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

/// docs/03 "로그아웃 vs 연결 해제": what is true of a disconnect and is not true of a sign-out — every
/// device loses access, the upload queue on this device is wiped while its workflows and keys stay,
/// and the recordings Drive has not got stay here unless the user asks otherwise. Only the audio
/// that exists nowhere else is a separate, unchecked answer: the irreversible half is never the
/// default one.
///
/// One dialog for both shells, as [DeleteDialog] is, and for the same reason: these lines name the
/// device and nothing else about them differs.
public struct DisconnectDialog: View {
    private let prompt: DisconnectPrompt
    private let device: DisconnectDevice
    private let confirm: (Bool) -> Void
    private let cancel: () -> Void
    private let permissions: () -> Void

    @State private var alsoDelete = false
    @Environment(\.locale) private var locale

    public init(
        prompt: DisconnectPrompt,
        device: DisconnectDevice,
        confirm: @escaping (Bool) -> Void,
        cancel: @escaping () -> Void,
        permissions: @escaping () -> Void
    ) {
        self.prompt = prompt
        self.device = device
        self.confirm = confirm
        self.cancel = cancel
        self.permissions = permissions
    }

    public var body: some View {
        BlueprintDialog(title: loc("Disconnect Recly from Google?")) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { cancel() }
            BlueprintButton(loc("Disconnect"), tone: .danger) { confirm(alsoDelete) }
                .disabled(!prompt.canConfirm)
                .accessibilityIdentifier("disconnect-confirm")
        } content: {
            // docs/03: what a disconnect takes away. Only the audio that exists nowhere else takes
            // the record red — several red paragraphs would leave the colour meaning nothing, and
            // this is the same line [DeleteDialog] puts in red for the same reason. Losing access is
            // undone by signing in again; these recordings are what the checkbox below takes for
            // good.
            BlueprintDialogText(loc(device.everyDeviceLosesAccess))
            if prompt.unuploaded > 0 {
                BlueprintDialogText(loc(device.unuploadedStay, "\(prompt.unuploaded)"), tone: .danger)
            }
            BlueprintDialogText(loc(device.queueWiped))
            BlueprintCheckRow(loc(device.alsoDeleteRecordings), isOn: $alsoDelete)
                .accessibilityIdentifier("disconnect-also-delete")
            // docs/03: a capture that is running has no job yet, so the core's own busy guard does
            // not cover it and "also delete" would take the recording being written. Said rather
            // than stopped for them — an app that ended a recording to answer a settings question
            // would be answering a different one.
            if let blocker = prompt.blocker {
                BlueprintDialogText(blocker, tone: .danger)
                    .accessibilityIdentifier("disconnect-blocked")
            }
            // docs/03: "안내에 Google 계정 설정에서 직접 해제하는 방법도 함께 적는다."
            BlueprintDialogLink(loc("Open Google account permissions")) { permissions() }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }

    private func loc(_ key: String, _ argument: String) -> String {
        RecKitStrings.localized(key, argument)
    }
}
