package moundcity.transit.core.gtfs

import java.io.StringReader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build plan 1.8. The golden expectations are the Python reference's own query
 * output (build_index.py benchmark): stop 10624 after 11:50 on weekday services
 * {319-T1, 325-B1} yields eight MetroLink departures alternating Blue/Red.
 */
class ScheduleIndexTest {

    private val index: ScheduleIndex by lazy {
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        ScheduleIndex(IndexWriter.build(feed).container())
    }

    // serviceIds sorted: 319-T1=0, 319-T2=1, 325-B1=2, ...
    private val weekday = setOf(0, 2)

    @Test
    fun resolveStopFindsRealCodesAndRejectsUnknown() {
        val idx = index.resolveStop(10624)
        assertTrue(idx != null, "10624 is a real stop code")
        assertEquals(10624, index.stopCode(idx!!), "resolved index round-trips to its code")
        assertNull(index.resolveStop(1), "1 is below every real code (range starts at 127)")
        assertNull(index.resolveStop(99999), "99999 is past every real code")
    }

    @Test
    fun goldenBoardStop10624After1150Weekday() {
        val stop = index.resolveStop(10624)!!
        val rows = index.departures(stop, fromMinute = 11 * 60 + 50, services = weekday, limit = 8)
        assertEquals(
            listOf(710, 712, 720, 722, 730, 732, 740, 742),
            rows.map { it.minute },
            "minutes of the eight departures after 11:50",
        )
        assertEquals(
            listOf("MLB", "MLB", "MLR", "MLR", "MLB", "MLB", "MLR", "MLR"),
            rows.map { index.routeShortName(index.tripRoute(it.tripIdx)) },
            "Blue/Blue/Red/Red alternation, matching the Python reference output",
        )
        assertEquals(
            "BLUE LINE TO SHREWSBURY LANSDOWNE I-44",
            index.headsign(index.tripHeadsign(rows[0].tripIdx)),
            "first departure's headsign",
        )
    }

    @Test
    fun limitTruncatesTheWalk() {
        val stop = index.resolveStop(10624)!!
        val rows = index.departures(stop, 11 * 60 + 50, weekday, limit = 3)
        assertEquals(listOf(710, 712, 720), rows.map { it.minute }, "limit=3 keeps the first three")
    }

    @Test
    fun serviceFilterExcludesInactiveTrips() {
        val stop = index.resolveStop(10624)!!
        val busOnly = index.departures(stop, 11 * 60 + 50, services = setOf(2), limit = 8)
        assertEquals(emptyList(), busOnly.map { it.minute }, "10624 is a rail station; bus-only service set sees nothing")
    }

    @Test
    fun tripStopsWalksToTheTerminus() {
        val stop = index.resolveStop(10624)!!
        val first = index.departures(stop, 11 * 60 + 50, weekday, limit = 1).single()
        val remaining = index.tripStops(first.tripIdx, fromSeq = first.seq)
        assertEquals(stop, remaining.first().stopIdx, "walk starts at the queried stop")
        assertEquals(710, remaining.first().minute, "at the queried departure minute")
        assertTrue(remaining.zipWithNext().all { (a, b) -> a.seq < b.seq }, "sequence strictly ascends")
        assertTrue(
            "SHREWSBURY" in index.stopName(remaining.last().stopIdx),
            "the Blue Line trip ends at Shrewsbury (headsign: TO SHREWSBURY LANSDOWNE I-44); got '${index.stopName(remaining.last().stopIdx)}'",
        )
    }

    @Test
    fun tripIndexOfJoinsRealtimeIdsToFileOrderTrips() {
        val stop = index.resolveStop(10624)!!
        val first = index.departures(stop, 11 * 60 + 50, weekday, limit = 1).single()
        val rtId = index.tripId(first.tripIdx)
        assertEquals(first.tripIdx, index.tripIndexOf(rtId), "binary search on sorted ids returns the file-order index")
        assertNull(index.tripIndexOf(1), "unknown realtime trip id joins to nothing")
    }

    @Test
    fun writerRefusesUnsortedTripIds() {
        // The RT join equates sorted-array position with file-order tripIdx, which
        // only holds because trips.txt arrives numerically sorted. A feed that
        // stops doing that must refuse, not silently mis-join every prediction.
        val files = mapOf(
            "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n100,100,A,38.6,-90.2,1\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nR1,1,Main\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign\nR1,S1,20,0,OUT\nR1,S1,10,0,BACK\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n20,08:00:00,08:00:00,100,1\n10,09:00:00,09:00:00,100,1\n",
            "calendar.txt" to "service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nS1,20260730,20260830,1,1,1,1,1,0,0\n",
            "calendar_dates.txt" to "service_id,exception_type,date\n",
        )
        val feed = GtfsFeed.load { name -> StringReader(files.getValue(name)) }
        val e = assertFailsWith<IllegalStateException> { IndexWriter.build(feed) }
        assertTrue("sorted" in e.message!!, "refusal names the sortedness invariant: ${e.message}")
    }
}
