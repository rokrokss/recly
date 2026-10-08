import SwiftUI

/// docs/09 screen principle 2: state is never colour alone. The tone picks the colour, the word is the
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

/// The state and its tone — what [LedgerRow] shows in its last column. The code is the core's and the
/// logs' word and never shown; the badge draws [label], the code as a translated word (2026-10-08 §1a).
public struct LedgerStatus: Equatable, Sendable {
    public let code: String
    public let tone: BadgeTone
    /// The catalog key of the word, where one code is more than one word (`WAITING`: iCloud or the
    /// local folder). Nil reads the word off the code.
    private let word: String?

    public init(code: String, tone: BadgeTone, word: String? = nil) {
        self.code = code
        self.tone = tone
        self.word = word
    }

    /// The word the badge draws, read where it is drawn so a language change reaches a badge already
    /// on screen (docs/07 rule 3). A code the table below does not name is shown as it stands.
    public var label: String {
        guard let key = word ?? Self.words[code] else { return code }
        return RecKitStrings.localized(key)
    }

    /// 2026-10-08 §1a: one word per code, in RecKit's catalog. `ICLOUD_STORAGE_FULL` and `NEEDS_SPACE`
    /// are the banner's codes for the ledger's `NO_SPACE`; the banner's failure codes (a missing key, a
    /// refused key, a quota) are failures, so they say so.
    static let words: [String: String] = [
        "DONE": "Done",
        "FAILED": "Failed",
        "RETRY": "Retrying",
        "PENDING": "Waiting",
        "UPLOADING": "Uploading",
        "RECEIVING": "Receiving",
        "TRANSCRIBING": "Transcribing",
        "IMPORTING": "Importing",
        "REC": "Recording",
        "NEEDS_AUTH": "Waiting for Drive",
        "NEEDS_CONSENT": "Needs permission",
        "NEEDS_MODEL": "Waiting for model",
        "NEEDS_SPACE": "Storage full",
        "NO_SPACE": "Storage full",
        "ICLOUD_STORAGE_FULL": "Storage full",
        "SKIPPED": "Too short",
        "WAITING": "Waiting for iCloud",
        "UNKNOWN": "Unknown",
        "MISSING_SECRET": "Failed",
        "AUTH_REJECTED": "Failed",
        "QUOTA": "Failed",
        "LOCAL_TRANSCRIPTION_UNAVAILABLE": "Failed",
        "LOCAL_DIARIZATION_UNAVAILABLE": "Failed",
    ]

    /// Every badge [forRecent] and [RecentItem.badge] can mint — what the ledger's status column is
    /// measured against, in the language on screen, so the widest of the words fits at full size.
    public static let ledgerStatuses: [LedgerStatus] = [
        "IMPORTING", "RECEIVING", "UPLOADING", "PENDING", "TRANSCRIBING", "REC", "RETRY", "DONE", "FAILED",
        "NEEDS_CONSENT", "NEEDS_MODEL", "NEEDS_AUTH", "NO_SPACE", "SKIPPED", "UNKNOWN", "WAITING",
    ].map { LedgerStatus(code: $0, tone: .neutral) } + [waitingForFolder]

    /// The codes of [ledgerStatuses].
    public static var ledgerCodes: [String] { ledgerStatuses.map(\.code) }

    /// docs/03 "Storage location": a local folder that cannot be reached waits the way iCloud does — the
    /// same code, its own word.
    static let waitingForFolder = LedgerStatus(code: "WAITING", tone: .neutral, word: "Waiting for folder")

