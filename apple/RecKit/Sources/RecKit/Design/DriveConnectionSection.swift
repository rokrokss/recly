import SwiftUI

/// Google authorizes storage access; Recly has no user account (docs/06 · docs/15).
public struct DriveConnectionSection: View {
    private let account: String?
    private let connected: Bool
    private let configured: Bool
    private let pending: Bool
    private let disconnecting: Bool
    private let revokeDebt: Bool
    private let blocker: String?
    private let signIn: () -> Void
    private let disconnect: () -> Void
    private let permissions: () -> Void
    private let debtSettled: () -> Void
    @Environment(\.locale) private var locale

    public init(account: String?, connected: Bool, configured: Bool, pending: Bool, disconnecting: Bool,
                revokeDebt: Bool, blocker: String?, signIn: @escaping () -> Void,
                disconnect: @escaping () -> Void,
                permissions: @escaping () -> Void, debtSettled: @escaping () -> Void) {
        self.account = account
        self.connected = connected
        self.configured = configured
        self.pending = pending
        self.disconnecting = disconnecting
        self.revokeDebt = revokeDebt
        self.blocker = blocker
        self.signIn = signIn
        self.disconnect = disconnect
        self.permissions = permissions
        self.debtSettled = debtSettled
    }

    public var body: some View {
        SectionHeader(loc("Google Drive")).padding(.horizontal, Space.m)
        if connected || pending || disconnecting {
            SectionRow(title: account ?? loc(disconnecting ? "Google Drive" : connected ? "Drive connected" : "Drive connection needs attention")) {
                BlueprintButton(loc(disconnecting ? "Disconnecting…" : "Disconnect Drive"),
                                tone: .danger, action: disconnect)
                    .disabled(disconnecting)
                    .accessibilityIdentifier("disconnect")
            }
        } else {
            SectionRow(title: loc("Drive not connected"),
                       subtitle: loc("Record locally. Connect Drive to upload.")) {
                BlueprintButton(loc("Connect Google Drive"), action: signIn)
                    .disabled(!configured || blocker != nil)
                    .accessibilityIdentifier("signIn")
            }
        }
        if revokeDebt && !disconnecting {
            SectionFootnote(DisconnectGuard.stillListed.text)
            BlueprintDialogLink(loc("Google permissions"), action: permissions)
                .padding(.horizontal, Space.m)
            SectionBlock {
                BlueprintButton(DisconnectGuard.debtSettled.text, tone: .quiet, action: debtSettled)
                    .accessibilityIdentifier("revoke-debt-settled")
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
