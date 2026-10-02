#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// docs/03 "Storage location" (ADR-024): where new recordings go — Google Drive, or the app's iCloud folder
/// on the iPhone and the Mac — and, when it is iCloud, whether this device can reach it.
///
/// The choice is saved the moment it is made, as the theme is. A recording already started keeps the
/// storage it froze, and nothing already uploaded moves.
@MainActor
public final class StorageChoice: ObservableObject {
    public enum ICloud: Equatable { case available, unavailable }

    @Published public private(set) var selected: StorageKind = .drive
    @Published public private(set) var icloud: ICloud = .unavailable
    @Published public private(set) var busy = false
    @Published public private(set) var message: UiMessage?

    /// After a switch: the open processing form takes the new revision under its draft
    /// ([ProcessingSettingsModel.storageChanged]).
    public var onChanged: (() async -> Void)?

    private let core: ReclyCore_

    public init(core: ReclyCore_) {
        self.core = core
    }

    /// Whether this build can offer iCloud at all: signed with the iCloud entitlement (docs/13 "iCloud").
    public var offered: Bool { core.deps.ubiquity != nil }

    public func refresh() async {
        if let kind = try? await core.processingSettings.storage() { selected = kind }
        guard let container = core.deps.ubiquity else { return }
        let available = (try? await container.available().boolValue) == true
        icloud = available ? .available : .unavailable
        if available, selected == .icloud { await showFolder(in: container) }
    }

    /// The storage folder's first level, made as soon as iCloud can be used: the Recly folder then
    /// shows in Files and Finder, and Recly in the system's list of apps using iCloud Drive, before
    /// the first recording. A first level that is a date pattern waits for that recording.
    private func showFolder(in container: UbiquityContainer) async {
        guard let ready = try? await core.processingSettings.read() as? ProcessingSettingsStateReady,
              let first = ready.document.settings.storage.folder.split(separator: "/").first,
              !first.contains("{{")
        else { return }
        try? await container.makeDirectories(path: String(first))
    }

    public func select(_ kind: StorageKind) async {
        guard kind != selected, !busy else { return }
        busy = true
        defer { busy = false }
        message = nil
        do {
            let result = try await core.processingSettings.setStorage(provider: kind)
            if result is ProcessingSaveResultSaved {
                selected = kind
                await onChanged?()
            } else if let invalid = result as? ProcessingSaveResultInvalid {
                message = .key("Failed: %@", args: [.verbatim(invalid.errors.joined(separator: "\n"))])
            }
        } catch {
            message = .key("Failed: %@", args: [.verbatim(error.localizedDescription)])
        }
        await refresh()
    }
}

/// The account block of the settings screen with the storage choice on top of it. A build that
/// cannot offer iCloud draws the Drive block exactly as before; one that can asks "Storage" first,
/// in chips as the theme does, and under them draws the chosen storage's row only — the same shape
/// for both: connected, or not connected with what to do. Drive stays connected after a switch to
/// iCloud, and its row, with Disconnect, is back under the Google Drive chip.
public struct StorageSection: View {
    @ObservedObject private var choice: StorageChoice
    private let drive: (Bool) -> DriveConnectionSection
    @Environment(\.locale) private var locale

    /// [drive] draws the Drive rows, with or without their own "Google Drive" header.
    public init(choice: StorageChoice, drive: @escaping (Bool) -> DriveConnectionSection) {
        self.choice = choice
        self.drive = drive
    }

    public var body: some View {
        Group {
            if choice.offered {
                SectionHeader(loc("Storage")).padding(.horizontal, Space.m)
                SectionBlock {
                    ChoiceRow {
                        // Product names, never translated (docs/07 rule 1).
                        BlueprintChip("Google Drive", selected: choice.selected == .drive, fill: true) {
                            Task { await choice.select(.drive) }
                        }
                        .accessibilityIdentifier("storage-drive")
                        BlueprintChip("iCloud", selected: choice.selected == .icloud, fill: true) {
                            Task { await choice.select(.icloud) }
                        }
                        .accessibilityIdentifier("storage-icloud")
                    }
                    .disabled(choice.busy)
                }
                if choice.selected == .icloud {
                    // Drive's row in shape, without a sign-in of its own: the system's Settings is
                    // where iCloud is turned on, so the row says exactly where (docs/03 "Storage location").
                    if choice.icloud == .available {
                        SectionRow(title: loc("iCloud connected")) { EmptyView() }
                            .accessibilityIdentifier("icloud-status")
                    } else {
                        SectionRow(title: loc("iCloud not connected"), subtitle: loc(Self.unavailable)) {
                            #if os(macOS)
                            BlueprintButton(loc("Open iCloud Settings"), tone: .quiet) { Self.openICloudSettings() }
                            #endif
                        }
                        .accessibilityIdentifier("icloud-status")
                    }
                } else {
                    drive(false)
                }
                if let message = choice.message {
                    SectionFootnote(message.text)
                }
            } else {
                drive(true)
            }
        }
        .task { await choice.refresh() }
        // Back from Settings, or the iCloud account or iCloud Drive changed: asked again, so the row
        // never keeps saying what was true before the trip to Settings.
        .onReceive(NotificationCenter.default.publisher(for: .NSUbiquityIdentityDidChange)) { _ in
            Task { await choice.refresh() }
        }
        .onReceive(NotificationCenter.default.publisher(for: Self.becameActive)) { _ in
            Task { await choice.refresh() }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }

    // The way to each switch, in the words of the system's own Settings (2026-10-02, iOS and
    // macOS 26). No public link opens iCloud's page on the iPhone, so the iPhone gets the path only.
    #if os(macOS)
    private static let becameActive = NSApplication.didBecomeActiveNotification
    private static let unavailable =
        "In System Settings → [your name] → iCloud → Drive, turn on Sync this Mac, then turn on Recly under Apps Syncing to iCloud Drive."

    /// System Settings → Apple Account → iCloud, as the screen recording and microphone panes are
    /// opened (MenuModel).
    private static func openICloudSettings() {
        guard let pane = URL(string: "x-apple.systempreferences:com.apple.systempreferences.AppleIDSettings:icloud") else { return }
        NSWorkspace.shared.open(pane)
    }
    #else
    private static let becameActive = UIApplication.didBecomeActiveNotification
    private static let unavailable =
        "In Settings → [your name] → iCloud → Drive, turn on Sync this iPhone, then turn on Recly under Saved to iCloud → See All."
    #endif
}
#endif
