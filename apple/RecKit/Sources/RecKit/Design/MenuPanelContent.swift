#if os(macOS)
import SwiftUI

/// Keeps menu actions visible while the middle section scrolls within the available screen height.
public struct MenuPanelContent<Header: View, Content: View, Footer: View>: View {
    private let maximumSize: CGSize
    private let preferredContentHeight: CGFloat
    private let header: Header
    private let content: Content
    private let footer: Footer

    public init(
        maximumSize: CGSize,
        preferredContentHeight: CGFloat,
        @ViewBuilder header: () -> Header,
        @ViewBuilder content: () -> Content,
        @ViewBuilder footer: () -> Footer
    ) {
        self.maximumSize = maximumSize
        self.preferredContentHeight = preferredContentHeight
        self.header = header()
        self.content = content()
        self.footer = footer()
    }

    public var body: some View {
        VStack(spacing: 0) {
            header
                .fixedSize(horizontal: false, vertical: true)
                .layoutPriority(1)
            ScrollView { content }
                .frame(minHeight: 0, idealHeight: preferredContentHeight, maxHeight: preferredContentHeight)
                .accessibilityIdentifier("menu-panel-content")
            footer
                .fixedSize(horizontal: false, vertical: true)
                .layoutPriority(1)
        }
        .frame(width: maximumSize.width)
        .frame(maxHeight: maximumSize.height)
    }
}
#endif
