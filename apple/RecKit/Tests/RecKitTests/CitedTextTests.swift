#if os(macOS)
import AppKit
import SwiftUI
import XCTest
@testable import RecKit

/// docs/09 "Summary view": a citation's target is at least the minimum each way, centred on it, and a tap where two
/// targets meet plays the nearer citation.
final class CitedTextTests: XCTestCase {
    func testATargetIsAtLeastTheMinimumEachWayCentredOnTheCitation() {
        let wide = CitationTarget(run: 1, glyphs: CGRect(x: 10, y: 20, width: 90, height: 18))
        XCTAssertEqual(wide.area, CGRect(x: 10, y: 7, width: 90, height: minTouch))
        // `[1:05]` is narrower than a finger.
        let short = CitationTarget(run: 1, glyphs: CGRect(x: 10, y: 20, width: 30, height: 18))
        XCTAssertEqual(short.area, CGRect(x: 3, y: 7, width: minTouch, height: minTouch))
        // The largest text sizes are taller than the minimum already.
        let large = CitationTarget(run: 1, glyphs: CGRect(x: 10, y: 20, width: 200, height: 60))
        XCTAssertEqual(large.area, large.glyphs)
    }

    func testTheLaidOutPiecesOfACitationOnOneLineAreOneTarget() {
        // Right-to-left text lays `[`, the digits and `]` out as three runs.
        let targets = CitationTarget.targets([
            (run: 1, line: 0, glyphs: CGRect(x: 90, y: 0, width: 10, height: 18)),
            (run: 1, line: 0, glyphs: CGRect(x: 10, y: 0, width: 80, height: 18)),
            (run: 1, line: 0, glyphs: CGRect(x: 0, y: 0, width: 10, height: 18)),
            (run: 1, line: 1, glyphs: CGRect(x: 0, y: 22, width: 20, height: 18)),
            (run: 3, line: 1, glyphs: CGRect(x: 60, y: 22, width: 90, height: 18)),
        ])
        XCTAssertEqual(targets, [
            CitationTarget(run: 1, glyphs: CGRect(x: 0, y: 0, width: 100, height: 18)),
            CitationTarget(run: 1, glyphs: CGRect(x: 0, y: 22, width: 20, height: 18)),
            CitationTarget(run: 3, glyphs: CGRect(x: 60, y: 22, width: 90, height: 18)),
        ])
    }

    func testATapPlaysTheNearestCitationWhoseTargetHoldsIt() {
        // Two citations on neighbouring lines: each target reaches over the other's glyphs.
        let above = CitationTarget(run: 1, glyphs: CGRect(x: 0, y: 0, width: 90, height: 18))
        let below = CitationTarget(run: 3, glyphs: CGRect(x: 40, y: 22, width: 90, height: 18))
        let targets = [above, below]
        XCTAssertTrue(below.area.contains(CGPoint(x: 50, y: 16)))
        XCTAssertEqual(CitationTarget.hit(CGPoint(x: 50, y: 16), in: targets), above, "on its own glyphs")
        XCTAssertEqual(CitationTarget.hit(CGPoint(x: 60, y: 24), in: targets), below, "on its own glyphs")
        XCTAssertEqual(CitationTarget.hit(CGPoint(x: 60, y: 19), in: targets), above, "between the lines, nearer the one above")
        XCTAssertEqual(CitationTarget.hit(CGPoint(x: 60, y: 21), in: targets), below, "between the lines, nearer the one below")
        XCTAssertEqual(CitationTarget.hit(CGPoint(x: 20, y: -10), in: targets), above, "above the text, inside its target")
        XCTAssertEqual(CitationTarget.hit(CGPoint(x: 120, y: 50), in: targets), below)
        XCTAssertNil(CitationTarget.hit(CGPoint(x: 20, y: -30), in: targets), "past the target")
        XCTAssertNil(CitationTarget.hit(CGPoint(x: 200, y: 10), in: targets))
        XCTAssertNil(CitationTarget.hit(CGPoint(x: 0, y: 0), in: []))
    }

    /// The real view, laid out by the system: a click that misses the citation's glyphs but lands in its target
    /// plays from it, outside the text's own frame too, and a click on the words around it does not.
    @MainActor
    func testAClickNearACitationPlaysFromIt() throws {
        try clickAround("[00:00:05] We agreed to ship on Friday, and to move the budget review to next week.\n[00:09:00]", rightToLeft: false)
    }

