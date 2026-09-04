import SwiftUI

/// docs/09 화면 원칙 5 ("제목 + 설명 + 최대 2개 버튼"), drawn the way the rest of the app is drawn: a
/// square-cornered node on the grid, not the platform's own alert. `alert` and `confirmationDialog`
/// are containers with their own shape, their own material and their own corner radius, and none of
/// those is in docs/09 — so this is the same surface, [Blueprint.line] border and [Radius.node]
/// corner as `StateNode`, with no shadow.
///
/// The Android shell's `BlueprintDialog` is the same component, and this mirrors it line for line:
/// the body scrolls on its own so a long one (the disconnect warnings) never pushes the answers off
/// the screen, and the answers stack when a row of them no longer fits (docs/09 유동 타이포).
///
/// There is no motion to reduce: nothing here animates.
public struct BlueprintDialog<Actions: View, Content: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let title: String
    private let actions: Actions
    private let content: Content

    public init(
        title: String,
        @ViewBuilder actions: () -> Actions,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.actions = actions()
        self.content = content()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(verbatim: title)
                .font(blueprint.fonts.title)
                .foregroundStyle(blueprint.palette.text)
                // docs/09 유동 타이포: three lines is what the header may take. Every title here is
                // a short question; the one thing that can outgrow it is the user's own recording
                // name inside "Delete ‘…’?", and cutting *that* in the middle is what keeps the
                // question itself — its first words and its question mark — readable.
                .lineLimit(Self.titleLines)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
            // The card is as tall as its question and no taller: a two-line dialog is a two-line
            // card. What scrolls when the body outgrows the screen is the page it sits on
            // ([BlueprintDialogSheet]), not a region inside it — a scroll view here would claim
            // every point the sheet offered and leave the card stretched to the bottom of it.
            VStack(alignment: .leading, spacing: Space.s) {
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // docs/09 "접근성" · 유동 타이포: the answers go across while they fit and downwards
            // when they do not — an answer clipped to a syllable makes the question unanswerable.
            // Stacked they keep their order, which puts the primary one at the bottom: last, as it
            // is last on the right, and nearest the thumb.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Space.s) {
                    Spacer(minLength: 0)
                    actions
                }
                VStack(spacing: Space.s) {
                    actions
                }
            }
        }
        .padding(Space.m)
        .frame(maxWidth: Self.maxWidth)
        .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node)
                .strokeBorder(blueprint.palette.grid, lineWidth: blueprint.line)
        }
    }

    /// How much of a long question stays in the header.
    static var titleLines: Int { 3 }

    /// Wide enough for the disconnect warnings, narrow enough that a dialog on a Mac window still
    /// reads as something sitting *on* the screen rather than as another screen.
    static var maxWidth: CGFloat { 420 }
}

extension View {
    /// The dialog as the platform presents a modal: a sheet, over the dot grid, with the card in
    /// the middle of it. The one presentation both shells use for a question that has to be
    /// answered before anything else happens.
    public func blueprintDialog<Content: View>(
        isPresented: Binding<Bool>,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        sheet(isPresented: isPresented) {
            BlueprintDialogSheet { content() }
        }
    }

    /// The same, driven by the thing the dialog is about — which is also what carries the counts it
    /// has to say (how many parts, how many recordings).
    public func blueprintDialog<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        sheet(item: item) { value in
            BlueprintDialogSheet { content(value) }
        }
    }
}

extension View {
    /// The dialog drawn *in* the screen rather than presented over it: a scrim across what the
    /// question is about, and the card on top of it.
    ///
    /// For the two places a sheet cannot go. A view may host one sheet, and a screen that already
    /// has one — the record screen's naming prompt, which is a different question with a different
    /// answer — has no second one to give. And a `LSUIElement` menu-bar app has no window at all:
    /// its popover *is* the surface, so its dialogs are drawn inside it.
    public func blueprintDialogOverlay<Content: View>(
        isPresented: Binding<Bool>,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        overlay {
            if isPresented.wrappedValue {
                BlueprintDialogScrim { content() }
            }
        }
    }
}

/// The dark behind a dialog drawn inside a screen: it dims what the question is about without
/// hiding it, and swallows the taps that would otherwise reach what is underneath.
///
/// docs/09 트렌드 7 keeps material for chrome, so this is a flat wash of the page colour rather than
/// a blur — there is nothing here for glass to be the chrome of.
public struct BlueprintDialogScrim<Content: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        ZStack {
            blueprint.palette.background.opacity(0.86)
            ScrollView {
                content.padding(Space.m).frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .contentShape(Rectangle())
        // The scrim is the dialog: a screen reader that could still reach the list behind it would
        // be reading a screen the question has taken over.
        .accessibilityAddTraits(.isModal)
    }
}

