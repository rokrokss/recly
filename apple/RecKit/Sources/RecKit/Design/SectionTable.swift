import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// The one line at the top of every screen: what this is, and — in monospace, on the right — the
/// machine's side of it (the device, the counts, the revision). No navigation bar, no elevation and
/// no glass: docs/09 keeps glass for chrome the platform owns.
public struct ScreenHeader<Trailing: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let title: String
    private let meta: String?
    private let trailing: Trailing

    public init(title: String, meta: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.meta = meta
        self.trailing = trailing()
    }

    public var body: some View {
        HStack(spacing: 12) {
            Text(verbatim: title)
                .font(blueprint.fonts.sans(TypeSize.body, weight: .semibold))
                .foregroundStyle(blueprint.palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let meta {
                Text(verbatim: meta)
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            trailing
        }
        .padding(.horizontal, Space.m)
        .padding(.top, Space.m)
        .padding(.bottom, 12)
    }
}

extension ScreenHeader where Trailing == EmptyView {
    public init(title: String, meta: String? = nil) {
        self.init(title: title, meta: meta) { EmptyView() }
    }
}

/// docs/09 화면 원칙 4: a settings screen is a table, and a table has section headings. Only the
/// vertical rhythm is baked in — the caller owns the horizontal inset, because an inspector already
/// has one and a full-bleed table does not.
///
/// The heading may carry the section's one control on its right, the way [ScreenHeader] does — a
/// section whose only action is "add one" has nowhere else to put it but a row that is not a row.
public struct SectionHeader<Trailing: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let text: String
    private let trailing: Trailing

    public init(_ text: String, @ViewBuilder trailing: () -> Trailing) {
        self.text = text
        self.trailing = trailing()
    }

    public var body: some View {
        HStack(spacing: 12) {
            Text(verbatim: text)
                .font(blueprint.fonts.label)
                .tracking(0.6)
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.top, Space.m)
        .padding(.bottom, 6)
    }
}

extension SectionHeader where Trailing == EmptyView {
    public init(_ text: String) {
        self.init(text) { EmptyView() }
    }
}

/// One row of a section table: a title, an optional second line, and whatever the row is for on the
/// right. Square corners — docs/09 gives a table row a radius of zero.
public struct SectionRow<Trailing: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let title: String
    private let subtitle: String?
    private let trailing: Trailing

    public init(title: String, subtitle: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing()
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: title)
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.text)
                    if let subtitle {
                        Text(verbatim: subtitle)
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                trailing
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, 12)
            .frame(minHeight: minTouch)
            HairLine()
        }
        .background(blueprint.palette.surface)
    }
}

extension SectionRow where Trailing == EmptyView {
    public init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// A row of the same table that is more than a title and a trailing control — a workflow with its
/// switch on one line and its buttons on the next. Same surface, same insets, same rule under it.
public struct SectionBlock<Content: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: Space.s) {
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Space.m)
            .padding(.vertical, 12)
            HairLine()
        }
        .background(blueprint.palette.surface)
    }
}

/// A [SectionRow] that *is* its switch. The row is one `Toggle` with the Blueprint style, so what
/// VoiceOver focuses is a single element with a name — "<title>, switch, on" — and not an unnamed
/// track next to a title it cannot see (docs/09 "접근성").
public struct SwitchRow: View {
    @Environment(\.blueprint) private var blueprint
    private let title: String
    private let subtitle: String?
    @Binding private var isOn: Bool

    public init(title: String, subtitle: String? = nil, isOn: Binding<Bool>) {
        self.title = title
        self.subtitle = subtitle
        _isOn = isOn
    }

    public var body: some View {
        VStack(spacing: 0) {
            Toggle(isOn: $isOn) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: title)
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.text)
                    if let subtitle {
                        Text(verbatim: subtitle)
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                }
            }
            .toggleStyle(BlueprintSwitchStyle())
            .padding(.horizontal, Space.m)
            .padding(.vertical, 6)
            .frame(minHeight: minTouch)
            HairLine()
        }
        .background(blueprint.palette.surface)
    }
}

/// docs/09 "형태": no rounded pills, so the switch is a square track with a square thumb. The whole
/// row is the control — one hit target, one accessibility element, and `accessibilityRepresentation`
/// makes sure it is announced as the switch it is rather than as the button it is drawn with.
public struct BlueprintSwitchStyle: ToggleStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(spacing: 12) {
                configuration.label.frame(maxWidth: .infinity, alignment: .leading)
                SwitchTrack(isOn: configuration.isOn)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            Toggle(isOn: configuration.$isOn) { configuration.label }
        }
    }
}

/// The track on its own, for whoever is making the control around it.
public struct SwitchTrack: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.isEnabled) private var isEnabled
    private let isOn: Bool

    public init(isOn: Bool) {
        self.isOn = isOn
    }

    public var body: some View {
        let ink = isEnabled
            ? (isOn ? blueprint.palette.accent : blueprint.palette.textMuted)
            : blueprint.palette.grid
        HStack(spacing: 0) {
            if isOn { Spacer(minLength: 0) }
            RoundedRectangle(cornerRadius: 1).fill(ink).frame(width: 14, height: 14)
            if !isOn { Spacer(minLength: 0) }
        }
        .padding(2)
        .frame(width: 40, height: 22)
        .overlay {
            // docs/09 "선"/"고대비 모드": the track's edge is a line like every other, so it
            // thickens with them rather than staying at a number of its own.
            RoundedRectangle(cornerRadius: Radius.badge)
                .strokeBorder(ink, lineWidth: blueprint.line)
        }
        .frame(width: minTouch, height: minTouch, alignment: .trailing)
    }
}

