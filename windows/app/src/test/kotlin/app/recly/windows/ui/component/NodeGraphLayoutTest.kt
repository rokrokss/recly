package app.recly.windows.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/09 화면 원칙 3: where the nodes, the connectors and their `+` land across the window. */
class NodeGraphLayoutTest {

    /**
     * The editor's first state, and the state it returns to when the last step is deleted: one
     * trigger node. Without a `+` after it there is no way back to a two-node graph.
     */
    @Test
    fun `a single node still offers the plus that appends after it`() {
        val layout = nodeGraphLayout(listOf(200), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(listOf(NodeBox(left = 0, width = 200)), layout.nodes)
        assertEquals(1, layout.connectors.size)
        assertEquals(200, layout.connectors[0].left)
        assertEquals(200 + LEG, layout.connectors[0].plusLeft)
        assertEquals(200 + RUN, layout.terminalLeft)
        assertEquals(200 + RUN + TERMINAL, layout.width)
    }

    /** `connectors[i]` inserts at position `i`, so the last one — after the last node — appends. */
    @Test
    fun `the last connector appends, the ones between the nodes insert`() {
        val layout = nodeGraphLayout(listOf(200, 240, 160), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(3, layout.connectors.size)
        layout.nodes.zipWithNext().forEachIndexed { index, (left, right) ->
            assertEquals(left.right, layout.connectors[index].left)
            assertEquals(right.left, layout.connectors[index].right)
        }
        assertEquals(layout.nodes.last().right, layout.connectors.last().left)
        assertEquals(layout.terminalLeft, layout.connectors.last().right)
    }

    @Test
    fun `every gap gets one connector, and the plus sits between its two legs`() {
        val layout = nodeGraphLayout(listOf(200, 240, 160), LEG, PLUS, TERMINAL, TOUCH)

        val first = layout.connectors[0]
        assertEquals(200, first.left)
        assertEquals(RUN, first.width)
        assertEquals(200 + LEG, first.plusLeft)
        assertEquals(200 + LEG + PLUS, first.plusRight)
        assertEquals(first.plusLeft - first.left, first.right - first.plusRight, "the two legs differ")
    }

    /** docs/09 "접근성": the glyph stays 18dp, what takes the click does not — and it stays in its run. */
    @Test
    fun `the plus is clicked over at least 44, centred on the glyph`() {
        val layout = nodeGraphLayout(listOf(200, 240), LEG, PLUS, TERMINAL, TOUCH)

        layout.connectors.forEach { run ->
            assertEquals(TOUCH, run.touchSize)
            assertEquals(PLUS, run.plusSize)
            assertEquals(
                run.plusLeft + run.plusSize / 2,
                run.touchLeft + run.touchSize / 2,
                "the target is off the glyph it belongs to",
            )
            assertTrue(run.touchLeft >= run.left, "the target reaches into the node before it")
            assertTrue(run.touchLeft + run.touchSize <= run.right, "the target reaches into the node after it")
        }
    }

    /** A target wider than the run would steal the neighbouring nodes' clicks, so it is clamped. */
    @Test
    fun `a target that cannot fit the run is clamped to it`() {
        val layout = nodeGraphLayout(listOf(200), LEG, PLUS, TERMINAL, touch = RUN + 20)

        assertEquals(RUN, layout.connectors[0].touchSize)
        assertEquals(layout.connectors[0].left, layout.connectors[0].touchLeft)
    }

    @Test
    fun `nodes follow one another with the connectors between them`() {
        val layout = nodeGraphLayout(listOf(200, 240, 160), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(
            listOf(NodeBox(0, 200), NodeBox(200 + RUN, 240), NodeBox(200 + RUN + 240 + RUN, 160)),
            layout.nodes,
        )
    }

    @Test
    fun `the graph ends one run after the last node, and is as wide as its parts`() {
        val widths = listOf(200, 240, 160)
        val layout = nodeGraphLayout(widths, LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(widths.sum() + widths.size * RUN, layout.terminalLeft)
        assertEquals(layout.terminalLeft + TERMINAL, layout.width)
    }

    @Test
    fun `a node of any width is placed, not assumed`() {
        val layout = nodeGraphLayout(listOf(40, 900), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(40, layout.nodes[0].width)
        assertEquals(900, layout.nodes[1].width)
        assertTrue(layout.nodes[1].left > layout.nodes[0].right)
    }

    @Test
    fun `an empty graph has no width`() {
        val layout = nodeGraphLayout(emptyList(), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(emptyList(), layout.nodes)
        assertEquals(emptyList(), layout.connectors)
        assertEquals(0, layout.width)
    }

    private companion object {
        /** The mockup's rhythm, in pixels: 16 + 18 + 16 after every node, 10 to close. */
        const val LEG = 16
        const val PLUS = 18
        const val TERMINAL = 10
        const val TOUCH = 44
        const val RUN = LEG + PLUS + LEG
    }
}
