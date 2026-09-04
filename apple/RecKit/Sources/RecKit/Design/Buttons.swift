import SwiftUI

/// docs/09: square, bordered, never a pill. Four weights is all the four apps need.
public enum ButtonTone: Sendable {
    case primary
    case accent
    case quiet
    case danger
}

/// The button of this design: a square-cornered box with a 1pt edge (2pt in high contrast), at
/// least [minTouch] tall however small the label is.
public struct BlueprintButton: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.isEnabled) private var isEnabled
    private let label: String
    private let leading: String?
    private let tone: ButtonTone
    private let mono: Bool
    private let action: () -> Void

    /// - Parameter mono: for a label that is data — a provider id — rather than a word, as
    ///   [BlueprintField]'s own `mono` is, and as the other two shells' `BlueprintButton` already had.
    public init(
        _ label: String,
        tone: ButtonTone = .accent,
        leading: String? = nil,
        mono: Bool = false,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.tone = tone
        self.leading = leading
        self.mono = mono
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: Space.xs) {
                if let leading {
                    Text(verbatim: leading)
                }
                Text(verbatim: label)
            }
            .font(mono ? blueprint.fonts.monoBodySmall : blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
            .foregroundStyle(ink)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            // docs/09 "접근성": the label is small, the button is not.
            .frame(minHeight: minTouch)
            .background(fill, in: RoundedRectangle(cornerRadius: Radius.node))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(edge, lineWidth: blueprint.line)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var ink: Color {
        guard isEnabled else { return blueprint.palette.textMuted }
        switch tone {
        case .primary: return blueprint.palette.onAccent
        case .accent: return blueprint.palette.accent
        case .danger: return blueprint.palette.danger
        case .quiet: return blueprint.palette.textMuted
        }
    }

    private var edge: Color {
        guard isEnabled else { return blueprint.palette.grid }
        switch tone {
        case .primary, .accent: return blueprint.palette.accent
        case .danger: return blueprint.palette.danger
        case .quiet: return blueprint.palette.grid
        }
    }

    private var fill: Color {
        isEnabled && tone == .primary ? blueprint.palette.accent : .clear
    }
}

/// docs/09 "형태": one of a set of choices that is expected to grow — the language, and whatever
/// enum setting comes after it. A row of chips says every option out loud, which is right for two or
/// three and wrong for ten; this says the chosen one and keeps the rest one click away, so the
/// setting stays one row however long the list gets.
///
/// A `Menu` and not a menu-styled `Picker`: that picker *is* an `NSPopUpButton`, and its bezel, its
/// radius and its chevron are the system's — there is no way to give them this design's square edge.
/// A `Menu` draws its own label, which is the box below, and pops the same platform menu; a pop-up
/// menu is chrome the platform owns (docs/09 트렌드 7), so it is left as the platform draws it.
///
/// Not on the watch: it has no settings screen of its own (docs/07 §7 매핑 — the watch follows the
/// system language), and a menu is not a control a screen that size offers.
#if !os(watchOS)
public struct BlueprintDropdown<Value: Hashable & Identifiable>: View {
    @Environment(\.blueprint) private var blueprint
    private let label: String
    private let options: [Value]
    private let title: (Value) -> String
    private let mono: Bool
    @Binding private var selection: Value

    /// The glyph on the closed dropdown. Not an SF Symbol: it sits in the label's own line of text,
    /// at the label's own size, and grows with it — as [BlueprintChip.selectionMark] does.
    public static var indicator: String { "▾" }

    /// - Parameter mono: for a value that is data — a provider id — rather than a word, as
    ///   [BlueprintField]'s own `mono` is. The menu is the platform's and stays in its own font.
    public init(
        _ label: String,
        options: [Value],
        selection: Binding<Value>,
        mono: Bool = false,
        title: @escaping (Value) -> String
    ) {
        self.label = label
        self.options = options
        _selection = selection
        self.mono = mono
        self.title = title
    }

    public var body: some View {
        Menu {
            ForEach(options) { option in
                Button {
                    selection = option
                } label: {
                    // docs/09 "모든 상태는 색 + 텍스트": which one is chosen is a mark in the label,
                    // the same one a chip wears, and not a colour a monochrome reader loses.
                    Text(verbatim: option == selection
                        ? "\(BlueprintChip.selectionMark) \(title(option))"
                        : title(option))
                }
            }
        } label: {
            box
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        // One element, deliberately (the AlertBanner/LedgerRow idiom): a `Menu` publishes its own
        // menu-button element AND its plain-styled label subtree publishes a second one, so the
        // setting reads twice — 언어/한국어, then 언어/한국어 again over nothing. Ignoring the
        // children keeps the menu's role and activation while this one element carries the words.
        // (Hiding the label instead removes *both* elements — verified live.)
        .accessibilityElement(children: .ignore)
        // Flattening drops the menu-button role with the children, so the trait puts an
        // actionable role back on the one element that is left.
        .accessibilityAddTraits(.isButton)
        // docs/09 "접근성": the element says the value, and the row it sits in says what the value
        // is of — a reader given only the value would hear "한국어" and no question.
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityValue(Text(verbatim: title(selection)))
    }

    /// The closed control: [BlueprintButton]'s quiet box, drawn here rather than reused, because a
    /// `Button` inside a `Menu`'s label is a second control and not a label.
    private var box: some View {
        HStack(spacing: Space.xs) {
            Text(verbatim: title(selection))
            Text(verbatim: Self.indicator)
        }
        .font(mono ? blueprint.fonts.monoBodySmall : blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
        .foregroundStyle(blueprint.palette.textMuted)
        .lineLimit(1)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .frame(minHeight: minTouch)
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node)
                .strokeBorder(blueprint.palette.grid, lineWidth: blueprint.line)
        }
        .contentShape(Rectangle())
    }
}
#endif

