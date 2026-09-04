import SwiftUI

/// docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the same lines the notifications carry, at the top
/// of the list the recordings they are about are in — the phone's list, the Mac's popover. The
/// Android banner is the same component.
public struct AlertBanner: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    private let alerts: [JobAlert]
    private let fix: (JobAlert) -> Void

    public init(alerts: [JobAlert], fix: @escaping (JobAlert) -> Void) {
        self.alerts = alerts
        self.fix = fix
    }

    public var body: some View {
        if !alerts.isEmpty {
            VStack(spacing: 0) {
                HairLine()
                ForEach(alerts) { alert in
                    HStack(spacing: Space.s) {
                        Button { fix(alert) } label: {
                            HStack(spacing: Space.s) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: alert.reason.label)
                                        .font(blueprint.fonts.bodySmall)
                                        .foregroundStyle(blueprint.palette.danger)
                                    Text(verbatim: alert.waiting)
                                        .font(blueprint.fonts.sans(TypeSize.small))
                                        .foregroundStyle(blueprint.palette.textMuted)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                StatusBadge(LedgerStatus(code: alert.reason.code, tone: .warning))
                            }
                            .padding(.leading, Space.m)
                            .padding(.vertical, Space.s)
                            .frame(minHeight: minTouch)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        // docs/09 "접근성": one node with a sentence in it, not a reason, a count and
                        // a code read out as three separate things (the same rule as `LedgerRow`).
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(verbatim: "\(alert.reason.label) \(alert.waiting)"))
                        .accessibilityAddTraits(.isButton)
                        // docs/10: "탭하면 고칠 수 있는 화면으로 간다". The row goes there when it is
                        // pressed, but only the button says *where* — a line that is tappable
                        // without saying what the tap opens is a fix the user has to guess at, and
                        // the Windows banner has named its surface all along.
                        BlueprintButton(alert.reason.fix.label) { fix(alert) }
                            .accessibilityIdentifier("alert-fix")
                    }
                    .padding(.trailing, Space.m)
                }
                HairLine()
            }
            .background(blueprint.palette.surface)
            .accessibilityIdentifier("alert-banner")
        }
    }
}