/// A line of news over the content — what an import said, why a save was refused.
/// Square, on the surface, with the tone in a 1pt edge rather than in a wash of colour.
public struct Banner: View {
    @Environment(\.blueprint) private var blueprint
    private let text: String
    private let tone: BadgeTone

    public init(_ text: String, tone: BadgeTone = .neutral) {
        self.text = text
        self.tone = tone
    }

    public var body: some View {
        Text(verbatim: text)
            .font(blueprint.fonts.sans(TypeSize.bodySmall))
            .foregroundStyle(tone.ink(blueprint.palette))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            // Filled on the same shape as the edge: a square fill under a 4pt border showed the
            // page through the corners.
            .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(tone.edge(blueprint.palette), lineWidth: blueprint.line)
            }
    }
}

/// One field of an inspector: the name above, the value in a square-cornered box. `mono` for the
/// values that are data rather than prose — a folder template, a URL, a retry count.
public struct BlueprintField: View {
    @Environment(\.blueprint) private var blueprint
    private let label: String
    private let mono: Bool
    private let secure: Bool
    @Binding private var text: String

    public init(
        _ label: String,
        text: Binding<String>,
        mono: Bool = false,
        secure: Bool = false
    ) {
        self.label = label
        _text = text
        self.mono = mono
        self.secure = secure
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(verbatim: label)
                .font(blueprint.fonts.label)
                .tracking(0.6)
                .foregroundStyle(blueprint.palette.textMuted)
            Group {
                if secure {
                    SecureField("", text: $text)
                } else {
                    TextField("", text: $text)
                }
            }
            .textFieldStyle(.plain)
            .font(mono ? blueprint.fonts.monoBodySmall : blueprint.fonts.bodySmall)
            .foregroundStyle(blueprint.palette.text)
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(blueprint.palette.grid, lineWidth: blueprint.line)
            }
            .accessibilityLabel(Text(verbatim: label))
        }
    }
}

/// One of a small set of choices — a source, a track, what to do on failure. Square, bordered, and
/// selected in the accent rather than by a fill nobody can name.
///
/// docs/09 "모든 상태는 색 + 텍스트": what says "this one" is three things and not one — the accent,
/// a border on [BlueprintPalette.selectedLine] (heavier than the hairline *even in high contrast*,
/// where the hairline is itself 2pt), and the [selectionMark] in front of the label, which is the
/// only one of the three a monochrome or colour-blind reader gets. The Android chip is the same
/// shape.
public struct BlueprintChip: View {
    @Environment(\.blueprint) private var blueprint
    private let label: String
    private let selected: Bool
    private let action: () -> Void

    /// The glyph a chosen chip wears. Not an SF Symbol: it sits in the label's own line of text, at
    /// the label's own size, and grows with it.
    public static let selectionMark = "✓"

    public init(_ label: String, selected: Bool, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(verbatim: selected ? "\(Self.selectionMark) \(label)" : label)
                .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                .foregroundStyle(selected ? blueprint.palette.accent : blueprint.palette.textMuted)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                // docs/09 "접근성": in *both* directions. A height alone left a two-letter chip —
                // "ko", "2" — a target barely half as wide as it is tall.
                .frame(minWidth: minTouch, minHeight: minTouch)
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.node)
                        .strokeBorder(
                            selected ? blueprint.palette.accent : blueprint.palette.grid,
                            lineWidth: selected ? blueprint.palette.selectedLine : blueprint.line
                        )
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // The mark is decoration to a screen reader, which is told the same fact as a trait.
        .accessibilityLabel(Text(verbatim: label))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// docs/09 "아이콘": thin geometric line work — the platform's own symbols at `.light`, with no
/// filled variant a tab bar might substitute in.
public enum BlueprintGlyph: String, Sendable {
    /// A square with a dot in it — the record node of docs/09 "형태", not a circle.
    case record = "dot.square"
    case list = "list.bullet.rectangle"
    case workflows = "rectangle.connected.to.line.below"
    case settings = "slider.horizontal.3"
}

public struct BlueprintIcon: View {
    private let glyph: BlueprintGlyph
    private let size: CGFloat

    public init(_ glyph: BlueprintGlyph, size: CGFloat = 19) {
        self.glyph = glyph
        self.size = size
    }

    public var body: some View {
        #if canImport(UIKit)
        // A tab bar draws the icon itself and drops the `.font` and `.symbolVariant` a SwiftUI
        // `Image` carries — it renders its own filled variant at its own weight. Configuring the
        // `UIImage` instead is the one way the thin line work survives into the bar.
        Image(uiImage: configured)
        #else
        Image(systemName: glyph.rawValue)
            .font(.system(size: size, weight: .light))
            .symbolVariant(.none)
        #endif
    }

    #if canImport(UIKit)
    /// Built once per glyph and size. A fresh `UIImage` on every body evaluation makes the tab item
    /// it is inside compare unequal, and the tab bar is then rebuilt for nothing.
    private var configured: UIImage {
        let key = "\(glyph.rawValue)-\(size)" as NSString
        if let cached = Self.symbols.object(forKey: key) { return cached }
        let configuration = UIImage.SymbolConfiguration(pointSize: size, weight: .light)
        let image = UIImage(systemName: glyph.rawValue, withConfiguration: configuration) ?? UIImage()
        Self.symbols.setObject(image, forKey: key)
        return image
    }

    private static let symbols = NSCache<NSString, UIImage>()
    #endif
}
