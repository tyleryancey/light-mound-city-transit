package moundcity.transit.core.query

/**
 * D13 tap resolution: the nearest projected glyph within a finger radius,
 * or nothing. Callers build the parallel arrays from the same transform the
 * canvas drew with, so hit-testing follows zoom and pan for free.
 */
object GlyphHitTest {

    fun nearest(xs: FloatArray, ys: FloatArray, x: Float, y: Float, radiusPx: Float): Int? {
        var best = -1
        var bestD2 = radiusPx * radiusPx
        for (i in xs.indices) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD2) {
                bestD2 = d2
                best = i
            }
        }
        return if (best >= 0) best else null
    }
}
