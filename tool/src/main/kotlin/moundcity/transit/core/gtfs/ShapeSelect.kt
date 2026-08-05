package moundcity.transit.core.gtfs

/**
 * Representative-shape selection and decimation for the route viewer (D12).
 * douglasPeucker is transcribed operation-for-operation in
 * harness/build_index.py — the byte-diff manifest proves the two agree.
 */
object ShapeSelect {

    /** 10 m expressed in degrees of latitude. */
    const val TOLERANCE_DEG: Double = 10.0 / 111_000.0

    data class RepShape(val routeIdx: Int, val directionId: Int, val points: List<ShapePoint>)

    fun representatives(feed: GtfsFeed, routeIdxById: Map<String, Int>): List<RepShape> {
        val usage = HashMap<String, Int>()
        for (t in feed.trips) if (t.shapeId.isNotEmpty()) usage.merge(t.shapeId, 1) { a, b -> a + b }
        return feed.trips
            .filter { it.shapeId.isNotEmpty() }
            .groupBy { it.routeId to it.directionId }
            .map { (key, group) ->
                // most-used wins; ties break to the lexicographically smallest id
                val rep = group.map { it.shapeId }.distinct()
                    .sortedWith(compareByDescending<String> { usage.getValue(it) }.thenBy { it })
                    .first()
                RepShape(
                    routeIdxById.getValue(key.first), key.second,
                    douglasPeucker(feed.shapes.getValue(rep), TOLERANCE_DEG),
                )
            }
            .sortedWith(compareBy({ it.routeIdx }, { it.directionId }))
    }

    fun douglasPeucker(points: List<ShapePoint>, tolDeg: Double): List<ShapePoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val ax = points[a].lat; val ay = points[a].lon
            val bx = points[b].lat; val by = points[b].lon
            val dx = bx - ax; val dy = by - ay
            var n = Math.sqrt(dx * dx + dy * dy)
            if (n == 0.0) n = 1e-12
            var best = 0.0
            var bi = -1
            for (i in a + 1 until b) {
                val px = points[i].lat; val py = points[i].lon
                val d = Math.abs(dx * (ay - py) - dy * (ax - px)) / n
                if (d > best) { best = d; bi = i }
            }
            if (best > tolDeg) {
                keep[bi] = true
                stack.addLast(a to bi)
                stack.addLast(bi to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
