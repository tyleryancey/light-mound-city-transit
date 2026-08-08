package moundcity.transit.core.query

import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * D13 orientation layer, phase 1: every OTHER route's shape whose bounding
 * box overlaps the viewed route's, drawn thin and lightened behind it. This
 * is the tool's own feed geometry — already bundled, nothing fetched, no
 * basemap. A self-drawn street layer is a separate future decision.
 */
object ContextRoutes {

    fun select(index: ScheduleIndex, routeIdx: Int, directionId: Int): List<IntArray> {
        val viewed = index.routeShape(routeIdx, directionId) ?: return emptyList()
        val vb = bboxOf(viewed)
        val out = mutableListOf<IntArray>()
        for (r in 0 until index.routeCount) {
            if (r == routeIdx) continue
            val shape = index.routeShape(r, 0) ?: index.routeShape(r, 1) ?: continue
            // The rail pick twins (19731B/19870B — one line, two route_ids
            // across the service-change transition) carry identical geometry.
            // Drawing it as "context" would just paint the viewed line twice.
            if (shape.contentEquals(viewed)) continue
            if (intersects(bboxOf(shape), vb)) out.add(shape)
        }
        return out
    }

    /** [minLat, minLon, maxLat, maxLon] */
    private fun bboxOf(shape: IntArray): IntArray {
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
        return intArrayOf(minLat, minLon, maxLat, maxLon)
    }

    private fun intersects(a: IntArray, b: IntArray): Boolean =
        a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]
}
