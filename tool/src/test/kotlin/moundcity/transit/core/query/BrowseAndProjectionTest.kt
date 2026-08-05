package moundcity.transit.core.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Build plan 3.5 (Browse) and 3.11 (viewer projection), pure JVM. */
class BrowseAndProjectionTest {

    private val index get() = QueryTestData.index

    @Test
    fun railStationsAreThirtyEightAlphabetical() {
        val stations = BrowseCatalog.railStations(index)
        assertEquals(38, stations.size, "doc 02 §3.4: 38 stations, one stop each")
        assertEquals(stations.map { it.name }.sorted(), stations.map { it.name }, "alphabetical")
        assertTrue(stations.all { it.name.endsWith("METROLINK STATION") }, "the feed's own naming")
        assertEquals("5TH & MISSOURI METROLINK STATION", stations.first().name, "first alphabetically")
    }

    @Test
    fun transitCentersMergeToThirtyNamedCenters() {
        val centers = BrowseCatalog.transitCenters(index)
        assertEquals(30, centers.size, "45 stops collapse to 30 named centers (handoff review)")
        assertEquals(12, centers.count { it.stopIdxs.size > 1 }, "12 centers have 2–5 platform stops")
        assertEquals(5, centers.maxOf { it.stopIdxs.size }, "Civic Center alone has 5")
        assertEquals(45, centers.sumOf { it.stopIdxs.size }, "no stop lost in the merge")
        assertEquals(centers.map { it.name }.sorted(), centers.map { it.name }, "alphabetical")
    }

    @Test
    fun routesGroupMoIlRail() {
        val groups = BrowseCatalog.routesGrouped(index)
        assertEquals(44, groups.missouri.size, "58 buses minus 14 Illinois")
        assertEquals(14, groups.illinois.size, "route_ids 19855–19868")
        assertEquals(2, groups.rail.size, "two lines — the pick twins merge under one label")
        assertEquals(listOf("MLB", "MLR"), groups.rail.map { it.label }, "Blue and Red")
        assertTrue(groups.rail.all { it.routeIdxs.size == 2 }, "each line spans its two pick route_ids")
        val moLabels = groups.missouri.map { it.label }.toSet()
        assertTrue(groups.illinois.none { it.label in moLabels }, "no IL label collides with a MO label — the 1.22 markers guarantee it")
        assertEquals(8, groups.illinois.count { it.label.endsWith(" IL") }, "exactly the eight colliding numbers carry the IL marker")
    }

    @Test
    fun projectionFitsAndPreservesAspect() {
        // Two points 0.02° apart in lat only, canvas 1000×1000 pad 100:
        // the long axis spans the padded range; the flat axis centers.
        val shape = intArrayOf(38_000_000, -90_000_000, 38_020_000, -90_000_000)
        val p = ShapeProjection.fit(shape, width = 1000f, height = 1000f, pad = 100f)
        assertEquals(500f, p.x(0), 0.5f, "flat axis centers")
        assertEquals(900f, p.y(0), 0.5f, "south point at the bottom of the padded range")
        assertEquals(500f, p.x(1), 0.5f, "still centered")
        assertEquals(100f, p.y(1), 0.5f, "north point at the top")
        // an off-line point projects with the same transform
        val (sx, sy) = p.project(38_010_000, -90_000_000)
        assertEquals(500f, sx, 0.5f, "midpoint x")
        assertEquals(500f, sy, 0.5f, "midpoint y")
    }

    private fun assertEquals(expected: Float, actual: Float, tol: Float, message: String) {
        assertTrue(Math.abs(expected - actual) <= tol, "$message: expected $expected got $actual")
    }
}
