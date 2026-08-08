package moundcity.transit.core.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D13: viewer interaction math — pure JVM, the screen only draws. */
class ViewerInteractionTest {

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(Math.abs(expected - actual) <= 0.51f, "$message: expected $expected got $actual")

    // --- Viewport (D13.1) ---

    @Test
    fun identityViewportIsANoOp() {
        val v = Viewport(width = 1000f, height = 800f)
        assertTrue(v.isIdentity, "fresh viewport is fit-to-screen")
        assertNear(123f, v.x(123f), "x passes through at 1×")
        assertNear(456f, v.y(456f), "y passes through at 1×")
        assertNear(123f, v.fromScreenX(v.x(123f)), "inverse round-trips x")
        assertNear(456f, v.fromScreenY(v.y(456f)), "inverse round-trips y")
    }

    @Test
    fun zoomIsClampedAndAnchoredAtTheCentroid() {
        val v = Viewport(1000f, 800f).transformed(centroidX = 500f, centroidY = 400f, panDX = 0f, panDY = 0f, zoomChange = 2f)
        assertEquals(2f, v.zoom, "pinch doubles the zoom")
        assertNear(500f, v.x(500f), "the centroid stays put under zoom")
        assertNear(400f, v.y(400f), "both axes")
        assertEquals(8f, v.transformed(500f, 400f, 0f, 0f, zoomChange = 100f).zoom, "zoom clamps at 8×")
        val floored = v.transformed(500f, 400f, 0f, 0f, zoomChange = 0.01f)
        assertEquals(1f, floored.zoom, "and at 1×")
        assertTrue(floored.isIdentity, "returning to 1× snaps pan home — fit is fit")
    }

    @Test
    fun panIsBoundedSoTheContentCannotBeLost() {
        val v = Viewport(1000f, 800f).transformed(500f, 400f, 0f, 0f, 2f)
        val dragged = v.transformed(500f, 400f, panDX = 99_999f, panDY = -99_999f, zoomChange = 1f)
        assertNear(0f, dragged.x(0f), "the left edge cannot be dragged right of the canvas edge")
        assertNear(800f, dragged.y(800f), "nor the bottom edge above the canvas bottom")
        assertEquals(0f, dragged.panX, "pan clamps at the content edge, not at the drag distance")
        assertEquals(-800f, dragged.panY, "and at the far edge on the other axis")
    }

    @Test
    fun resetReturnsToFit() {
        val v = Viewport(1000f, 800f).transformed(200f, 200f, 30f, 40f, 3f)
        assertTrue(!v.isIdentity, "a pinched, dragged viewport is not fit")
        assertTrue(v.reset().isIdentity, "double-tap resets to fit")
    }

    // --- RouteBearing (D13.2) ---

    @Test
    fun bearingFollowsNetDisplacement() {
        val east = intArrayOf(38_600_000, -90_300_000, 38_601_000, -90_100_000)
        assertEquals("eastbound", RouteBearing.of(east), "dominant +lon (cos-corrected) reads eastbound")
        assertEquals(
            "westbound",
            RouteBearing.of(intArrayOf(38_601_000, -90_100_000, 38_600_000, -90_300_000)),
            "reversed reads westbound",
        )
        val north = intArrayOf(38_500_000, -90_200_000, 38_700_000, -90_201_000)
        assertEquals("northbound", RouteBearing.of(north), "dominant +lat reads northbound")
        assertEquals(
            "southbound",
            RouteBearing.of(intArrayOf(38_700_000, -90_201_000, 38_500_000, -90_200_000)),
            "reversed reads southbound",
        )
    }

    @Test
    fun loopsRefuseABearing() {
        // A closed square: large bbox, near-zero net displacement.
        val loop = intArrayOf(
            38_600_000, -90_300_000, 38_700_000, -90_300_000,
            38_700_000, -90_200_000, 38_600_000, -90_200_000,
            38_600_100, -90_299_900,
        )
        assertNull(RouteBearing.of(loop), "a loop must not claim a direction (30% displacement rule)")
        assertNull(RouteBearing.of(intArrayOf(38_600_000, -90_200_000)), "a single point has no direction")
    }

    @Test
    fun theBlueLineDirectionsAreOppositeEastWest() {
        val index = QueryTestData.index
        val blue = index.routeIndexOf("19731B")!!
        val d0 = RouteBearing.of(index.routeShape(blue, 0)!!)
        val d1 = RouteBearing.of(index.routeShape(blue, 1)!!)
        assertEquals(
            setOf("eastbound", "westbound"),
            setOf(d0, d1),
            "MetroLink Blue runs east-west; the two directions must disagree",
        )
    }

    // --- ScaleBar (D13.3) ---

