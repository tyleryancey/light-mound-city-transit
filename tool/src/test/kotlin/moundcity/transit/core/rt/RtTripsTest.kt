package moundcity.transit.core.rt

import moundcity.transit.core.query.QueryTestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build plan 1.12 + 1.13, all values verified against the fixture via the
 * Python oracle on 2026-08-05: 153 entities, 127 live, 26 canceled, 20 trips
 * with every StopTimeUpdate adjacently duplicated, 17 live trips with no delay.
 */
class RtTripsTest {

    private val trips: RtTrips get() = QueryTestData.rtTrips

    @Test
    fun entityCountsMatchTheOracle() {
        assertEquals(1785775752L, trips.headerTimestamp, "header timestamp, byte-identical with Vehicles")
        assertEquals(153, trips.entities.size, "153 entities")
        assertEquals(127, trips.entities.count { !it.canceled }, "127 live trips")
        assertEquals(26, trips.canceledTrips().size, "26 canceled")
    }

    @Test
    fun delayReductionKeeps127TripsWith17OnTime() {
        val delays = trips.delayByTrip()
        assertEquals(127, delays.size, "one reduced record per live trip")
        assertEquals(17, delays.values.count { it == null }, "absent delay = on time, 17 trips")
        assertEquals(180, delays.getValue(3407211), "trip 3407211 runs +180 s (oracle value)")
        assertTrue(delays.values.filterNotNull().all { it in -300..1200 }, "delays inside the observed −5…+20 min band")
    }

    @Test
    fun negativeDelaysAreTheOrdinaryCase() {
        val neg = trips.delayByTrip().values.filterNotNull().count { it < 0 }
        assertTrue(neg > 20, "early buses are ordinary, not an edge case; got $neg negative")
    }

    @Test
    fun twentyTripsAreFullyAdjacentDuplicatedAndDedupMatchesTheSchedule() {
        val dup = trips.entities.filter { !it.canceled && it.isFullyAdjacentDuplicated() }
        assertEquals(20, dup.size, "20 of 127 live trips carry every STU twice (doc 01 §5c)")

        val index = QueryTestData.index
        for (e in dup) {
            val deduped = e.dedupedStus()
            assertEquals(e.stus.size / 2, deduped.size, "dedup halves trip ${e.tripId}")
            val tripIdx = index.tripIndexOf(e.tripId.toInt())!!
            val scheduled = index.tripStops(tripIdx, fromSeq = 0).map { index.stopCode(it.stopIdx).toString() }
            assertEquals(scheduled, deduped.map { it.stopId }, "deduped STU stop ids reproduce trip ${e.tripId}'s scheduled sequence")
        }
    }

    @Test
    fun nonDuplicatedTripKeepsItsFullSequence() {
        val e = trips.entities.first { it.tripId == "3407211" }
        assertEquals(104, e.stus.size, "trip 3407211 has 104 STUs, one per scheduled stop")
        assertEquals(e.stus.size, e.dedupedStus().size, "dedup is a no-op on a clean trip")
        assertNull(e.stus.firstOrNull { it.delay == null }, "every STU on this trip carries a delay")
    }
}
