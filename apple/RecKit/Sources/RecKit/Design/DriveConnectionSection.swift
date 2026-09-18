import SwiftUI

/// Google authorizes storage access; Recly has no user account (docs/06 · docs/15).
public struct DriveConnectionSection: View {
    private let account: String?
    private let connected: Bool
    private let configured: Bool
    private let pending: Bool
    private let revokeDebt: Bool
    private let blocker: String?
    private let signIn: () -> Void
    private let signOut: () -> Void
    private let revoke: () -> Void
    private let permissions: () -> Void
    private let debtSettled: () -> Void
    @State private var managing = false
    @State private var afterDismiss: (() -> Void)?
    @Environment(\.locale) private var locale

    public init(account: String?, connected: Bool, configured: Bool, pending: Bool,
                revokeDebt: Bool, blocker: String?, signIn: @escaping () -> Void,
                signOut: @escaping () -> Void, revoke: @escaping () -> Void,
                permissions: @escaping () -> Void, debtSettled: @escaping () -> Void) {
        self.account = account
        self.connected = connected
        self.configured = configured
        self.pending = pending
        self.revokeDebt = revokeDebt
        self.blocker = blocker
        self.signIn = signIn
        self.signOut = signOut
        self.revoke = revoke
        self.permissions = permissions
        self.debtSettled = debtSettled
    }

    public var body: some View {
        SectionHeader(loc("Google Drive")).padding(.horizontal, Space.m)
        #if os(macOS)
        connection
        if managing { management.padding(Space.m) }
        #else
        connection.sheet(isPresented: $managing, onDismiss: {
            let action = afterDismiss
            afterDismiss = nil
            action?()
        }) {
            BlueprintDialogSheet { management }
        }
        #endif
    }

    private var connection: some View {
        Group {
            if connected || pending || revokeDebt {
                Button { managing = true } label: {
                    SectionRow(
                        title: account ?? loc(connected ? "Drive connected" : "Drive connection needs attention"),
                        subtitle: pending || revokeDebt ? loc("Drive connection needs attention") : nil
                    ) {
                        Image(systemName: "chevron.right")
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("drive-manage")
            } else {
                SectionRow(title: loc("Drive not connected")) {
                    BlueprintButton(loc("Connect Google Drive")) { signIn() }
                        .disabled(!configured || blocker != nil)
                        .accessibilityIdentifier("signIn")
                }
                BlueprintDialogText(loc("Record and play local audio without an account. Connect Google Drive to upload recordings."))
                    .padding(.horizontal, Space.m)
                BlueprintDialogLink(loc("Manage Google permissions")) { permissions() }
                    .padding(.horizontal, Space.m)
            }
        }
    }

    private var management: some View {
        BlueprintDialog(title: loc("Google Drive")) {
            BlueprintButton(loc("Close"), tone: .quiet) { managing = false }
        } content: {
            if let account { BlueprintDialogText(account) }
            BlueprintDialogText(loc("Google access is used for Drive storage. Recly has no account of its own."))
            if connected {
                BlueprintDialogLink(loc("Stop using Drive on this device")) {
                    performAfterClosing(signOut)
                }
                .disabled(pending)
                .accessibilityIdentifier("signOut")
                BlueprintDialogText(loc("This device stops accessing Drive. Recordings stay, and Google permissions remain until revoked."))
            }
            if connected || pending {
                BlueprintDialogLink(loc(pending ? "Finish revoking Google access" : "Revoke Google access")) {
                    performAfterClosing(revoke)
                }
                .accessibilityIdentifier("disconnect")
            }
            if !connected && !pending {
                BlueprintDialogLink(loc("Connect Google Drive")) {
                    performAfterClosing(signIn)
                }
                .disabled(!configured)
            }
            BlueprintDialogLink(loc("Manage Google permissions")) { permissions() }
            if revokeDebt {
                BlueprintDialogText(DisconnectGuard.stillListed.text)
                BlueprintDialogLink(DisconnectGuard.debtSettled.text) { debtSettled() }
                    .accessibilityIdentifier("revoke-debt-settled")
            }
        }
    }

    /// A menu-bar popover expands inline. On iOS, dismiss the management sheet before opening
    /// Google's sign-in UI or the separate revocation confirmation sheet.
    private func performAfterClosing(_ action: @escaping () -> Void) {
        #if os(macOS)
        managing = false
        action()
        #else
        afterDismiss = action
        managing = false
        #endif
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
