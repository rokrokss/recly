#if os(macOS)
import SwiftUI

/// Keeps menu actions visible while the middle section scrolls within the available screen height.
///
/// One height, [preferredHeight] or the screen's room if that is less, whatever the header holds (2026-11-04): the
/// scrolled middle gives and takes what the header's changes need — a recording's memo row, its field opening
/// and folding. The window around it follows a size change a turn later (`MenuBarPanel.scheduleFit`), and in
/// that frame the content and the window disagreed: Done on the memo flashed an empty band under the footer.
public struct MenuPanelContent<Header: View, Content: View, Footer: View>: View {
    private let maximumSize: CGSize
    private let preferredHeight: CGFloat
    /// A new value starts the scrolled middle over at its top; the header and footer stay as they are.
    private let contentID: AnyHashable?
    private let header: Header
    private let content: Content
    private let footer: Footer

    public init(
        maximumSize: CGSize,
        preferredHeight: CGFloat,
        contentID: AnyHashable? = nil,
        @ViewBuilder header: () -> Header,
        @ViewBuilder content: () -> Content,
        @ViewBuilder footer: () -> Footer
    ) {
        self.maximumSize = maximumSize
        self.preferredHeight = preferredHeight
        self.contentID = contentID
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
                .id(contentID)
                .frame(minHeight: 0, maxHeight: .infinity)
                .accessibilityIdentifier("menu-panel-content")
            footer
                .fixedSize(horizontal: false, vertical: true)
                .layoutPriority(1)
        }
        .frame(width: maximumSize.width, height: min(preferredHeight, maximumSize.height))
    }
}
#endif
