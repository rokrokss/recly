#if os(macOS)
import AppKit
import SwiftUI
import XCTest
@testable import RecKit

final class MenuPanelContentTests: XCTestCase {
    @MainActor
    func testLongSettingsKeepTheFooterInsideAShortViewport() throws {
        for height: CGFloat in [400, 600] {
            let probes = Probes()
            let hosting = host(view(height: height, preferred: 420, probes: probes))
            XCTAssertLessThanOrEqual(hosting.frame.height, height)
            let footer = try XCTUnwrap(probes.footer)
            let frame = footer.convert(footer.bounds, to: hosting)
            XCTAssertEqual(frame.height, 60, accuracy: 1)
            XCTAssertGreaterThanOrEqual(frame.minY, 0)
            XCTAssertLessThanOrEqual(frame.maxY, hosting.bounds.maxY)
            let content = try XCTUnwrap(probes.content)
            XCTAssertEqual(content.bounds.height, 1600, accuracy: 1,
                "The settings keep their full content inside the scroll view")
        }
    }

    @MainActor
    func testReturningToTheLedgerShrinksTheSameHostingView() {
        let probes = Probes()
        let hosting = host(view(height: 900, preferred: 420, probes: probes))
        let expanded = hosting.frame.height
        hosting.rootView = view(height: 900, preferred: 280, probes: probes)
        layout(hosting)
        XCTAssertLessThan(hosting.frame.height, expanded)
        XCTAssertEqual(expanded - hosting.frame.height, 140, accuracy: 1)
    }

    @MainActor
    func testRecordingHeaderAndQuitActionKeepTheirHeightWhenSpaceIsLimited() throws {
        let probes = Probes()
        let hosting = host(view(height: 440, preferred: 420, headerHeight: 300, probes: probes))
        XCTAssertLessThanOrEqual(hosting.frame.height, 440)
        let header = try XCTUnwrap(probes.header)
        let footer = try XCTUnwrap(probes.footer)
        XCTAssertEqual(header.bounds.height, 300, accuracy: 1)
        XCTAssertEqual(footer.bounds.height, 60, accuracy: 1)
        XCTAssertTrue(hosting.bounds.contains(footer.convert(footer.bounds, to: hosting)))
    }

    @MainActor
    private func host(_ view: AnyView) -> NSHostingView<AnyView> {
        let hosting = NSHostingView(rootView: view)
        hosting.sizingOptions = [.intrinsicContentSize]
        layout(hosting)
        return hosting
    }

    @MainActor
    private func layout(_ hosting: NSHostingView<AnyView>) {
        hosting.setFrameSize(hosting.intrinsicContentSize)
        hosting.layoutSubtreeIfNeeded()
    }

    @MainActor
    private func view(height: CGFloat, preferred: CGFloat, headerHeight: CGFloat = 180, probes: Probes) -> AnyView {
        AnyView(MenuPanelContent(maximumSize: CGSize(width: 460, height: height), preferredContentHeight: preferred) {
            Probe { probes.header = $0 }.frame(height: headerHeight)
        } content: {
            Probe { probes.content = $0 }.frame(height: 1600)
        } footer: {
            Probe { probes.footer = $0 }.frame(height: 60)
        })
    }

    @MainActor
    private final class Probes {
        var header: NSView?
        var content: NSView?
        var footer: NSView?
    }

    private struct Probe: NSViewRepresentable {
        let created: (NSView) -> Void

        func makeNSView(context: Context) -> NSView {
            let view = NSView()
            created(view)
            return view
        }

        func updateNSView(_ nsView: NSView, context: Context) {}
    }
}
#endif
