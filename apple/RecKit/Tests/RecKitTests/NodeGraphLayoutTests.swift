import XCTest
@testable import RecKit

/// docs/09 화면 원칙 3: where the nodes, the connectors and their `+` land.
final class NodeGraphLayoutTests: XCTestCase {

    /// The mockup's rhythm, in points: 14 + 18 + 14 after every node, 10 to close.
    private let leg: CGFloat = 14
    private let plus: CGFloat = 18
    private let terminal: CGFloat = 10
    private let touch: CGFloat = 44
    private var run: CGFloat { leg + plus + leg }

    private func layout(_ extents: [CGFloat], touch: CGFloat? = nil) -> GraphLayout {
        nodeGraphLayout(
            nodeExtents: extents,
            leg: leg,
            plus: plus,
            terminal: terminal,
            touch: touch ?? self.touch
        )
    }

    /// The editor's first state, and the state it returns to when the last step is deleted: one
    /// trigger node. Without a `+` after it there is no way back to a two-node graph.
    func testASingleNodeStillOffersThePlusThatAppendsAfterIt() {
        let graph = layout([60])

        XCTAssertEqual(graph.nodes, [NodeBox(start: 0, extent: 60)])
        XCTAssertEqual(graph.connectors.count, 1)
        XCTAssertEqual(graph.connectors[0].start, 60)
        XCTAssertEqual(graph.connectors[0].plusStart, 60 + leg)
        XCTAssertEqual(graph.terminalStart, 60 + run)
        XCTAssertEqual(graph.extent, 60 + run + terminal)
    }

    /// `connectors[i]` inserts at position `i`, so the last one — after the last node — appends.
    func testTheLastConnectorAppendsAndTheOnesBetweenTheNodesInsert() {
        let graph = layout([60, 80, 40])

        XCTAssertEqual(graph.connectors.count, 3)
        for index in 0..<(graph.nodes.count - 1) {
            XCTAssertEqual(graph.nodes[index].end, graph.connectors[index].start)
            XCTAssertEqual(graph.nodes[index + 1].start, graph.connectors[index].end)
        }
        XCTAssertEqual(graph.nodes.last?.end, graph.connectors.last?.start)
        XCTAssertEqual(graph.terminalStart, graph.connectors.last?.end)
    }

    func testThePlusSitsBetweenItsTwoEqualLegs() {
        let first = layout([60, 80, 40]).connectors[0]

        XCTAssertEqual(first.start, 60)
        XCTAssertEqual(first.extent, run)
        XCTAssertEqual(first.plusStart, 60 + leg)
        XCTAssertEqual(first.plusEnd, 60 + leg + plus)
        XCTAssertEqual(first.plusStart - first.start, first.end - first.plusEnd, "the two legs differ")
    }

    /// docs/09 "접근성": the glyph stays 18pt, what takes the tap does not — and it stays in its run.
    func testThePlusIsTappedOverAtLeast44CentredOnTheGlyph() {
        for connector in layout([60, 80]).connectors {
            XCTAssertEqual(connector.touchSize, touch)
            XCTAssertEqual(connector.plusSize, plus)
            XCTAssertEqual(
                connector.plusStart + connector.plusSize / 2,
                connector.touchStart + connector.touchSize / 2,
                "the target is off the glyph it belongs to"
            )
            XCTAssertGreaterThanOrEqual(connector.touchStart, connector.start)
            XCTAssertLessThanOrEqual(connector.touchStart + connector.touchSize, connector.end)
        }
    }

    /// The glyph is 18pt and the target is 44, so the target *cannot* stay off the two legs it is
    /// centred between — which is why `NodeGraph` draws them with `allowsHitTesting(false)`. A leg
    /// that took taps would take most of the `+`'s own.
    func testTheTargetNecessarilyCoversTheLegsItIsCentredBetween() {
        for connector in layout([60, 80]).connectors {
            XCTAssertLessThan(connector.touchStart, connector.plusStart, "the leading leg is clear")
            XCTAssertGreaterThan(
                connector.touchStart + connector.touchSize,
                connector.plusEnd,
                "the trailing leg is clear"
            )
        }
    }

    /// A target longer than the run would steal the neighbouring nodes' taps, so it is clamped.
    func testATargetThatCannotFitTheRunIsClampedToIt() {
        let connector = layout([60], touch: run + 20).connectors[0]

        XCTAssertEqual(connector.touchSize, run)
        XCTAssertEqual(connector.touchStart, connector.start)
    }

    func testNodesFollowOneAnotherWithTheConnectorsBetweenThem() {
        XCTAssertEqual(
            layout([60, 80, 40]).nodes,
            [
                NodeBox(start: 0, extent: 60),
                NodeBox(start: 60 + run, extent: 80),
                NodeBox(start: 60 + run + 80 + run, extent: 40),
            ]
        )
    }

    func testTheGraphEndsOneRunAfterTheLastNodeAndIsAsLongAsItsParts() {
        let extents: [CGFloat] = [60, 80, 40]
        let graph = layout(extents)

        XCTAssertEqual(graph.terminalStart, extents.reduce(0, +) + CGFloat(extents.count) * run)
        XCTAssertEqual(graph.extent, graph.terminalStart + terminal)
    }

    /// The node's extent is whatever its text and the user's Dynamic Type size made it — measured,
    /// never assumed.
    func testANodeOfAnyExtentIsPlacedRatherThanAssumed() {
        let graph = layout([10, 400])

        XCTAssertEqual(graph.nodes[0].extent, 10)
        XCTAssertEqual(graph.nodes[1].extent, 400)
        XCTAssertGreaterThan(graph.nodes[1].start, graph.nodes[0].end)
    }

    func testAnEmptyGraphHasNoExtent() {
        let graph = layout([])

        XCTAssertEqual(graph.nodes, [])
        XCTAssertEqual(graph.connectors, [])
        XCTAssertEqual(graph.extent, 0)
    }
}
