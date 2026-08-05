package moundcity.transit.core.query

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Build plan 1.22. Ten short names collide in the fixture: eight MO/IL bus
 * pairs (doc 01 said eight — the other two are MLB/MLR colliding across the
 * pick transition, the same physical lines) — bus pairs carry a discreet
 * MO/IL marker (doc 02 §3.4), rail renders bare.
 */
class RouteLabelsTest {

    private val index get() = QueryTestData.index

    private fun label(routeId: String): String =
        RouteLabels.displayShortName(index, index.routeIndexOf(routeId)!!)

    @Test
    fun collidingBusPairsCarryMoIlMarkers() {
        assertEquals("1 MO", label("19811"), "Missouri 1 (Gold)")
        assertEquals("1 IL", label("19855"), "Illinois 1 (Main Street - State Street)")
        assertEquals("9 MO", label("19816"), "Missouri 9 (Oakville)")
        assertEquals("9 IL", label("19862"), "Illinois 9 (Washington Park)")
    }

    @Test
    fun allEightBusCollisionsResolveDistinctly() {
        val shorts = listOf("1", "2", "4", "5", "8", "9", "13", "16")
        for (s in shorts) {
            val idxs = (0 until index.routeCount).filter { index.routeShortName(it) == s }
            assertEquals(2, idxs.size, "short name '$s' is shared by exactly two routes")
            val labels = idxs.map { RouteLabels.displayShortName(index, it) }
            assertEquals(2, labels.distinct().size, "'$s' renders as two distinct labels: $labels")
            assertEquals(true, labels.none { it == s }, "a colliding number never renders bare: $labels")
        }
    }

    @Test
    fun railPickTwinsRenderBare() {
        assertEquals("MLB", label("19731B"), "old-pick Blue")
        assertEquals("MLB", label("19870B"), "new-pick Blue — same line, no marker")
        assertEquals("MLR", label("19731R"), "old-pick Red")
        assertEquals("MLR", label("19870R"), "new-pick Red")
    }

    @Test
    fun nonCollidingRoutesRenderBare() {
        val bare = (0 until index.routeCount).first { idx ->
            val s = index.routeShortName(idx)
            (0 until index.routeCount).count { index.routeShortName(it) == s } == 1
        }
        assertEquals(index.routeShortName(bare), RouteLabels.displayShortName(index, bare), "unique numbers stay unadorned")
    }

    @Test
    fun illinoisDetectionIsTheRouteIdRange() {
        val il = (0 until index.routeCount).filter { RouteLabels.isIllinois(index.routeId(it)) }
        assertEquals(14, il.size, "route_ids 19855–19868 are the Illinois block")
    }
}
