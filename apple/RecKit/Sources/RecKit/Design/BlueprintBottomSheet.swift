import SwiftUI

#if os(iOS)
extension View {
    /// docs/09 screen principle 5 (2026-10-29): a question the phone asks from its bottom edge — the screen's full width,
    /// standing on that edge with only its top corners rounded, as the full-height sheets (the detail, Ask) do.
    ///
    /// Drawn rather than presented as a system sheet: iOS 26 floats a sheet shorter than the screen as a card inset
    /// from both sides and from the bottom, and has no setting that attaches it to the edges. So the presentation is a
    /// cover with nothing of its own, and the panel rises into it over a dim of its own.
    ///
    /// It is as tall as what it holds and scrolls past the screen. A question is closed by its own answers and nothing
    /// else — no swipe, no tap on the dim — which is why it takes the state rather than a binding to write back. A
    /// sheet with no answers to give, a list to pick from (Share), passes [onDismiss]: a tap on the dim, or a screen
    /// reader's escape, asks for it to close.
    public func blueprintBottomSheet<Sheet: View>(
        isPresented: Bool,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Sheet
    ) -> some View {
        modifier(BottomSheetCover(value: isPresented ? () : nil, onDismiss: onDismiss) { _ in content() })
    }

    /// The same, driven by the thing the question is about.
    public func blueprintBottomSheet<Item, Sheet: View>(
        item: Item?,
        @ViewBuilder content: @escaping (Item) -> Sheet
    ) -> some View {
        modifier(BottomSheetCover(value: item, onDismiss: nil, sheet: content))
    }

    /// The same panel drawn in the screen rather than presented over it, for a screen whose one presentation is another
    /// question's — the recording screen's is the naming sheet, and a second would never open (docs/08 "Memo": the
    /// memo's editor while recording).
    public func blueprintBottomSheetOverlay<Sheet: View>(
        isPresented: Bool,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Sheet
    ) -> some View {
        modifier(BottomSheetOverlay(presented: isPresented, onDismiss: onDismiss, sheet: content))
    }
}

private struct BottomSheetOverlay<Sheet: View>: ViewModifier {
    let presented: Bool
    let onDismiss: (() -> Void)?
    let sheet: () -> Sheet
    @Environment(\.blueprint) private var blueprint
    @State private var last = LastValue<Void>()
    /// Whether the panel is in the screen: from the ask until it has gone back down.
    @State private var shown = false
    @State private var risen = false

    func body(content: Content) -> some View {
        last.presented = presented
        return content
            .overlay {
                if shown {
                    BottomSheetPanel(risen: $risen, onDismiss: onDismiss) { sheet() }
                        // The panel is the screen while it is up, as the presented one is.
                        .accessibilityAddTraits(.isModal)
                }
            }
            .onChange(of: presented, initial: true) { _, presented in
                if presented {
                    // A new panel rises once it has measured what it holds.
                    shown = true
                } else if shown {
                    withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) {
                        risen = false
                    } completion: {
                        // Asked again while it was on its way down: it goes back up instead.
                        if last.presented {
                            withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) { risen = true }
                        } else {
                            shown = false
                        }
                    }
                }
            }
    }
}

/// What the cover holds on to between two drawings: the value it was last drawn for, so the panel still says it on its
/// way down after the question has been answered and the value has gone. A box rather than state: keeping it is not
/// a change to draw.
private final class LastValue<Value> {
    var value: Value?
    var presented = false
}

private struct BottomSheetCover<Value, Sheet: View>: ViewModifier {
    let value: Value?
    let onDismiss: (() -> Void)?
    let sheet: (Value) -> Sheet
    @Environment(\.blueprint) private var blueprint
    @State private var last = LastValue<Value>()
    /// Whether the cover is up. Set with no animation: the system's slides the whole cover up, the dim with it.
    @State private var covered = false
    /// Whether the panel stands on the bottom edge rather than below it.
    @State private var risen = false

    func body(content: Content) -> some View {
        if let value { last.value = value }
        last.presented = value != nil
        let shown = value ?? last.value
        return content
            .onChange(of: value != nil, initial: true) { _, presented in
                if presented {
                    withoutAnimation { covered = true }
                } else if covered {
                    withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) {
                        risen = false
                    } completion: {
                        // Asked again while it was on its way down: it goes back up instead.
                        if last.presented {
                            withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) { risen = true }
                        } else {
                            withoutAnimation { covered = false }
                            last.value = nil
                        }
                    }
                }
            }
            .fullScreenCover(isPresented: $covered) {
                if let shown {
                    BottomSheetPanel(risen: $risen, onDismiss: onDismiss) { sheet(shown) }
                        .presentationBackground(.clear)
                }
            }
    }

    private func withoutAnimation(_ change: () -> Void) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction, change)
    }
}

private struct BottomSheetPanel<Content: View>: View {
    @Environment(\.blueprint) private var blueprint
    @Binding var risen: Bool
    let onDismiss: (() -> Void)?
    @ViewBuilder let content: Content
    /// How tall what it holds is: the panel grows to it, and past the screen it scrolls. Nil until measured, and
    /// the panel waits below the edge until then.
    @State private var height: CGFloat?
    /// The home indicator's inset, which the panel's page runs on into.
    @State private var inset: CGFloat = 0

    /// The full-height sheet's corner, measured off the iPhone 17 Pro's detail sheet (2026-10-29).
    private static var corner: CGFloat { 24 }

    var body: some View {
        ZStack(alignment: .bottom) {
            // The system sheet's dim: the screen under the question is waiting on it.
            Color.black.opacity(risen ? 0.2 : 0)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { onDismiss?() }
                .accessibilityHidden(onDismiss == nil)
                .accessibilityLabel(RecKitStrings.localized("Close"))
                .accessibilityAddTraits(.isButton)
            ScrollView {
                content
                    .frame(maxWidth: .infinity)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: height ?? 0)
            .background(alignment: .top) {
                DotGrid(palette: blueprint.palette)
                    .clipShape(UnevenRoundedRectangle(topLeadingRadius: Self.corner, topTrailingRadius: Self.corner, style: .continuous))
                    .ignoresSafeArea(edges: .bottom)
            }
            // Below the edge until it rises: its own height, and the inset its page runs on into.
            .offset(y: risen ? 0 : (height ?? 0) + inset)
        }
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.bottom } action: { inset = $0 }
        .accessibilityAction(.escape) { onDismiss?() }
        .onChange(of: height != nil) { _, measured in
            if measured {
                withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) { risen = true }
            }
        }
    }
}
#endif
