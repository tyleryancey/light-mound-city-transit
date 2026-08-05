package moundcity.transit.core.gtfs

import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 1d additions to the container (v2): route_ids, service_ids, calendar,
 * calendar_dates. Discovered while building the join — the on-device board
 * cannot compute active services without calendar data, cannot join alerts or
 * detect Illinois routes without route_ids, and doc 03 §3's section table
 * simply omitted both (the off-device oracle re-reads the feed instead).
 */
class IndexV2SectionsTest {

    private val index: ScheduleIndex by lazy {
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        ScheduleIndex(IndexWriter.build(feed).container())
    }

    @Test
    fun routeIdsRoundTripInFileOrder() {
        assertEquals("19731B", index.routeId(index.routeIndexOf("19731B")!!), "rail ids with letter suffixes survive")
        assertEquals("19855", index.routeId(index.routeIndexOf("19855")!!), "the first Illinois route")
        assertEquals(null, index.routeIndexOf("99999"), "unknown route id resolves to nothing")
    }

    @Test
    fun serviceIdsAreTheSortedEight() {
        assertEquals(8, index.serviceCount, "eight service ids")
        assertEquals("319-T1", index.serviceId(0), "sorted lexicographically, matching trip_meta's serviceIdx")
        assertEquals("319-T2", index.serviceId(1), "the calendar_dates-only service is present")
        assertEquals("325-B1", index.serviceId(2), "weekday bus")
    }

    @Test
    fun writerRefusesExceptionTypePastU8() {
        // Review finding (Phase 1d): the Python mirror's struct.pack('<B')
        // raises on >255 where toByte() truncated silently. Refuse in lockstep.
        val files = mapOf(
            "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n100,100,A,38.6,-90.2,1\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nR1,70,Grand\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign,shape_id\nR1,S1,10,0,OUT,SH1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n10,08:00:00,08:00:00,100,1\n",
            "calendar.txt" to "service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nS1,20260730,20260830,1,1,1,1,1,0,0\n",
            "calendar_dates.txt" to "service_id,exception_type,date\nS1,300,20260808\n",
            "shapes.txt" to "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\nSH1,1,38.6,-90.2\nSH1,2,38.7,-90.2\n",
        )
        val feed = GtfsFeed.load { name -> java.io.StringReader(files.getValue(name)) }
        val e = kotlin.test.assertFailsWith<IllegalStateException> { IndexWriter.build(feed) }
        kotlin.test.assertTrue("300" in e.message!!, "refusal quotes the observed exception_type: ${e.message}")
    }

    @Test
    fun activeServiceIdxsFromTheIndexMatchTheOracle() {
        assertEquals(
            setOf(0, 2),
            index.activeServiceIdxs(LocalDate.of(2026, 8, 3)),
            "Monday 2026-08-03: 319-T1 (0) + 325-B1 (2), the golden board's service set",
        )
        assertEquals(
            setOf(1, 3),
            index.activeServiceIdxs(LocalDate.of(2026, 8, 8)),
            "Saturday 2026-08-08: 319-T2 added by exception (idx 1), 325-B2 (idx 3); 325-T2 removed — the stl_gtfs_calendar oracle's exact answer",
        )
    }
}