    /// docs/09 screen principle 2: the badge is the state in one word, minted from the code the core and
    /// the logs use. What it *means* is [RecentItem.stateLabel], which is what VoiceOver hears.
    ///
    /// Keyed on the docs/07 key `Recents.stateLabel` produced, so the two cannot drift: a state the
    /// core grows without a code here shows as `UNKNOWN` rather than as nothing.
    public static func forRecent(state: String) -> LedgerStatus {
        switch state {
        // docs/03 "Recordings from other devices" · "Watch → phone transfer contract": three states that are not this device's
        // job and not this device's recording either — something is in flight elsewhere, which is
        // the accent's whole meaning here. They come first because two of them are `RECORDING` rows
        // and would otherwise read as `REC` (see [Recents.stateLabel], which orders them the same).
        case "Receiving from the watch": return LedgerStatus(code: "RECEIVING", tone: .accent)
        // docs/03 "Naming rules": a picked file still being made into parts.
        case "Importing": return LedgerStatus(code: "IMPORTING", tone: .accent)
        case "Uploading on another device": return LedgerStatus(code: "UPLOADING", tone: .accent)
        case "Transcription pending", "Waiting for the device to cool down": return LedgerStatus(code: "PENDING", tone: .neutral)
        case "Transcribing on another device", "Transcribing on this device":
            return LedgerStatus(code: "TRANSCRIBING", tone: .accent)
        case "Recording": return LedgerStatus(code: "REC", tone: .danger)
        case "Waiting": return LedgerStatus(code: "PENDING", tone: .neutral)
        case "Uploading": return LedgerStatus(code: "UPLOADING", tone: .accent)
        case "Retry pending": return LedgerStatus(code: "RETRY", tone: .warning)
        case "Done": return LedgerStatus(code: "DONE", tone: .success)
        case "Failed": return LedgerStatus(code: "FAILED", tone: .danger)
        case "Transfer permission needed": return LedgerStatus(code: "NEEDS_CONSENT", tone: .warning)
        case "Waiting for speech model": return LedgerStatus(code: "NEEDS_MODEL", tone: .warning)
        case "Sign-in needed": return LedgerStatus(code: "NEEDS_AUTH", tone: .neutral)
        // docs/10 "Drive out of space": a state of its own and not a failure — a retry is not what
        // clears it, and the row says so.
        case "No space in Drive", "No space in iCloud": return LedgerStatus(code: "NO_SPACE", tone: .warning)
        // docs/03 "Storage location": iCloud is uploading on its own schedule — in flight, like a running upload.
        case "Uploading to iCloud": return LedgerStatus(code: "UPLOADING", tone: .accent)
        case "Waiting for iCloud": return LedgerStatus(code: "WAITING", tone: .warning)
        case "Waiting for the local folder": return LedgerStatus(code: "WAITING", tone: .warning, word: "Waiting for folder")
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

/// A square badge: 1pt of the tone (2pt in high contrast), the state's word in monospace, on the surface.
public struct StatusBadge: View {
    @Environment(\.blueprint) private var blueprint
    private let status: LedgerStatus
    private let wraps: Bool
    /// The text in place of the status's word — only the ledger's measure of its cap uses it.
    private let verbatim: String?

    /// - Parameter wraps: take the width the ledger's status column gives it and wrap the word onto
    ///   further lines, centred, when the word is wider than that (2026-10-08 badge column rule).
    ///   Elsewhere the badge is its own width on one line.
    public init(_ status: LedgerStatus, wraps: Bool = false) {
        self.status = status
        self.wraps = wraps
        verbatim = nil
    }

    /// A badge-sized piece of text, for measuring: the ledger's status column is capped at what the
    /// old code `TRANSCRIBING` took.
    init(measuring text: String) {
        status = LedgerStatus(code: text, tone: .neutral)
        wraps = false
        verbatim = text
    }

    public var body: some View {
        let words = HStack(spacing: Space.xs) {
            // docs/09 "Import": an import is work with no percentage — the one loader, in the badge's ink.
            if status.code == "IMPORTING" { BlueprintLoader(color: status.tone.ink(blueprint.palette)) }
            Text(verbatim: verbatim ?? status.label)
        }
            .font(blueprint.fonts.monoSmall)
            .foregroundStyle(status.tone.ink(blueprint.palette))
            .multilineTextAlignment(.center)
        return Group {
            if wraps {
                // A word wider than the column goes onto a second line rather than being cut or
                // shrunk: every letter is still read, at full size. Its own line limit, because the
                // ledger row puts its columns on one line each.
                words.lineLimit(nil).fixedSize(horizontal: false, vertical: true)
            } else {
                // A word that is truncated or shrunk is not read any more: the badge is its own width.
                words.lineLimit(1).fixedSize()
            }
        }
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .overlay {
                RoundedRectangle(cornerRadius: Radius.badge)
                    .strokeBorder(status.tone.edge(blueprint.palette), lineWidth: blueprint.line)
            }
    }
}
