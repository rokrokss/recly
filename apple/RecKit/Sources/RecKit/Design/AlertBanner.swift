import SwiftUI

/// docs/10 "Failures the user can fix, and their notices": one notice per reason above the recordings.
/// Drive connection uses a neutral upload count and one action, without repeating the reason.
public struct AlertBanner: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    private let alerts: [JobAlert]
    /// The shell's model download (docs/05 "Fixed processing settings"). While it runs, the line of the
    /// recordings waiting for the model is its progress and its button cancels it.
    private let download: ModelDownload?
    private let fix: (JobAlert) -> Void

    public init(alerts: [JobAlert], download: ModelDownload? = nil, fix: @escaping (JobAlert) -> Void) {
        self.alerts = alerts
        self.download = download
        self.fix = fix
    }

    public var body: some View {
        if !alerts.isEmpty {
            VStack(spacing: 0) {
                HairLine()
                ForEach(alerts) { alert in
                    if alert.reason == .needsAuth {
                        HStack(spacing: Space.s) {
                            Text(verbatim: RecKitStrings.localized("alert.uploadsWaiting", String(alert.count)))
                                .font(blueprint.fonts.bodySmall)
                                .foregroundStyle(blueprint.palette.textMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            BlueprintButton(RecKitStrings.localized("Connect Drive")) { fix(alert) }
                                .accessibilityIdentifier("alert-fix")
                        }
                        .padding(.horizontal, Space.m)
                        .padding(.vertical, Space.s)
                    } else if alert.reason.fix == .modelDownload, let download {
                        ModelWaitLine(alert: alert, download: download, fix: fix)
                    } else {
                        AlertLine(alert: alert, line: alert.reason.label, fix: fix) {
                            BlueprintButton(alert.reason.fix.label) { fix(alert) }
                        }
                    }
                }
                HairLine()
            }
            .background(blueprint.palette.surface)
            .accessibilityIdentifier("alert-banner")
        }
    }
}

/// The recordings waiting for the speech model: the reason and its download, or — while the one
/// download runs — how far it is and the way to stop it. Never "not downloaded yet" then.
private struct ModelWaitLine: View {
    let alert: JobAlert
    @ObservedObject var download: ModelDownload
    let fix: (JobAlert) -> Void

    var body: some View {
        if download.downloading {
            AlertLine(alert: alert, line: ModelDownload.progressText(download.progress), fix: { _ in }) {
                BlueprintButton(RecKitStrings.localized("Cancel download"), tone: .quiet) { download.cancel() }
            }
        } else {
            AlertLine(alert: alert, line: alert.reason.label, fix: fix) {
                BlueprintButton(alert.reason.fix.label) { fix(alert) }
                    .disabled(download.capturing)
            }
        }
    }
}

/// One reason: what it is, how many recordings are behind it, the code, and the fix. The line is
/// red for a failure and the badge's warning tone for a job that is only waiting (docs/09
/// "Every state is color + text": red means failed).
private struct AlertLine<Action: View>: View {
    @Environment(\.blueprint) private var blueprint
    let alert: JobAlert
    let line: String
    let fix: (JobAlert) -> Void
    @ViewBuilder let action: () -> Action

    var body: some View {
        HStack(spacing: Space.s) {
            Button { fix(alert) } label: {
                HStack(spacing: Space.s) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: line)
                            .font(blueprint.fonts.bodySmall)
                            .foregroundStyle(alert.reason.isWait ? BadgeTone.warning.ink(blueprint.palette) : blueprint.palette.danger)
                        Text(verbatim: alert.waiting)
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // 2026-10-10 (A-A25): a badge that says Failed is in the failure's red, as its line is.
                    StatusBadge(LedgerStatus(code: alert.reason.code, tone: alert.reason.isWait ? .warning : .danger))
                }
                .padding(.leading, Space.m)
                .padding(.vertical, Space.s)
                .frame(minHeight: minTouch)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // docs/09 "Accessibility": one node with a sentence in it, not a reason, a count and
            // a code read out as three separate things (the same rule as `LedgerRow`).
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: "\(line) \(alert.waiting)"))
            .accessibilityAddTraits(.isButton)
            // docs/10: "A tap goes to the screen that can fix it". The row goes there when it is
            // pressed, but only the button says *where* — a line that is tappable
            // without saying what the tap opens is a fix the user has to guess at, and
            // the Windows banner has named its surface all along.
            action()
                .accessibilityIdentifier("alert-fix")
        }
        .padding(.trailing, Space.m)
    }
}
