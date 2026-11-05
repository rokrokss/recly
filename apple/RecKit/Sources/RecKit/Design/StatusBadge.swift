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

/// The state and its tone — what [LedgerRow] shows under a row's title. The code is the core's and the
/// logs' word and never shown; the badge draws [label], the code as a translated word (2026-10-08 §1a).
public struct LedgerStatus: Equatable, Sendable {
    public let code: String
    public let tone: BadgeTone

    /// docs/09 screen principle 2 (2026-11-04): whether a row wears its badge — every state but `DONE`. A `Done` on
    /// every finished row said nothing; the rows with something going on or to fix are the ones that say so.
    public var shown: Bool { code != "DONE" }
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

    /// 2026-10-08 §1a: one word per code, in RecKit's catalog.
    static let words: [String: String] = [
        "DONE": "Done",
        "FAILED": "Failed",
        "RETRY": "Retrying",
        "PENDING": "Waiting",
        "UPLOADING": "Uploading",
        "RECEIVING": "Receiving",
        "TRANSCRIBING": "Transcribing",
        "FILLING": "Filling in",
        "ORGANIZING": "Summarizing",
        "IMPORTING": "Importing",
        "REC": "Recording",
        "NEEDS_AUTH": "Waiting for Drive",
        "NEEDS_CONSENT": "Needs permission",
        "NEEDS_MODEL": "Waiting for model",
        "NO_SPACE": "Storage full",
        "SKIPPED": "Too short",
        "WAITING": "Waiting for iCloud",
        "UNKNOWN": "Unknown",
    ]

    /// docs/09 screen principle 2 · docs/08 "Titles and speaker names": ChatGPT is filling in the recording's title or
    /// speaker names — work in flight, so accent, with the loader.
    public static let filling = LedgerStatus(code: "FILLING", tone: .accent)

    /// docs/09 screen principle 2 · docs/08 "Organizing": a recording that is done, its notes being organized now — the
    /// detail's `Organizing…` on its row. Accent, with the loader, as a fill is.
    public static let organizing = LedgerStatus(code: "ORGANIZING", tone: .accent)

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

/// A square badge: 1pt of the tone (2pt in high contrast), the state's word in monospace, on the surface.
public struct StatusBadge: View {
    @Environment(\.blueprint) private var blueprint
    private let status: LedgerStatus
    private let wraps: Bool

    /// - Parameter wraps: its own width on one line while the word fits the width it is given, and the word wrapped
    ///   onto further lines, centred, when it is wider than that — the ledger row's badge under a narrow title column.
    ///   Elsewhere the badge is its own width on one line.
    public init(_ status: LedgerStatus, wraps: Bool = false) {
        self.status = status
        self.wraps = wraps
    }

    public var body: some View {
        let label = status.label
        let words = HStack(spacing: Space.xs) {
            // docs/09 "Import": an import is work with no percentage — the one loader, in the badge's ink. So are a fill
            // and the notes being organized (docs/09 screen principle 2).
            if status.code == "IMPORTING" || status.code == LedgerStatus.filling.code || status.code == LedgerStatus.organizing.code {
                BlueprintLoader(color: status.tone.ink(blueprint.palette))
            }
            // 2026-10-11 (N2): a word wider than the width it has breaks at a hyphen, never between two letters.
            if wraps { WrappingText(label) } else { Text(verbatim: label) }
        }
            // 2026-10-11 (N3): Hangul, Kana and Han in the sans.
            .font(blueprint.fonts.mono(TypeSize.small, words: label))
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
            // docs/09 "Component measurements" — Status badge: padding 8 × 4, radius 2.
            .padding(.horizontal, Space.s)
            .padding(.vertical, Space.xs)
            .overlay {
                RoundedRectangle(cornerRadius: Radius.badge)
                    .strokeBorder(status.tone.edge(blueprint.palette), lineWidth: blueprint.line)
            }
    }
}
