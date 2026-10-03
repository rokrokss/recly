#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
import UniformTypeIdentifiers
#endif

/// docs/03 "Storage location" (ADR-024): where new recordings go — Google Drive, the app's iCloud folder on
/// the iPhone and the Mac, or a local folder picked on the device — and whether this device can reach it.
///
/// The choice is saved the moment it is made, as the theme is. A recording already started keeps the
/// storage it froze, and nothing already uploaded moves.
@MainActor
public final class StorageChoice: ObservableObject {
    public enum ICloud: Equatable { case available, unavailable }
    /// The local folder: none picked yet, picked and reachable, or picked and gone — a disk that was
    /// unplugged, a folder moved or deleted.
    public enum Folder: Equatable { case notChosen, available, unavailable }

    @Published public private(set) var selected: StorageKind = .drive
    @Published public private(set) var icloud: ICloud = .unavailable
    @Published public private(set) var folder: Folder = .notChosen
    /// The picked folder's absolute path, which its row names; nil while none is picked.
    @Published public private(set) var folderPath: String?
    @Published public private(set) var busy = false
    @Published public private(set) var message: UiMessage?

    /// After a switch: the open processing form takes the new revision under its draft
    /// ([ProcessingSettingsModel.storageChanged]).
    public var onChanged: (() async -> Void)?

    /// After a folder is picked: the jobs it let go are due now, and the shell's runner runs them.
    public var onFolderPicked: (() -> Void)?

    private let core: ReclyCore_

    public init(core: ReclyCore_) {
        self.core = core
    }

    /// Whether this build can offer iCloud at all: signed with the iCloud entitlement (docs/13 "iCloud").
    public var icloudOffered: Bool { core.deps.ubiquity != nil }

    /// Whether this device can offer a local folder: the iPhone and the Mac (docs/03 "Storage location").
    public var folderOffered: Bool { core.deps.localFolder != nil }

    /// Whether there is a choice to make at all: iCloud or a local folder is offered.
    public var offered: Bool { icloudOffered || folderOffered }

    public func refresh() async {
        if let kind = try? await core.processingSettings.storage() { selected = kind }
        #if os(macOS)
        if let local = core.deps.localFolder { await check(local) }
        #else
        if let picked = core.deps.localFolder as? PickedFolder { await check(picked) }
        #endif
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

    #if os(macOS)
    /// The picked folder as it stands now: the path from this Mac's settings, and whether the core can
    /// use it.
    private func check(_ local: LocalFolder) async {
        folderPath = LocalFolderPath.current
        let reachable = (try? await local.available().boolValue) == true
        folder = folderPath == nil ? .notChosen : reachable ? .available : .unavailable
    }

    /// The user picked [path]. It is kept on this Mac, and the uploads and transcripts that were
    /// waiting for a folder run now rather than at their next look, five minutes out.
    public func pickFolder(_ path: String) async {
        LocalFolderPath.current = path
        await picked()
    }
    #else
    /// The folder picked in Files as it stands now: its name, and whether it can still be reached —
    /// a bookmark that no longer resolves, or access taken back in Settings, is a folder that is gone.
    private func check(_ picked: PickedFolder) async {
        folderPath = await picked.name()
        let reachable = await picked.reachable()
        folder = !picked.picked ? .notChosen : reachable ? .available : .unavailable
    }

    /// The user picked [url] in Files. It is kept as a bookmark on this iPhone, and the uploads and
    /// transcripts that were waiting for a folder run now rather than at their next look.
    public func pickFolder(_ url: URL) async {
        guard let picked = core.deps.localFolder as? PickedFolder else { return }
        do {
            try picked.pick(url)
        } catch {
            message = .key("Failed: %@", args: [.verbatim(error.localizedDescription)])
            return
        }
        await self.picked()
    }
    #endif

    /// After a pick: what was waiting for a folder is let go, and the shell's runner runs it.
    private func picked() async {
        _ = try? await core.resumeFolderWaits()
        onFolderPicked?()
        await refresh()
    }
}

/// The account block of the settings screen with the storage choice on top of it. A build that
/// can offer neither iCloud nor a local folder draws the Drive block exactly as before; one that can
/// asks "Storage" first, in chips as the theme does, and under them draws the chosen storage's row
/// only — the same shape for each: connected, or not connected with what to do. Drive stays
/// connected after a switch away from it, and its row, with Disconnect, is back under the Google
/// Drive chip.
public struct StorageSection: View {
    @ObservedObject private var choice: StorageChoice
    private let drive: (Bool) -> DriveConnectionSection
    @Environment(\.locale) private var locale
    #if os(iOS)
    /// The Files picker for a folder is up.
    @State private var importing = false
    #endif

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
                        if choice.icloudOffered {
                            BlueprintChip("iCloud", selected: choice.selected == .icloud, fill: true) {
                                Task { await choice.select(.icloud) }
                            }
                            .accessibilityIdentifier("storage-icloud")
                        }
                        if choice.folderOffered {
                            BlueprintChip(loc("Local folder"), selected: choice.selected == .folder, fill: true) {
                                Task { await choice.select(.folder) }
                            }
                            .accessibilityIdentifier("storage-folder")
                        }
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
                } else if choice.selected == .folder {
                    folderRow
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

    /// Drive's row in shape: where the folder is, and the picker — "Change folder" while it can be
    /// reached, "Choose folder" while there is none or it is gone (docs/03 "Storage location").
    @ViewBuilder private var folderRow: some View {
        #if os(iOS)
        // The system's folder picker: a security-scoped URL, which the bookmark keeps (PickedFolder).
        folderStatus.fileImporter(isPresented: $importing, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result else { return }
            Task { await choice.pickFolder(url) }
        }
        #else
        folderStatus
        #endif
    }

    @ViewBuilder private var folderStatus: some View {
        switch choice.folder {
        case .notChosen:
            SectionRow(title: loc("No folder chosen")) { pickButton(loc("Choose folder")) }
                .accessibilityIdentifier("folder-status")
        case .available:
            SectionRow(title: choice.folderPath ?? "") { pickButton(loc("Change folder")) }
                .accessibilityIdentifier("folder-status")
        case .unavailable:
            SectionRow(title: choice.folderPath ?? "", subtitle: loc("This folder cannot be reached. Choose it again.")) {
                pickButton(loc("Choose folder"))
            }
            .accessibilityIdentifier("folder-status")
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

    private func pickButton(_ title: String) -> some View {
        BlueprintButton(title) {
            guard let path = Self.pickFolder(from: choice.folderPath) else { return }
            Task { await choice.pickFolder(path) }
        }
        .disabled(choice.busy)
        .accessibilityIdentifier("folder-pick")
    }

    /// A folder, or a new one made in the panel — never a file. Application-modal, as this app's
    /// other questions are (`BlueprintPanel`), and brought to the front for the same reason: an
    /// `LSUIElement` app is not frontmost when its menu is open.
    @MainActor private static func pickFolder(from current: String?) -> String? {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        if let current { panel.directoryURL = URL(fileURLWithPath: current, isDirectory: true) }
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        return url.path
    }
    #else
    private static let becameActive = UIApplication.didBecomeActiveNotification
    private static let unavailable =
        "In Settings → [your name] → iCloud → Drive, turn on Sync this iPhone, then turn on Recly under Saved to iCloud → See All."

    private func pickButton(_ title: String) -> some View {
        BlueprintButton(title) { importing = true }
            .disabled(choice.busy)
            .accessibilityIdentifier("folder-pick")
    }
    #endif
}
#endif
