import SwiftUI

/// docs/09 화면 원칙 2: state is never colour alone. The tone picks the colour, the code is the
/// text, and a reader who sees neither hue gets the same answer from the letters.
public enum BadgeTone: Sendable {
    case neutral
    case accent
    case success
    case warning
    case danger

    /// The letters, in an ink that clears WCAG AA on both the surface and the page — which for
    /// amber is not the same colour as the border (see `BlueprintToken.warningInk`).
    public var inkToken: BlueprintToken {
        switch self {
        case .neutral: return .textMuted
        case .accent: return .accent
        case .success: return .success
        case .warning: return .warningInk
        case .danger: return .danger
        }
    }

    /// The border, which is a graphic and so may use the documented amber rather than its dark ink.
    public var edgeToken: BlueprintToken {
        self == .warning ? .warning : inkToken
    }

    public func ink(_ palette: BlueprintPalette) -> Color { palette.color(inkToken) }

    public func edge(_ palette: BlueprintPalette) -> Color { palette.color(edgeToken) }
}

/// The code and its tone — what [LedgerRow] shows in its last column.
public struct LedgerStatus: Equatable, Sendable {
    public let code: String
    public let tone: BadgeTone

    public init(code: String, tone: BadgeTone) {
        self.code = code
        self.tone = tone
    }

    /// docs/09 화면 원칙 2: the badge is the state as a code, and the code is the same word the core
    /// and the logs use. What it *means* is [RecentItem.stateLabel], which is what VoiceOver hears.
    ///
    /// Keyed on the docs/07 key `Recents.stateLabel` produced, so the two cannot drift: a state the
    /// core grows without a code here shows as `UNKNOWN` rather than as nothing.
    public static func forRecent(state: String) -> LedgerStatus {
        switch state {
        // docs/03 "다른 기기의 녹음" · "워치 → 폰 전송 계약": three states that are not this device's
        // job and not this device's recording either — something is in flight elsewhere, which is
        // the accent's whole meaning here. They come first because two of them are `RECORDING` rows
        // and would otherwise read as `REC` (see [Recents.stateLabel], which orders them the same).
        case "Receiving from the watch": return LedgerStatus(code: "RECEIVING", tone: .accent)
        case "Uploading on another device": return LedgerStatus(code: "UPLOADING", tone: .accent)
        case "Transcribing on another device":
            return LedgerStatus(code: "TRANSCRIBING", tone: .accent)
        case "Recording": return LedgerStatus(code: "REC", tone: .danger)
        case "No workflow": return LedgerStatus(code: "NO_JOB", tone: .neutral)
        case "Waiting": return LedgerStatus(code: "PENDING", tone: .neutral)
        case "Uploading": return LedgerStatus(code: "UPLOADING", tone: .accent)
        case "Retry pending": return LedgerStatus(code: "RETRY", tone: .warning)
        case "Done": return LedgerStatus(code: "DONE", tone: .success)
        case "Failed": return LedgerStatus(code: "FAILED", tone: .danger)
        case "Sign-in needed": return LedgerStatus(code: "NEEDS_AUTH", tone: .warning)
        // docs/10 "Drive 용량 초과": a state of its own and not a failure — a retry is not what
        // clears it, and the row says so.
        case "No space in Drive": return LedgerStatus(code: "NO_SPACE", tone: .warning)
        case "Too short": return LedgerStatus(code: "SKIPPED", tone: .neutral)
        default: return LedgerStatus(code: "UNKNOWN", tone: .neutral)
        }
    }
}

/// The same chip as [StatusBadge], as something to press: what a ledger row offers *about* the
/// recording, on the line the state is already on. A [BlueprintButton] is the size of a button and
/// would be a second row's worth of height here; this is the size of the badge it sits beside.
///
/// Its own control rather than a modifier on the badge, because the two say different things — one
/// is what the recording is, the other is what can be done to it.
public struct BadgeButton: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.isEnabled) private var isEnabled
    private let label: String
    private let tone: BadgeTone
    private let action: () -> Void

    public init(_ label: String, tone: BadgeTone, action: @escaping () -> Void) {
        self.label = label
        self.tone = tone
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(verbatim: label)
                .font(blueprint.fonts.monoSmall)
                .foregroundStyle(ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.badge)
                        .strokeBorder(edge, lineWidth: blueprint.line)
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(.isButton)
    }

    private var ink: Color {
        isEnabled ? tone.ink(blueprint.palette) : blueprint.palette.textMuted
    }

    private var edge: Color {
        isEnabled ? tone.edge(blueprint.palette) : blueprint.palette.grid
    }
}

/// A square badge: 1pt of the tone (2pt in high contrast), the code in monospace, on the surface.
public struct StatusBadge: View {
    @Environment(\.blueprint) private var blueprint
    private let status: LedgerStatus

    public init(_ status: LedgerStatus) {
        self.status = status
    }

    public var body: some View {
        Text(verbatim: status.code)
            .font(blueprint.fonts.monoSmall)
            .foregroundStyle(status.tone.ink(blueprint.palette))
            .lineLimit(1)
            // A code that is truncated is not a code any more. The longest of them is
            // `TRANSCRIBING`, which the ledger's status column is cut for — but the user's own type
            // size can still outgrow it, and shrinking the letters keeps them readable where
            // clipping does not.
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .overlay {
                RoundedRectangle(cornerRadius: Radius.badge)
                    .strokeBorder(status.tone.edge(blueprint.palette), lineWidth: blueprint.line)
            }
    }
}