/// Where the caller's operation actually is — what the screen knows, not what the button draws.
public enum ProcessingState: Sendable, Equatable {
    case idle
    case processing
    case done
    case failed
}

/// What a processing button draws at one moment — not what the operation behind it is doing.
public enum ProcessingPhase: Sendable, Equatable {
    case idle
    case processing
    case done
}

/// docs/09 트렌드 2, as arithmetic: the windows a high-risk action is shown inside. Pure, so the
/// rule can be checked without a screen (`ProcessingPhaseTests`).
public enum Processing {
    /// How much longer the "…" has to stay after the work finished in [workSec]. Instant work is
    /// padded up to [Motion.processingMin]; work that already took that long is not padded at all.
    ///
    /// Reduce motion does not shorten this. docs/09 "모션" asks for "즉시 전환 + 텍스트 상태만" —
    /// instant transitions *and* the text state, not no state at all — and a user who has turned
    /// animations off is the one with nothing else to tell them the tap was heard. What reduce
    /// motion switches off is the fade between the labels (`Motion.badgeAnimation`), which is the
    /// transition; the label itself is the state.
    public static func hold(workSec: Double) -> Double {
        max(0, Motion.processingMin - workSec)
    }

    /// How long the completion badge ("✓") then stands. It fills out the [Motion.processingMax]
    /// window that the processing state opened, and never flashes for less than one fade
    /// ([Motion.badgeFade]) when the work overran that window — with reduce motion there is no
    /// fade, but the letters still need that long to be read.
    public static func doneBadge(workSec: Double) -> Double {
        let shown = max(workSec, Motion.processingMin)
        return max(Motion.processingMax - shown, Motion.badgeFade)
    }

    /// The phase [elapsedSec] after the operation started, from the operation's own outcome:
    /// [succeeded] is nil while it is still running, true when it worked, false when it failed.
    ///
    /// Running work holds the processing state however long it takes; a result that arrived earlier
    /// waits out [hold] before it shows. Only a success then wears the badge — a failure goes
    /// straight back to idle, because the screen is the one that says what went wrong.
    public static func phase(succeeded: Bool?, workSec: Double, elapsedSec: Double) -> ProcessingPhase {
        guard let succeeded else { return .processing }
        let holdEnd = workSec + hold(workSec: workSec)
        if elapsedSec < holdEnd { return .processing }
        if !succeeded { return .idle }
        return elapsedSec < holdEnd + doneBadge(workSec: workSec) ? .done : .idle
    }
}

/// docs/09 트렌드 2: the rare high-risk action — sign-in, a save, an upload — shows that it happened.
/// What happened is the caller's to say: [state] comes from the operation itself, and the button
/// only owns the *window* around it — "…" for at least [Motion.processingMin] however fast the
/// result was, a ✓ that fills out the 800 ms on success, and nothing at all on failure, which the
/// screen reports.
///
/// Reduce motion keeps all three labels and drops only the fade between them (docs/09 "모션":
/// "즉시 전환 + 텍스트 상태만"). A window of zero would leave a user who has turned animations off
/// with no sign at all that the tap was heard.
public struct ProcessingButton: View {
    @Environment(\.blueprint) private var blueprint
    private let label: String
    private let state: ProcessingState
    private let tone: ButtonTone
    private let action: () -> Void

    /// Armed by this button's own tap: several buttons on a screen can share one operation state,
    /// and only the one that was pressed owns a window.
    @State private var startedAt: Date?
    @State private var phase: ProcessingPhase = .idle

    public init(
        _ label: String,
        state: ProcessingState,
        tone: ButtonTone = .accent,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.state = state
        self.tone = tone
        self.action = action
    }

    public var body: some View {
        button
            .task(id: Key(state: state, startedAt: startedAt, reduceMotion: blueprint.reduceMotion)) {
                await follow()
            }
    }

    private struct Key: Equatable {
        let state: ProcessingState
        let startedAt: Date?
        let reduceMotion: Bool
    }

    @ViewBuilder
    private var button: some View {
        switch phase {
        case .idle:
            BlueprintButton(label, tone: tone) {
                startedAt = Date()
                phase = .processing
                action()
            }

        case .processing:
            BlueprintButton("…", tone: tone) {}
                .disabled(true)
                .accessibilityLabel(Text("Working…", bundle: .module))

        case .done:
            BlueprintButton(label, tone: tone, leading: "✓") {}
                .disabled(true)
        }
    }

    private func follow() async {
        guard let start = startedAt else { return }
        let reduceMotion = blueprint.reduceMotion
        // docs/09 "모션": a badge fades in and out; with reduce motion on the label simply changes.
        let fade = Motion.badgeAnimation(reduceMotion: reduceMotion)
        switch state {
        // The window stays open for as long as the work does.
        case .processing:
            withAnimation(fade) { phase = .processing }

        // The tap has not reached the caller's state yet — or never will, because the operation was
        // refused. Either way the processing look is held out and then dropped.
        case .idle:
            await sleep(Processing.hold(workSec: 0))
            withAnimation(fade) { phase = .idle }
            startedAt = nil

        case .done, .failed:
            let workSec = Date().timeIntervalSince(start)
            let hold = Processing.hold(workSec: workSec)
            await sleep(hold)
            let next = Processing.phase(
                succeeded: state == .done,
                workSec: workSec,
                elapsedSec: workSec + hold
            )
            withAnimation(fade) { phase = next }
            if next == .done {
                await sleep(Processing.doneBadge(workSec: workSec))
            }
            withAnimation(fade) { phase = .idle }
            startedAt = nil
        }
    }

    private func sleep(_ seconds: Double) async {
        guard seconds > 0 else { return }
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
}
