package moundcity.transit.core.query

import java.time.LocalDate
import java.time.ZoneId
import moundcity.transit.core.time.ServiceDay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Build plan 1.20: predicted == scheduled + delay. Doc 01 counted 8,450/8,453
 * with 3 exceptions it attributed to its own naive stop_id→row matching on
 * loop routes; this check matches the k-th STU occurrence of a stop to the
 * k-th scheduled occurrence, so the artifact should vanish. Quote observed
 * values either way.
 */
class RtStaticAgreementTest {

    private val chicago = ZoneId.of("America/Chicago")
    private val serviceDate = LocalDate.of(2026, 8, 3)

    @Test
    fun noRealtimeRecordOfAnyKindTouchesARailRoute() {
        // 1.21's global half (review finding: only the single-stop golden
        // existed). Every live trip, every cancellation, and every vehicle fix
        // must resolve to a non-rail route — a re-captured fixture with partial
        // rail RT should fail HERE, not slip through.
        val index = QueryTestData.index
        val rail = setOf("19731B", "19731R", "19870B", "19870R")
        val rtTripIds = QueryTestData.rtTrips.entities.map { it.tripId.toInt() } +
            QueryTestData.rtVehicles.fixes.map { it.tripId.toInt() }
        val touched = rtTripIds.mapNotNull { index.tripIndexOf(it) }
            .map { index.routeId(index.tripRoute(it)) }
            .filter { it in rail }
        assertEquals(emptyList(), touched, "zero realtime for MetroLink, across all 280 RT records (doc 01 §5d)")
    }

    @Test
    fun rawSampleCountMatchesDoc01() {
        val raw = QueryTestData.rtTrips.entities.filter { !it.canceled }
            .sumOf { e -> e.stus.count { it.delay != null && it.time != null } }
        assertEquals(8453, raw, "doc 01's 8,453 samples are the RAW (pre-dedup) count")
    }

    @Test
    fun predictedEqualsScheduledPlusDelayOnEveryDedupedSample() {
        val index = QueryTestData.index
        var samples = 0
        val mismatches = mutableListOf<String>()
        for (e in QueryTestData.rtTrips.entities.filter { !it.canceled }) {
            val tripIdx = index.tripIndexOf(e.tripId.toInt()) ?: continue
            val scheduled = index.tripStops(tripIdx, fromSeq = 0)
            // k-th occurrence of each stop id in the scheduled sequence
            val byStopOccurrence = HashMap<String, ArrayDeque<Int>>()
            for (s in scheduled) {
                byStopOccurrence.getOrPut(index.stopCode(s.stopIdx).toString()) { ArrayDeque() }.add(s.minute)
            }
            for (stu in e.dedupedStus()) {
                val delay = stu.delay ?: continue
                val time = stu.time ?: continue
                val minute = byStopOccurrence[stu.stopId]?.removeFirstOrNull() ?: continue
                samples++
                val predicted = ServiceDay.resolve(serviceDate, minute * 60, chicago).epochSecond + delay
                if (predicted != time) {
                    mismatches.add("trip ${e.tripId} stop ${stu.stopId}: predicted $predicted wire $time (Δ ${time - predicted})")
                }
            }
        }
        assertEquals(7084, samples, "all deduped time+delay samples matched a scheduled occurrence")
        assertEquals(
            emptyList(),
            mismatches,
            "occurrence-aware matching should dissolve doc 01's 3 loop-route exceptions; observed ${mismatches.size} mismatches",
        )
    }
}
