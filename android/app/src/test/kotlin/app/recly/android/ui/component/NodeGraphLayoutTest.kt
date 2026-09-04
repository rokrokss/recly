package app.recly.android.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/09 화면 원칙 3: where the nodes, the connectors and their `+` land. */
class NodeGraphLayoutTest {

    /**
     * The editor's first state, and the state it returns to when the last step is deleted: one
     * trigger node. Without a `+` under it there is no way back to a two-node graph.
     */
    @Test
    fun `a single node still offers the plus that appends after it`() {
        val layout = nodeGraphLayout(listOf(60), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(listOf(NodeBox(top = 0, height = 60)), layout.nodes)
        assertEquals(1, layout.connectors.size)
        assertEquals(60, layout.connectors[0].top)
        assertEquals(60 + LEG, layout.connectors[0].plusTop)
        assertEquals(60 + RUN, layout.terminalTop)
        assertEquals(60 + RUN + TERMINAL, layout.height)
    }

    /** `connectors[i]` inserts at position `i`, so the last one — after the last node — appends. */
    @Test
    fun `the last connector appends, the ones between the nodes insert`() {
        val layout = nodeGraphLayout(listOf(60, 80, 40), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(3, layout.connectors.size)
        layout.nodes.zipWithNext().forEachIndexed { index, (above, below) ->
            assertEquals(above.bottom, layout.connectors[index].top)
            assertEquals(below.top, layout.connectors[index].bottom)
        }
        assertEquals(layout.nodes.last().bottom, layout.connectors.last().top)
        assertEquals(layout.terminalTop, layout.connectors.last().bottom)
    }

    @Test
    fun `every gap gets one connector, and the plus sits between its two legs`() {
        val layout = nodeGraphLayout(listOf(60, 80, 40), LEG, PLUS, TERMINAL, TOUCH)

        val first = layout.connectors[0]
        assertEquals(60, first.top)
        assertEquals(RUN, first.height)
        assertEquals(60 + LEG, first.plusTop)
        assertEquals(60 + LEG + PLUS, first.plusBottom)
        assertEquals(first.plusTop - first.top, first.bottom - first.plusBottom, "the two legs differ")
    }

    /** docs/09 "접근성": the glyph stays 18dp, what takes the tap does not — and it stays in its run. */
    @Test
    fun `the plus is tapped over at least 44, centred on the glyph`() {
        val layout = nodeGraphLayout(listOf(60, 80), LEG, PLUS, TERMINAL, TOUCH)

        layout.connectors.forEach { run ->
            assertEquals(TOUCH, run.touchSize)
            assertEquals(PLUS, run.plusSize)
            assertEquals(
                run.plusTop + run.plusSize / 2,
                run.touchTop + run.touchSize / 2,
                "the target is off the glyph it belongs to",
            )
            assertTrue(run.touchTop >= run.top, "the target reaches into the node above")
            assertTrue(run.touchTop + run.touchSize <= run.bottom, "the target reaches into the node below")
        }
    }

    /** A target wider than the run would steal the neighbouring nodes' taps, so it is clamped. */
    @Test
    fun `a target that cannot fit the run is clamped to it`() {
        val layout = nodeGraphLayout(listOf(60), LEG, PLUS, TERMINAL, touch = RUN + 20)

        assertEquals(RUN, layout.connectors[0].touchSize)
        assertEquals(layout.connectors[0].top, layout.connectors[0].touchTop)
    }

    @Test
    fun `nodes stack under one another with the connectors between them`() {
        val layout = nodeGraphLayout(listOf(60, 80, 40), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(
            listOf(NodeBox(0, 60), NodeBox(60 + RUN, 80), NodeBox(60 + RUN + 80 + RUN, 40)),
            layout.nodes,
        )
    }

    @Test
    fun `the graph ends one run below the last node, and is as tall as its parts`() {
        val heights = listOf(60, 80, 40)
        val layout = nodeGraphLayout(heights, LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(heights.sum() + heights.size * RUN, layout.terminalTop)
        assertEquals(layout.terminalTop + TERMINAL, layout.height)
    }

    @Test
    fun `a node of any height is placed, not assumed`() {
        val layout = nodeGraphLayout(listOf(10, 400), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(10, layout.nodes[0].height)
        assertEquals(400, layout.nodes[1].height)
        assertTrue(layout.nodes[1].top > layout.nodes[0].bottom)
    }

    @Test
    fun `an empty graph has no height`() {
        val layout = nodeGraphLayout(emptyList(), LEG, PLUS, TERMINAL, TOUCH)

        assertEquals(emptyList(), layout.nodes)
        assertEquals(emptyList(), layout.connectors)
        assertEquals(0, layout.height)
    }

    private companion object {
        /** The mockup's rhythm, in pixels: 14 + 18 + 14 under every node, 10 to close. */
        const val LEG = 14
        const val PLUS = 18
        const val TERMINAL = 10
        const val TOUCH = 44
        const val RUN = LEG + PLUS + LEG
    }
}
