import AppKit
import RecKit
import SwiftUI

/// docs/12 "Updates": the settings' Updates section — whether Recly looks on its own, and one row whose title is the
/// news and whose control is what can be done about it. The Windows settings draw the same section (docs/14 "Updates").
struct UpdateSection: View {
    @ObservedObject var updater: DesktopUpdater
    /// The recorder is idle: Restart and install never interrupts a recording being made or saved.
    let idle: Bool
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        SectionHeader(loc("Updates")).padding(.horizontal, Space.m)
        SwitchRow(
            title: loc("Check for updates automatically"),
            subtitle: loc("At launch and every six hours."),
            isOn: $updater.checksAutomatically
        )
        SectionRow(
            title: UpdateWords.title(updater.status),
            subtitle: subtitle?.text,
            subtitleColor: subtitle?.warning == true ? BadgeTone.warning.ink(blueprint.palette) : nil,
            link: releasePage
        ) {
            UpdateAction(updater: updater, idle: idle, compact: false)
        }
    }

    private var subtitle: (text: String, warning: Bool)? {
        UpdateWords.subtitle(updater.status, idle: idle)
    }

    /// A copy that cannot replace itself where it is is sent to the release's page instead.
    private var releasePage: TextLink? {
        guard case .failed(.location, let update?) = updater.status else { return nil }
        return TextLink(loc("Open release page"), small: true) { NSWorkspace.shared.open(update.notes) }
    }
}

/// docs/12 "Updates": the popover's line above the ledger while an update is there to install — the news and Restart
/// and install at the status badge's size, as the banner's actions are (docs/09 "Component measurements"). Nothing for
/// a check that failed or found nothing: that is the settings' to say.
struct UpdateLine: View {
    @ObservedObject var updater: DesktopUpdater
    let idle: Bool
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        if updater.offered != nil {
            VStack(spacing: 0) {
                HairLine()
                HStack(spacing: Space.s) {
                    VStack(alignment: .leading, spacing: Space.xxs) {
                        WrappingText(UpdateWords.title(updater.status))
                            .font(blueprint.fonts.bodySmall)
                            .foregroundStyle(blueprint.palette.text)
                        if let subtitle = UpdateWords.subtitle(updater.status, idle: idle) {
                            WrappingText(subtitle.text)
                                .font(blueprint.fonts.sans(TypeSize.small))
                                .foregroundStyle(subtitle.warning ? BadgeTone.warning.ink(blueprint.palette) : blueprint.palette.textMuted)
                        }
                        if case .failed(.location, let update?) = updater.status {
                            TextLink(loc("Open release page"), small: true) { NSWorkspace.shared.open(update.notes) }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    UpdateAction(updater: updater, idle: idle, compact: true)
                }
                .padding(.horizontal, Space.m)
                .padding(.vertical, Space.s)
                HairLine()
            }
            .background(blueprint.palette.surface)
        }
    }
}

/// The control the row and the line share: Check now, Restart and install, or what is running instead of it.
private struct UpdateAction: View {
    @ObservedObject var updater: DesktopUpdater
    let idle: Bool
    let compact: Bool
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        switch updater.status {
        case .checking:
            LoadingText(text: loc("Checking…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
        case .installing:
            LoadingText(text: loc("Installing…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
        case .failed(.location, _), .failed(.system, _):
            // The release page, or a newer macOS, is the way on; there is nothing here to try again.
            EmptyView()
        case .available, .failed(_, .some(_)):
            BlueprintButton(loc("Restart and install"), tone: .primary, compact: compact) {
                updater.install(idle: idle) { NSApplication.shared.terminate(nil) }
            }
            .disabled(!idle)
            .accessibilityIdentifier("update-install")
        case .idle, .upToDate, .failed(_, .none):
            BlueprintButton(loc("Check now"), tone: .quiet, compact: compact) {
                Task { await updater.check() }
            }
            .accessibilityIdentifier("update-check")
        }
    }
}

/// The words for each state, in one place so the row and the line never say it two ways.
enum UpdateWords {
    static func title(_ status: DesktopUpdater.Status) -> String {
        switch status {
        case .upToDate:
            loc("Recly is up to date.")
        case .available(let update), .installing(let update), .failed(_, .some(let update)):
            loc("Recly %@ is available.", update.version)
        case .idle, .checking, .failed(_, .none):
            "Recly \(CoreBridge.appVersion)"
        }
    }

    static func subtitle(_ status: DesktopUpdater.Status, idle: Bool) -> (text: String, warning: Bool)? {
        switch status {
        case .failed(let failure, _):
            (reason(failure), true)
        case .available where !idle:
            (loc("You can install it once the recording is saved."), false)
        default:
            nil
        }
    }

    static func reason(_ failure: UpdateFailure) -> String {
        switch failure {
        case .check: loc("Could not check for updates.")
        case .download: loc("Could not download the update.")
        case .signature: loc("The update's signature did not match, so it was not installed.")
        case .install: loc("Could not install the update.")
        case .location: loc("Recly cannot replace itself where it is. Download the new version from the release page.")
        case .system: loc("This update needs a newer macOS.")
        }
    }
}