    /// Right to left the first citation is at the right end of the first line, and the targets follow it.
    @MainActor
    func testAClickNearACitationPlaysFromItRightToLeft() throws {
        try clickAround("[00:00:05] اتفقنا على الشحن يوم الجمعة ونقل مراجعة الميزانية إلى الأسبوع المقبل بعد مكالمة المورد.\n[00:09:00]", rightToLeft: true)
    }

    /// [text] starts with `[00:00:05]` and ends with `[00:09:00]` on a line of its own.
    @MainActor
    private func clickAround(_ text: String, rightToLeft: Bool) throws {
        guard #available(macOS 15, *) else { throw XCTSkip("the targets need the macOS 15 text layout") }
        var played: [Double] = []
        let inset: CGFloat = 40
        let width: CGFloat = 320
        let window = show(
            CitedText(text: text, seekableSec: 100, onSeek: { played.append($0) })
                .frame(width: width)
                .padding(inset)
                .environment(\.layoutDirection, rightToLeft ? .rightToLeft : .leftToRight)
        )
        defer { window.orderOut(nil) }
        // Twenty points in from the side a line starts on, or from the other.
        let start = rightToLeft ? inset + width - 20 : inset + 20
        let end = rightToLeft ? inset + 20 : inset + width - 20

        // Six points above the first line: outside the text, inside the first citation's target.
        click(CGPoint(x: start, y: inset - 6), in: window)
        XCTAssertEqual(played, [5])
        // On the words of the first line, far from the citation.
        click(CGPoint(x: end, y: inset + 8), in: window)
        XCTAssertEqual(played, [5])
        // Six points under `[00:09:00]`, the last line: it is past the end of the recording, so it is muted text
        // and nothing is laid over it.
        click(CGPoint(x: start, y: window.frame.height - inset + 6), in: window)
        XCTAssertEqual(played, [5])
    }

    /// Two citations on neighbouring lines: each target reaches over the other's glyphs, and a click on either's
    /// glyphs plays that one.
    @MainActor
    func testAClickWhereTwoTargetsMeetPlaysTheNearerCitation() throws {
        guard #available(macOS 15, *) else { throw XCTSkip("the targets need the macOS 15 text layout") }
        var played: [Double] = []
        let inset: CGFloat = 40
        let window = show(
            CitedText(text: "[00:00:05] a\n[00:00:30] b", seekableSec: 100, onSeek: { played.append($0) })
                .frame(width: 320)
                .padding(inset)
        )
        defer { window.orderOut(nil) }
        // The lower half of the first line, then the upper half of the second: both inside both targets.
        click(CGPoint(x: inset + 20, y: inset + 10), in: window)
        click(CGPoint(x: inset + 20, y: inset + 26), in: window)
        XCTAssertEqual(played, [5, 30])
    }

    /// [view] in a window of its own, off every screen, laid out.
    @MainActor
    private func show(_ view: some View) -> NSWindow {
        let host = ClickableHost(rootView: AnyView(view))
        host.setFrameSize(host.fittingSize)
        let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.contentView = host
        window.setFrameOrigin(CGPoint(x: -4000, y: -4000))
        window.orderFrontRegardless()
        host.layoutSubtreeIfNeeded()
        pump(0.3)
        return window
    }

    /// A down and an up at [point] (from the top left), through the app as a real click goes.
    @MainActor
    private func click(_ point: CGPoint, in window: NSWindow) {
        let location = CGPoint(x: point.x, y: window.frame.height - point.y)
        for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
            guard let event = NSEvent.mouseEvent(
                with: type, location: location, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, eventNumber: 0, clickCount: 1,
                pressure: type == .leftMouseDown ? 1 : 0
            ) else { continue }
            NSApplication.shared.postEvent(event, atStart: false)
        }
        pump(0.3)
    }

    @MainActor
    private func pump(_ seconds: TimeInterval) {
        let until = Date().addingTimeInterval(seconds)
        while Date() < until {
            if let event = NSApplication.shared.nextEvent(matching: .any, until: Date().addingTimeInterval(0.02), inMode: .default, dequeue: true) {
                NSApplication.shared.sendEvent(event)
            }
        }
    }

    /// Takes the click without the app in front, so the test never activates it.
    private final class ClickableHost: NSHostingView<AnyView> {
        override func acceptsFirstMouse(for _: NSEvent?) -> Bool { true }
    }
}
#endif
