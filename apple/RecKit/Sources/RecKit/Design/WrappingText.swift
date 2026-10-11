import Foundation
import SwiftUI

/// 2026-10-11 (N2): a badge, a node or a banner line that wraps never breaks inside a word without a
/// hyphen. SwiftUI's `Text` takes no hyphenation setting — an `NSParagraphStyle.hyphenationFactor` in an
/// `AttributedString` is ignored — so where a word is wider than the line, the text is drawn with soft
/// hyphens at the language's own hyphenation points and the line breaks at one of them, with a hyphen.
/// While every word fits, the text is drawn as it is and breaks between words only. A language with no
/// hyphenation data (Korean, Japanese, Chinese, Arabic …) is drawn as it is.
///
/// For text that wraps: its ideal width is the whole text's on one line, as a `Text`'s is. Where the text
/// may shrink (`minimumScaleFactor`, as a state node's does), a word that fits once shrunk is shrunk rather
/// than hyphenated.
public struct WrappingText: View {
    /// docs/07 rule 3: the language on screen, which is the language the text is in.
    @Environment(\.locale) private var locale
    @Environment(\.minimumScaleFactor) private var minimumScale
    private let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        let hyphenated = Hyphenation.softHyphens(text, locale: locale)
        if hyphenated == text {
            Text(verbatim: text)
        } else {
            IdealWidthLayout {
                ViewThatFits(in: .horizontal) {
                    // Asked for its ideal width, this answers with the widest word's: it "fits" — and the
                    // text is drawn as it is — exactly when no word is wider than the line.
                    WidestWordLayout(scale: minimumScale) {
                        Text(verbatim: text)
                        ForEach(Array(words.enumerated()), id: \.offset) { Text(verbatim: $0.element).hidden() }
                    }
                    Text(verbatim: hyphenated)
                        // VoiceOver reads the words, not the soft hyphens between their syllables.
                        .accessibilityLabel(Text(verbatim: text))
                }
                Text(verbatim: text).hidden()
            }
        }
    }

    private var words: [String] {
        text.split(whereSeparator: \.isWhitespace).map(String.init)
    }
}

/// The language's own hyphenation points, as soft hyphens (U+00AD): invisible unless a line breaks there.
enum Hyphenation {
    static func softHyphens(_ text: String, locale: Locale) -> String {
        let language = locale as CFLocale
        guard CFStringIsHyphenationAvailableForLocale(language) else { return text }
        let string = text as NSString
        let whole = CFRange(location: 0, length: string.length)
        var points: Set<Int> = []
        var index = string.length
        // Each call answers the last point before [index] in the word around it; a word with none sends
        // the search on to the character before.
        while index > 0 {
            var character: UTF32Char = 0
            let point = CFStringGetHyphenationLocationBeforeIndex(string, index, whole, 0, language, &character)
            if point == kCFNotFound || point <= 0 || point >= index {
                index -= 1
            } else {
                points.insert(point)
                index = point
            }
        }
        guard !points.isEmpty else { return text }
        let result = NSMutableString(string: text)
        for point in points.sorted(by: >) { result.insert("\u{00AD}", at: point) }
        return result as String
    }
}

/// [WrappingText]'s ideal size is its whole text on one line (the hidden second subview's); any other
/// proposal goes to the first.
private struct IdealWidthLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.width == nil ? subviews[1].sizeThatFits(proposal) : subviews[0].sizeThatFits(proposal)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
        subviews[1].place(at: bounds.origin, proposal: .unspecified)
    }
}

/// The text (first subview) with its words (the hidden rest): asked for an ideal width, it gives the
/// widest word's at the smallest size the text may shrink to, and the text's height at that width.
private struct WidestWordLayout: Layout {
    let scale: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard proposal.width == nil || proposal.width == .infinity else { return subviews[0].sizeThatFits(proposal) }
        let widest = subviews.dropFirst().map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        let height = subviews[0].sizeThatFits(ProposedViewSize(width: widest, height: proposal.height)).height
        return CGSize(width: widest * scale, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
        for word in subviews.dropFirst() { word.place(at: bounds.origin, proposal: .unspecified) }
    }
}
