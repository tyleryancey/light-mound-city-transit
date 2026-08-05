package moundcity.transit.core.query

import moundcity.transit.core.gtfs.StopTimeRow

sealed interface ApproachEstimate {
    fun text(): String

    data class StopsAway(val n: Int, val distanceMi: Double?) : ApproachEstimate {
        override fun text(): String =
            if (n == 1) "about 1 stop away · ${"%.1f".format(java.util.Locale.US, distanceMi)} mi (straight line)"
            else "about $n stops away"
    }

    data class Approaching(val distanceMi: Double) : ApproachEstimate {
        override fun text(): String = "approaching · ${"%.1f".format(java.util.Locale.US, distanceMi)} mi (straight line)"
    }

    data object Passed : ApproachEstimate {
        override fun text(): String = "passed"
    }
}

/**
 * "About N stops back" (D12, build plan 1.24). Reads the trip's stop sequence
 * and stop_geo only — never shapes — so it is independent of the viewer's
 * geometry. The vehicle resolves to its nearest trip-stop by equirectangular
 * distance with cos taken at the vehicle's latitude (one fixed reference); an
 * exact tie (a loop's repeated stop) keeps the FIRST occurrence, so the count
 * overestimates rather than promising early. Distances are miles (user
 * decision 2026-08-05).
 */
object Approach {

    /** Folk constant; 0.04% off 68.97, irrelevant at one decimal under 2 mi. */
    private const val MI_PER_DEG = 69.0

    fun estimate(
        tripStops: List<StopTimeRow>,
        targetSeq: Int,
        vehLatMicro: Int,
        vehLonMicro: Int,
        stopLatMicro: (Int) -> Int,
        stopLonMicro: (Int) -> Int,
    ): ApproachEstimate? {
        val targetPos = tripStops.indexOfFirst { it.seq == targetSeq }
        if (targetPos < 0) return null
        val vLat = vehLatMicro / 1e6
        val vLon = vehLonMicro / 1e6
        val k = Math.cos(Math.toRadians(vLat))
        var nearestPos = 0
        var best = Double.MAX_VALUE
        for (i in tripStops.indices) {
            val dLat = stopLatMicro(tripStops[i].stopIdx) / 1e6 - vLat
            val dLon = (stopLonMicro(tripStops[i].stopIdx) / 1e6 - vLon) * k
            val d2 = dLat * dLat + dLon * dLon
            if (d2 < best) { best = d2; nearestPos = i }
        }
        val n = targetPos - nearestPos
        return when {
            n < 0 -> ApproachEstimate.Passed
            n == 0 -> ApproachEstimate.Approaching(distanceTo(tripStops[targetPos], vLat, vLon, k, stopLatMicro, stopLonMicro))
            n == 1 -> ApproachEstimate.StopsAway(1, distanceTo(tripStops[targetPos], vLat, vLon, k, stopLatMicro, stopLonMicro))
            else -> ApproachEstimate.StopsAway(n, null)
        }
    }

    private fun distanceTo(
        stop: StopTimeRow,
        vLat: Double,
        vLon: Double,
        k: Double,
        stopLatMicro: (Int) -> Int,
        stopLonMicro: (Int) -> Int,
    ): Double {
        val dLat = stopLatMicro(stop.stopIdx) / 1e6 - vLat
        val dLon = (stopLonMicro(stop.stopIdx) / 1e6 - vLon) * k
        return Math.sqrt(dLat * dLat + dLon * dLon) * MI_PER_DEG
    }
}