    @Test
    fun projectionKnowsItsScale() {
        // 0.02° of latitude fitted into 1000px with 100px padding → 800px.
        // 0.02° ≈ 2226.4 m → ~2.783 m per fitted pixel.
        val shape = intArrayOf(38_000_000, -90_000_000, 38_020_000, -90_000_000)
        val p = ShapeProjection.fit(shape, width = 1000f, height = 1000f, pad = 100f)
        assertTrue(Math.abs(p.metersPerPixel - 2.783) < 0.01, "meters per fitted pixel; got ${p.metersPerPixel}")
    }

    @Test
    fun scaleBarPicksTheLargestNiceMileThatFits() {
        // At 2.783 m/px: 1 mi ≈ 578 px, 2 mi ≈ 1157 px.
        val bar = ScaleBar.pick(metersPerPixel = 2.783, maxWidthPx = 600f)!!
        assertEquals("1 mi", bar.label, "2 mi would not fit in 600px")
        assertTrue(Math.abs(bar.widthPx - 578.3f) < 1f, "bar width in px; got ${bar.widthPx}")
        assertEquals("¼ mi", ScaleBar.pick(2.783, 200f)!!.label, "a small budget steps down the ladder")
        assertNull(ScaleBar.pick(2.783, 10f), "nothing fits — draw no bar rather than a wrong one")
        // Zoom divides meters-per-pixel: at 4× the same canvas spans ¼ the ground.
        assertEquals("¼ mi", ScaleBar.pick(2.783 / 4.0, 600f)!!.label, "the zoomed-in bar shrinks honestly")
    }

    // --- GlyphHitTest (D13.4) ---

    @Test
    fun hitTestPicksTheNearestWithinTheFingerRadius() {
        val xs = floatArrayOf(100f, 200f, 300f)
        val ys = floatArrayOf(100f, 100f, 100f)
        assertEquals(1, GlyphHitTest.nearest(xs, ys, x = 210f, y = 108f, radiusPx = 48f), "the nearest glyph wins")
        assertEquals(0, GlyphHitTest.nearest(xs, ys, x = 149f, y = 100f, radiusPx = 60f), "near-ties break to the nearer: 49 < 51")
        assertNull(GlyphHitTest.nearest(xs, ys, x = 210f, y = 300f, radiusPx = 48f), "outside every radius is a no-op, never a guess")
        assertNull(GlyphHitTest.nearest(FloatArray(0), FloatArray(0), 0f, 0f, 48f), "no glyphs, no hit")
    }

    // --- ContextRoutes and the shared route/center helpers (D13.5) ---

    private fun bbox(shape: IntArray): IntArray {
        var minLat = Int.MAX_VALUE
        var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE
        var maxLon = Int.MIN_VALUE
        var i = 0
        while (i < shape.size) {
            minLat = minOf(minLat, shape[i]); maxLat = maxOf(maxLat, shape[i])
            minLon = minOf(minLon, shape[i + 1]); maxLon = maxOf(maxLon, shape[i + 1])
            i += 2
        }
        return intArrayOf(minLat, minLon, maxLat, maxLon)
    }

    private fun bboxesIntersect(a: IntArray, b: IntArray): Boolean =
        a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]

    @Test
    fun contextRoutesIntersectAndExcludeSelf() {
        val index = QueryTestData.index
        val blue = index.routeIndexOf("19731B")!!
        val viewed = index.routeShape(blue, 0)!!
        val context = ContextRoutes.select(index, blue, 0)
        assertTrue(context.isNotEmpty(), "routes cross the Blue line — the context layer cannot be empty")
        assertTrue(context.none { it.contentEquals(viewed) }, "the viewed shape is never its own context")
        assertTrue(context.size <= index.routeCount - 1, "bounded by the other routes; got ${context.size}")
        val vb = bbox(viewed)
        assertTrue(context.all { bboxesIntersect(bbox(it), vb) }, "every context shape's bbox intersects the viewed one")
    }

    @Test
    fun transitCenterIdentityHasOneOwner() {
        assertTrue(BrowseCatalog.isTransitCenter("CIVIC CENTER TRANSIT CENTER"), "the name-merge convention")
        assertTrue(!BrowseCatalog.isTransitCenter("GRAVOIS @ BATES EB"), "an ordinary stop is not a center")
    }

    @Test
    fun representativeTripBacksRouteStopsAndCarriesTheHeadsign() {
        val index = QueryTestData.index
        val blue = index.routeIndexOf("19731B")!!
        val trip = BrowseCatalog.representativeTrip(index, blue, 0)!!
        assertEquals(
            BrowseCatalog.routeStops(index, blue, 0),
            index.tripStops(trip, fromSeq = 0).map { it.stopIdx }.distinct(),
            "routeStops is exactly the representative trip's stop sequence",
        )
        assertTrue(
            index.headsign(index.tripHeadsign(trip)).isNotEmpty(),
            "and it carries the headsign the direction line shows",
        )
    }
}
