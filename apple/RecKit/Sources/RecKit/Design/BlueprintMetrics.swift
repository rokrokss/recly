import SwiftUI

/// docs/09 "Spacing": multiples of four, with 8 / 16 / 24 as the rhythm, and the named steps between them — a
/// literal is for artwork only.
public enum Space {
    /// A label and its value.
    public static let xxs: CGFloat = 2
    public static let xs: CGFloat = 4
    public static let s: CGFloat = 8
    public static let sm: CGFloat = 12
    public static let m: CGFloat = 16
    public static let l: CGFloat = 24
    public static let xl: CGFloat = 32
}

/// docs/09 "Shape": 4 for a node, 8 for a card, 0 for a table row. Badges take half a node.
public enum Radius {
    public static let node: CGFloat = 4
    public static let card: CGFloat = 8
    public static let badge: CGFloat = 2
}

/// docs/09 "Accessibility": whatever it draws, nothing you can tap is smaller than this. A small glyph —
/// a square switch, a `×` — keeps its size and grows a target around itself.
public let minTouch: CGFloat = 44

/// docs/09 "Typography" (2026-10-15): the timer's tracking, on every shell.
public let timerTracking: CGFloat = 1

/// docs/09 "Typography": the scale is 12 / 14 / 16 / 20 / 28 / 44. Dynamic Type carries the user's own
/// size on top of it (see [BlueprintFonts.scale]).
public enum TypeSize {
    public static let small: CGFloat = 12
    public static let bodySmall: CGFloat = 14
    public static let body: CGFloat = 16
    public static let title: CGFloat = 20
    public static let headline: CGFloat = 28
    public static let timer: CGFloat = 44
}

/// docs/09 "Typography": the UI is the platform sans (SF Pro — no font is bundled, so Korean keeps its
/// glyphs) and *data* is monospace (SF Mono, through `design: .monospaced`).
///
/// [scale] is Dynamic Type, measured once at the root with `@ScaledMetric` and handed down: a
/// design where every size is a `ScaledMetric` of its own measures the same ramp six times.
public struct BlueprintFonts: Equatable, Sendable {
    public let scale: CGFloat

    public init(scale: CGFloat = 1) {
        self.scale = scale
    }

    public func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size * scale, weight: weight, design: .monospaced)
    }

    public func sans(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size * scale, weight: weight)
    }

    /// 2026-10-11 (N3): the face for *words* drawn where data is — a badge, a node's value, a status line.
    /// SF Mono has no Hangul, Kana or Han; their fallback is set in mono's cells, with mono's wide space
    /// between the words (`업로드  중`), so [text] in those scripts takes the sans at the same size.
    /// Times, counts and ids stay in [mono].
    public func mono(_ size: CGFloat, weight: Font.Weight = .regular, words text: String) -> Font {
        CJKText.contains(text) ? sans(size, weight: weight) : mono(size, weight: weight)
    }

    public var monoSmall: Font { mono(TypeSize.small) }
    public var monoBodySmall: Font { mono(TypeSize.bodySmall) }
    public var monoBody: Font { mono(TypeSize.body) }
    public var monoTitle: Font { mono(TypeSize.title) }
    /// docs/09 "Typography" (2026-10-15): regular weight on every shell; [MonoTimer] adds the 1 of tracking.
    public var monoTimer: Font { mono(TypeSize.timer) }

    /// The section headers and node kickers of the mockup: small, tracked out, never shouted.
    public var label: Font { sans(TypeSize.small, weight: .medium) }
    public var bodySmall: Font { sans(TypeSize.bodySmall) }
    public var body: Font { sans(TypeSize.body) }
    public var rowTitle: Font { sans(TypeSize.bodySmall, weight: .semibold) }
    public var title: Font { sans(TypeSize.title, weight: .semibold) }
    public var headline: Font { sans(TypeSize.headline) }
}

/// Hangul, Kana and Han, with their punctuation and full-width forms — the scripts SF Mono has no glyphs
/// for ([BlueprintFonts.mono(_:weight:words:)]).
enum CJKText {
    static func contains(_ text: String) -> Bool {
        text.unicodeScalars.contains { scalar in ranges.contains { $0.contains(scalar.value) } }
    }

    private static let ranges: [ClosedRange<UInt32>] = [
        0x1100...0x11FF, // Hangul Jamo
        0x2E80...0x2FDF, // CJK and Kangxi radicals
        0x3000...0x30FF, // CJK symbols and punctuation, Hiragana, Katakana
        0x3130...0x318F, // Hangul compatibility Jamo
        0x31F0...0x31FF, // Katakana phonetic extensions
        0x3400...0x4DBF, // CJK extension A
        0x4E00...0x9FFF, // CJK unified ideographs
        0xA960...0xA97F, // Hangul Jamo extended-A
        0xAC00...0xD7FF, // Hangul syllables, Jamo extended-B
        0xF900...0xFAFF, // CJK compatibility ideographs
        0xFF00...0xFFEF, // half-width and full-width forms
        0x20000...0x3134F, // CJK extensions B–G
    ]
}

/// docs/09 "Motion": motion is a state signal, never decoration. One easing, one duration for a normal
/// transition, a shorter one for a badge, and a deliberate window for the rare high-risk action —
/// start/stop, upload, sign-in, save — so the user sees that something happened.
public enum Motion {
    /// `ease-in-out 200ms`.
    public static let standard: Double = 0.2
    /// A status badge fading between two states.
    public static let badgeFade: Double = 0.15
    /// The processing state ("…") is on screen at least this long, even if the work was instant.
    public static let processingMin: Double = 0.4
    /// …and the processing state plus its completion badge do not run past this.
    public static let processingMax: Double = 0.8

    /// `nil` with reduce motion on: SwiftUI takes that as "change with no animation at all".
    public static func standardAnimation(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeInOut(duration: standard)
    }

    public static func badgeAnimation(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeInOut(duration: badgeFade)
    }
}
