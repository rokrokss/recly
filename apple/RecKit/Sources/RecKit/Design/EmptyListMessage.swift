import SwiftUI

/// docs/09 screen principle 8: an empty list says so in the middle of the space the list would fill — a line
/// in the text colour, a muted line under it, and, where the screen has no other way to the action,
/// the one button a clear step below, centred. The phones leave the button out: their tab bar puts
/// Record right under it.
///
/// Inside a `ScrollView` it takes the scroll view's visible height, so it sits in the middle
/// rather than at the top of an empty list.
public struct EmptyListMessage<Action: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let title: String
    private let hint: String?
    private let action: Action

    public init(title: String, hint: String? = nil, @ViewBuilder action: () -> Action) {
        self.title = title
        self.hint = hint
        self.action = action()
    }

    public var body: some View {
        VStack(spacing: Space.xs) {
            Text(verbatim: title)
                .font(blueprint.fonts.body)
                .foregroundStyle(blueprint.palette.text)
            if let hint {
                Text(verbatim: hint)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            if !(action is EmptyView) {
                action
                    .padding(.top, Space.l)
            }
        }
        .multilineTextAlignment(.center)
        .padding(Space.l)
        .frame(maxWidth: .infinity)
        .containerRelativeFrame(.vertical)
    }
}

extension EmptyListMessage where Action == EmptyView {
    public init(title: String, hint: String? = nil) {
        self.init(title: title, hint: hint) { EmptyView() }
    }
}
