package moundcity.transit.core.query

/**
 * D13 direction word from a shape's NET displacement, cos-corrected in
 * longitude. Loops and out-and-backs have small net displacement relative
 * to their bounding box — those return null rather than a lie (spec rule:
 * displacement < 30% of the bbox diagonal), and the screen falls back to
 * the headsign alone.
 */
object RouteBearing {

    private const val MIN_DISPLACEMENT_RATIO = 0.3

    fun of(shape: IntArray): String? {
        if (shape.size < 4) return null
        var minLat = Int.MAX_VALUE
        var maxLat = Int.MIN_VALUE
        var minLon = Int.MAX_VALUE
        var maxLon = Int.MIN_VALUE
        var i = 0
        while (i < shape.size) {
            val la = shape[i]
            val lo = shape[i + 1]
            if (la < minLat) minLat = la
            if (la > maxLat) maxLat = la
            if (lo < minLon) minLon = lo
            if (lo > maxLon) maxLon = lo
            i += 2
        }
        val lonScale = Math.cos(Math.toRadians((minLat + maxLat) / 2.0 / 1e6))
        val dLat = (shape[shape.size - 2] - shape[0]).toDouble()
        val dLon = (shape[shape.size - 1] - shape[1]) * lonScale
        val displacement = Math.hypot(dLat, dLon)
        val diagonal = Math.hypot((maxLat - minLat).toDouble(), (maxLon - minLon) * lonScale)
        if (diagonal == 0.0 || displacement < MIN_DISPLACEMENT_RATIO * diagonal) return null
        return if (Math.abs(dLon) >= Math.abs(dLat)) {
            if (dLon >= 0) "eastbound" else "westbound"
        } else {
            if (dLat >= 0) "northbound" else "southbound"
        }
    }
}
