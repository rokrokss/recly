import SwiftUI

/// 2026-10-10 (2.13): text the user or ChatGPT wrote — a transcript, a summary, an answer, an editor's words — is
/// laid out in its own direction, whatever the app's is: an English summary in the Arabic app starts at the left,
/// and an Arabic transcript in the English app at the right. The first letter that has a direction decides, as
/// Unicode's paragraph rule does; text with none (digits, times) follows the app.
enum ContentDirection {
    static func of(_ text: String) -> LayoutDirection? {
        for scalar in text.unicodeScalars where scalar.properties.isAlphabetic {
            return rightToLeft(scalar) ? .rightToLeft : .leftToRight
        }
        return nil
    }

    /// Hebrew, Arabic, Syriac, Thaana, N'Ko and the rest of the right-to-left blocks, with their presentation forms.
    private static func rightToLeft(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF, 0x10800...0x10FFF, 0x1E800...0x1EFFF: return true
        default: return false
        }
    }
}

extension View {
    /// The view laid out in [text]'s own direction ([ContentDirection]), across the width it is given so its start
    /// is that direction's start.
    func contentDirection(_ text: String) -> some View {
        modifier(ContentDirectionModifier(direction: ContentDirection.of(text)))
    }

    /// Times, the speed and diagnostics read left to right in every language — `1×`, not `×1`.
    func leftToRight() -> some View {
        environment(\.layoutDirection, .leftToRight)
    }
}

private struct ContentDirectionModifier: ViewModifier {
    @Environment(\.layoutDirection) private var app
    let direction: LayoutDirection?

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .environment(\.layoutDirection, direction ?? app)
    }
}
