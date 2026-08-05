package moundcity.transit.core.query

import moundcity.transit.core.gtfs.StopTimeRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Route-viewer plan Task 5 (build task 1.24): "about N stops back" from
 * tripStops + stop_geo only. Distances are miles (user decision 2026-08-05).
 */
class ApproachTest {

    // A straight north-south line of 6 stops, 0.01° (~0.69 mi) apart, seq 1..6.
    private val stops = (0 until 6).map { StopTimeRow(stopIdx = it, minute = 480 + it, seq = it + 1) }
    private fun lat(idx: Int) = 38_000_000 + idx * 10_000
    private fun lon(@Suppress("UNUSED_PARAMETER") idx: Int) = -90_000_000

    private fun est(targetSeq: Int, vLat: Int, vLon: Int) =
        Approach.estimate(stops, targetSeq, vLat, vLon, ::lat, ::lon)

    @Test
    fun fourStopsBackReadsAboutFour() {
        val e = est(targetSeq = 6, vLat = lat(1), vLon = lon(1))
        assertEquals("about 4 stops away", (e as ApproachEstimate.StopsAway).text(), "vehicle at stop 2, target stop 6")
        assertNull(e.distanceMi, "distance only rides along at N == 1")
    }

    @Test
    fun oneStopBackCarriesDistance() {
        val e = est(targetSeq = 3, vLat = lat(1), vLon = lon(1)) as ApproachEstimate.StopsAway
        assertEquals(1, e.n, "vehicle at stop 2, target stop 3")
        assertEquals("about 1 stop away · 0.7 mi (straight line)", e.text(), "0.01° latitude spacing ≈ 0.69 mi; the straight-line words are load-bearing")
    }

    @Test
    fun atTargetReadsApproaching() {
        val e = est(targetSeq = 3, vLat = lat(2) - 2_000, vLon = lon(2))
        assertEquals("approaching · 0.1 mi (straight line)", (e as ApproachEstimate.Approaching).text(), "nearest is the target itself, 0.002° ≈ 0.14 mi short")
    }

    @Test
    fun pastTargetReadsPassed() {
        val e = est(targetSeq = 2, vLat = lat(4), vLon = lon(4))
        assertEquals("passed", (e as ApproachEstimate.Passed).text(), "vehicle beyond the target stop")
    }

    @Test
    fun unknownSequenceIsNull() {
        assertNull(est(targetSeq = 99, vLat = lat(0), vLon = lon(0)), "target seq not on this trip")
    }

    @Test
    fun loopTripTieResolvesToFirstOccurrenceConservatively() {
        // stopIdx 0 appears at seq 1 AND seq 5 — a loop's repeated stop is the
        // same physical location, so the two occurrences are ALWAYS an exact
        // distance tie. The pinned rule: first occurrence wins, which counts
        // conservatively (says "about 5" when it might be 1 — never the reverse).
        val loop = listOf(
            StopTimeRow(0, 480, 1), StopTimeRow(1, 482, 2), StopTimeRow(2, 484, 3),
            StopTimeRow(3, 486, 4), StopTimeRow(0, 488, 5), StopTimeRow(4, 490, 6),
        )
        val e = Approach.estimate(
            loop, targetSeq = 6, vehLatMicro = lat(0), vehLonMicro = lon(0),
            stopLatMicro = ::lat, stopLonMicro = ::lon,
        )
        assertEquals(5, (e as ApproachEstimate.StopsAway).n, "strict '<' keeps the first occurrence; the count overestimates rather than promising early")
    }

    @Test
    fun distanceStringIsLocaleStable() {
        // Review finding: no-locale %.1f follows the device locale — a
        // comma-decimal device rendered "0,7 mi".
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val e = est(targetSeq = 3, vLat = lat(1), vLon = lon(1)) as ApproachEstimate.StopsAway
            assertEquals("about 1 stop away · 0.7 mi (straight line)", e.text(), "the decimal separator must not follow the device locale")
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test
    fun realFixtureGoldenTrip3407211() {
        // Golden computed independently in Python during planning (2026-08-05):
        // vehicle (38.734341, -90.354401) on trip 3407211; nearest stop 9208 at
        // seq 92; target stop 9213 at seq 96 -> about 4 stops away (~0.9 mi).
        val index = QueryTestData.index
        val trip = index.tripIndexOf(3407211)!!
        val e = Approach.estimate(
            index.tripStops(trip, fromSeq = 0), targetSeq = 96,
            vehLatMicro = 38_734_340, vehLonMicro = -90_354_400,
            stopLatMicro = index::stopLatMicro, stopLonMicro = index::stopLonMicro,
        )
        assertEquals("about 4 stops away", (e as ApproachEstimate.StopsAway).text(), "the planning-time Python golden (N=4; distance not shown at N>1)")
    }
}
