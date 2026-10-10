#if os(macOS)
import SwiftUI

/// docs/09 "Fields" (2026-10-10, A-A6): a multi-line field on the Mac in which Return is a new line — a text view,
/// as the summary's editor is, because a `TextField` there takes Return as its submit and leaves the new line to
/// ⌥Return. It grows with what is typed from [lines]'s lower bound to its upper one, then scrolls, and says its
/// placeholder in the muted ink while it is empty. The caller gives it the font, the box and the padding around it.
struct GrowingTextEditor: View {
    @Binding var text: String
    let placeholder: String
    let lines: ClosedRange<Int>
    /// The caller's focus, on the text view itself — what a save when editing ends follows. Nil where nothing does.
    var focused: FocusState<Bool>.Binding?
    /// What Return does instead of a new line, where it does something else (the Ask field asks); Shift+Return is
    /// the new line then. Nil keeps Return a new line.
    var onReturn: (() -> Void)?
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        // The text laid out as the view would lay it, unseen, is what sets the height; the text view is laid over it.
        Text(verbatim: sizing)
            .lineLimit(lines.upperBound)
            // Its own height, never less: a stack short of room would otherwise take lines off it, and the box
            // with them.
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Self.inset)
            .frame(maxWidth: .infinity, alignment: .leading)
            .hidden()
            .overlay {
                if let focused { editor.focused(focused) } else { editor }
            }
            .overlay(alignment: .topLeading) {
                if text.isEmpty {
                    Text(verbatim: placeholder)
                        .foregroundStyle(blueprint.palette.textMuted)
                        .padding(.horizontal, Self.inset)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
    }

    private var editor: some View {
        TextEditor(text: $text)
            .scrollContentBackground(.hidden)
            .onKeyPress(keys: [.return]) { press in
                guard let onReturn, !press.modifiers.contains(.shift) else { return .ignored }
                onReturn()
                return .handled
            }
    }

    /// What the text view keeps of its own at each side (its line fragment padding).
    static let inset: CGFloat = 5

    /// [text] with at least [lines]'s lower bound of lines, and a last line that is empty still counted. The filler
    /// is a letter, never seen: a `Text` drops the whitespace a line of nothing but a space would be.
    private var sizing: String {
        var shown = text.isEmpty || text.hasSuffix("\n") ? text + "X" : text
        let count = shown.components(separatedBy: "\n").count
        if count < lines.lowerBound { shown += String(repeating: "\nX", count: lines.lowerBound - count) }
        return shown
    }
}
#endif
