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
}