/// The page a [BlueprintDialog] is presented on: the dot grid, and the card centred on it.
public struct BlueprintDialogSheet<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        ScrollView {
            content
                .padding(Space.m)
                .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dotGridBackground()
        #if os(iOS)
        .presentationDetents([.medium, .large])
        #else
        .frame(minWidth: 460, minHeight: 300)
        #endif
    }
}

/// docs/09: the body is 14–16, and what it means decides the colour.
public enum DialogTone: Sendable {
    case body
    case muted
    case danger
}

/// One line of a dialog body.
public struct BlueprintDialogText: View {
    @Environment(\.blueprint) private var blueprint
    private let text: String
    private let tone: DialogTone

    public init(_ text: String, tone: DialogTone = .body) {
        self.text = text
        self.tone = tone
    }

    public var body: some View {
        Text(verbatim: text)
            .font(tone == .body ? blueprint.fonts.body : blueprint.fonts.bodySmall)
            .foregroundStyle(ink)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var ink: Color {
        switch tone {
        case .body: return blueprint.palette.text
        case .muted: return blueprint.palette.textMuted
        case .danger: return blueprint.palette.danger
        }
    }
}

/// One of several answers, as one accessibility element: what VoiceOver reads is
/// "<label>, selected" and not an unnamed mark beside a label it cannot see (docs/09 "접근성"). The
/// row is [minTouch] tall whatever the label does.
public struct BlueprintRadioRow: View {
    private let label: String
    private let selected: Bool
    private let action: () -> Void

    public init(_ label: String, selected: Bool, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            OptionRow(label: label, selected: selected, filled: false)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// The same row for an answer that is on or off rather than one of a set — announced as the switch
/// it is through `accessibilityRepresentation`, the way [BlueprintSwitchStyle] is.
public struct BlueprintCheckRow: View {
    private let label: String
    @Binding private var isOn: Bool

    public init(_ label: String, isOn: Binding<Bool>) {
        self.label = label
        _isOn = isOn
    }

    public var body: some View {
        Button { isOn.toggle() } label: {
            OptionRow(label: label, selected: isOn, filled: true)
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            Toggle(isOn: $isOn) { Text(verbatim: label) }
        }
    }
}

private struct OptionRow: View {
    @Environment(\.blueprint) private var blueprint
    let label: String
    let selected: Bool
    let filled: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            SelectionMark(selected: selected, filled: filled)
                // The mark sits on the first line of a label that wrapped, not in the middle of it.
                .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 3 }
            Text(verbatim: label)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Space.s)
        .frame(minHeight: minTouch)
        .contentShape(Rectangle())
    }
}

/// docs/09 "형태": no circles and no pills, so both marks are squares on the badge radius. A radio
/// holds a smaller square inside its outline; a checkbox fills, because "on" is a state and "this
/// one of the two" is a position.
///
/// The outline is on [BlueprintPalette.line] — the token the hairline and every bordered node take
/// — so high contrast thickens it here too (docs/09 "고대비 모드"). An unchecked box is nothing
/// *but* its outline, which is what made it the one that needed it.
private struct SelectionMark: View {
    @Environment(\.blueprint) private var blueprint
    let selected: Bool
    let filled: Bool

    var body: some View {
        let ink = selected ? blueprint.palette.accent : blueprint.palette.textMuted
        RoundedRectangle(cornerRadius: Radius.badge)
            .fill(selected && filled ? ink : .clear)
            .overlay {
                if selected {
                    RoundedRectangle(cornerRadius: 1)
                        .fill(filled ? blueprint.palette.onAccent : ink)
                        .frame(width: 8, height: 8)
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: Radius.badge)
                    .strokeBorder(ink, lineWidth: blueprint.line)
            }
            .frame(width: 18, height: 18)
    }
}

/// A link inside a dialog — the Google permissions page, the consent guidance. Accent *and*
/// underlined, because a link that is only a colour is invisible to a colour-blind reader (docs/09
/// "모든 상태는 색 + 텍스트"), and [minTouch] tall because it is something you tap.
public struct BlueprintDialogLink: View {
    @Environment(\.blueprint) private var blueprint
    private let label: String
    private let action: () -> Void

    public init(_ label: String, action: @escaping () -> Void) {
        self.label = label
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(verbatim: label)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.accent)
                .underline()
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: minTouch, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
