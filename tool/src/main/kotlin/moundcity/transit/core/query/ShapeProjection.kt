package moundcity.transit.core.query

/**
 * Equirectangular fit of a polyline's bounding box into a canvas (build plan
 * 3.11): longitude scaled by cos of the box's mid-latitude, aspect preserved,
 * centered inside the padding. Pure math so the viewer screen stays logic-free.
 */
class ShapeProjection private constructor(
    private val originLatMicro: Double,
    private val originLonMicro: Double,
    private val lonScale: Double,
    private val scale: Double,
    private val offsetX: Double,
    private val offsetY: Double,
    private val points: IntArray,
) {

    companion object {
        fun fit(shape: IntArray, width: Float, height: Float, pad: Float): ShapeProjection {
            var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
            var minLon = Int.MAX_VALUE; var maxLon = Int.MIN_VALUE
            var i = 0
            while (i < shape.size) {
                val la = shape[i]; val lo = shape[i + 1]
                if (la < minLat) minLat = la; if (la > maxLat) maxLat = la
                if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo
                i += 2
            }
            val midLatRad = Math.toRadians((minLat + maxLat) / 2.0 / 1e6)
            val lonScale = Math.cos(midLatRad)
            val spanX = (maxLon - minLon) * lonScale
            val spanY = (maxLat - minLat).toDouble()
            val availW = (width - 2 * pad).toDouble()
            val availH = (height - 2 * pad).toDouble()
            val scale = minOf(
                if (spanX > 0) availW / spanX else Double.MAX_VALUE,
                if (spanY > 0) availH / spanY else Double.MAX_VALUE,
            ).let { if (it == Double.MAX_VALUE) 1.0 else it }
            val offsetX = pad + (availW - spanX * scale) / 2
            val offsetY = pad + (availH - spanY * scale) / 2
            return ShapeProjection(minLat.toDouble(), minLon.toDouble(), lonScale, scale, offsetX, offsetY, shape)
        }
    }

    val pointCount: Int get() = points.size / 2

    fun x(i: Int): Float = project(points[i * 2], points[i * 2 + 1]).first
    fun y(i: Int): Float = project(points[i * 2], points[i * 2 + 1]).second

    /** Any (latMicro, lonMicro) — stops and vehicle fixes share the transform. */
    fun project(latMicro: Int, lonMicro: Int): Pair<Float, Float> {
        val px = offsetX + (lonMicro - originLonMicro) * lonScale * scale
        // screen y grows downward; north is up
        val southSpanned = (latMicro - originLatMicro) * scale
        val py = offsetY + (heightSpan() - southSpanned)
        return px.toFloat() to py.toFloat()
    }

    private fun heightSpan(): Double {
        var minLat = Int.MAX_VALUE; var maxLat = Int.MIN_VALUE
        var i = 0
        while (i < points.size) {
            val la = points[i]
            if (la < minLat) minLat = la; if (la > maxLat) maxLat = la
            i += 2
        }
        return (maxLat - minLat) * scale
    }
}
