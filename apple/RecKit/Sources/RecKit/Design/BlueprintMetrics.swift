import SwiftUI

/// docs/09 "간격": multiples of four, with 8 / 16 / 24 as the rhythm.
public enum Space {
    public static let xs: CGFloat = 4
    public static let s: CGFloat = 8
    public static let m: CGFloat = 16
    public static let l: CGFloat = 24
    public static let xl: CGFloat = 32
}

/// docs/09 "형태": 4 for a node, 8 for a card, 0 for a table row. Badges take half a node.
public enum Radius {
    public static let node: CGFloat = 4
    public static let card: CGFloat = 8
    public static let badge: CGFloat = 2
}

/// docs/09 "접근성": whatever it draws, nothing you can tap is smaller than this. A small glyph —
/// the connector's `+`, a square switch — keeps its size and grows a target around itself.
public let minTouch: CGFloat = 44

/// docs/09 "타이포": the scale is 12 / 14 / 16 / 20 / 28 / 44. Dynamic Type carries the user's own
/// size on top of it (see [BlueprintFonts.scale]).
public enum TypeSize {
    public static let small: CGFloat = 12
    public static let bodySmall: CGFloat = 14
    public static let body: CGFloat = 16
    public static let title: CGFloat = 20
    public static let headline: CGFloat = 28
    public static let timer: CGFloat = 44
}

/// docs/09 "타이포": the UI is the platform sans (SF Pro — no font is bundled, so Korean keeps its
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

    public var monoSmall: Font { mono(TypeSize.small) }
    public var monoBodySmall: Font { mono(TypeSize.bodySmall) }
    public var monoBody: Font { mono(TypeSize.body) }
    public var monoTitle: Font { mono(TypeSize.title) }
    public var monoTimer: Font { mono(TypeSize.timer, weight: .medium) }

    /// The section headers and node kickers of the mockup: small, tracked out, never shouted.
    public var label: Font { sans(TypeSize.small, weight: .medium) }
    public var bodySmall: Font { sans(TypeSize.bodySmall) }
    public var body: Font { sans(TypeSize.body) }
    public var rowTitle: Font { sans(TypeSize.bodySmall, weight: .semibold) }
    public var title: Font { sans(TypeSize.title, weight: .semibold) }
    public var headline: Font { sans(TypeSize.headline) }
}

/// docs/09 "모션": motion is a state signal, never decoration. One easing, one duration for a normal
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
