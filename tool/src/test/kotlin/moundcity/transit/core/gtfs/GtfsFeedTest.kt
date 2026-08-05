package moundcity.transit.core.gtfs

import java.io.Reader
import java.io.StringReader
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Build plan 1.6: assertions A1, A2, A6, A7, A13, A14 from doc 01 §9 run during
 * the load and refuse the build (throw) rather than warn. Every failure message
 * quotes the OBSERVED value (CLAUDE.md correction 4's discipline). A13 uses the
 * oracle's max_time_bounded threshold: strictly below 28:00.
 */
class GtfsFeedTest {

    // A minimal, internally consistent feed; each violation case mutates one file.
    private val base = mapOf(
        "stops.txt" to """
            stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding
            100,100,MAIN AT FIRST,38.6,-90.2,1
            200,200,MAIN AT SECOND,38.7,-90.3,2
        """.trimIndent() + "\n",
        "routes.txt" to """
            route_id,route_short_name,route_long_name
            R1,1,Main Street
        """.trimIndent() + "\n",
        "trips.txt" to """
            route_id,service_id,trip_id,direction_id,trip_headsign,shape_id
            R1,S1,T1,0,DOWNTOWN,SH1
        """.trimIndent() + "\n",
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            T1,08:00:00,08:00:00,100,1
            T1,08:05:00,08:05:00,200,2
        """.trimIndent() + "\n",
        "calendar.txt" to """
            service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday
            S1,20260730,20260830,1,1,1,1,1,0,0
        """.trimIndent() + "\n",
        "calendar_dates.txt" to """
            service_id,exception_type,date
        """.trimIndent() + "\n",
        "shapes.txt" to """
            shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon
            SH1,1,38.60,-90.20
            SH1,2,38.65,-90.25
        """.trimIndent() + "\n",
    )

    private fun load(overrides: Map<String, String> = emptyMap()): GtfsFeed {
        val files = base + overrides
        return GtfsFeed.load { name: String ->
            StringReader(files[name] ?: throw IllegalArgumentException("no fixture entry $name")) as Reader
        }
    }

    @Test
    fun minimalFeedLoadsWithExpectedShape() {
        val feed = load()
        assertEquals(2, feed.stops.size, "two stops")
        assertEquals(1, feed.trips.size, "one trip")
        assertEquals(2, feed.stopTimeCount, "two stop_time rows")
        assertEquals(485, feed.maxMinute, "08:05 = 485 minutes")
        assertEquals(listOf("S1"), feed.serviceIds, "services sorted lexicographically")
    }

    @Test
    fun a1StopCodeMustEqualStopId() {
        val bad = base.getValue("stops.txt").replace("200,200,", "200,999,")
        val e = assertFailsWith<IllegalStateException> { load(mapOf("stops.txt" to bad)) }
        assertTrue("A1" in e.message!! && "1 of 2" in e.message!!, "message names A1 with observed count: ${e.message}")
    }

    @Test
    fun a2StopCodeMustBeNumericAndUnique() {
        // id and code must match (A1 checks first), so both go non-numeric together
        val nonNumeric = base.getValue("stops.txt").replace("200,200,", "20A,20A,")
        val e1 = assertFailsWith<IllegalStateException> { load(mapOf("stops.txt" to nonNumeric)) }
        assertTrue("A2" in e1.message!!, "non-numeric stop_code names A2: ${e1.message}")

        val dup = base.getValue("stops.txt").replace("200,200,", "200,100,")
        val e2 = assertFailsWith<IllegalStateException> { load(mapOf("stops.txt" to dup)) }
        assertTrue("A2" in e2.message!! || "A1" in e2.message!!, "duplicate code fails: ${e2.message}")
    }

    @Test
    fun a6ArrivalMustEqualDeparture() {
        val bad = base.getValue("stop_times.txt").replace("T1,08:05:00,08:05:00", "T1,08:04:00,08:05:00")
        val e = assertFailsWith<IllegalStateException> { load(mapOf("stop_times.txt" to bad)) }
        assertTrue("A6" in e.message!! && "1 of 2" in e.message!!, "message names A6 with observed count: ${e.message}")
    }

    @Test
    fun a7SecondsMustBeZero() {
        val bad = base.getValue("stop_times.txt").replace("T1,08:05:00,08:05:00", "T1,08:05:30,08:05:30")
        val e = assertFailsWith<IllegalStateException> { load(mapOf("stop_times.txt" to bad)) }
        assertTrue("A7" in e.message!! && ":30" in e.message!!, "message names A7 with the observed value: ${e.message}")
    }

    @Test
    fun a13TimeOfServiceDayMustStayBelow28Hours() {
        val bad = base.getValue("stop_times.txt").replace("T1,08:05:00,08:05:00", "T1,28:00:00,28:00:00")
        val e = assertFailsWith<IllegalStateException> { load(mapOf("stop_times.txt" to bad)) }
        assertTrue("A13" in e.message!! && "1680" in e.message!!, "message names A13 with observed minutes: ${e.message}")
    }

    @Test
    fun a14MoreStopsThanU16Refuses() {
        val sb = StringBuilder("stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n")
        repeat(65536) { i -> sb.append("$i,$i,STOP $i,38.6,-90.2,1\n") }
        val e = assertFailsWith<IllegalStateException> { load(mapOf("stops.txt" to sb.toString())) }
        assertTrue("A14" in e.message!! && "65536" in e.message!!, "message names A14 with the observed count: ${e.message}")
    }

    @Test
    fun shapesParseSortedBySequence() {
        val feed = load()
        assertEquals(
            listOf(ShapePoint(38.60, -90.20), ShapePoint(38.65, -90.25)),
            feed.shapes["SH1"],
            "SH1 points in sequence order",
        )
        assertEquals("SH1", feed.trips.single().shapeId, "trip carries its shape_id")
    }

    @Test
    fun a15EveryRouteDirectionPairNeedsAShapedTrip() {
        val bad = base.getValue("trips.txt").replace(",SH1", ",")
        val e = assertFailsWith<IllegalStateException> { load(mapOf("trips.txt" to bad)) }
        assertTrue("A15" in e.message!! && "R1" in e.message!!, "refusal names A15 and the route: ${e.message}")
    }

    @Test
    fun a16DanglingShapeRefIsANamedRefusal() {
        // Review finding: a trip referencing a shape absent from shapes.txt
        // detonated later as a bare NoSuchElementException in the writer.
        val bad = base.getValue("trips.txt").replace(",SH1", ",SH9")
        val e = assertFailsWith<IllegalStateException> { load(mapOf("trips.txt" to bad)) }
        assertTrue("A16" in e.message!! && "SH9" in e.message!!, "refusal names A16 and the offending id: ${e.message}")
    }

    @Test
    fun a17DuplicateShapeSequenceRefuses() {
        // Review finding: duplicate (shape_id, seq) — invalid GTFS — would sort
        // differently in the two writers (Kotlin stable-by-seq vs Python tuple
        // sort) and silently diverge the bytes. Refuse it by name instead.
        val bad = "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\n" +
            "SH1,1,38.60,-90.20\nSH1,1,38.61,-90.21\nSH1,2,38.65,-90.25\n"
        val e = assertFailsWith<IllegalStateException> { load(mapOf("shapes.txt" to bad)) }
        assertTrue("A17" in e.message!! && "SH1" in e.message!!, "refusal names A17 and the shape: ${e.message}")
    }

    @Test
    fun realFixtureShapesMatchTheMeasuredProfile() {
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        assertEquals(236, feed.shapes.size, "236 shape_ids")
        assertEquals(104566, feed.shapes.values.sumOf { it.size }, "104,566 shape points")
        assertEquals(0, feed.trips.count { it.shapeId.isEmpty() }, "every trip is shaped")
    }

    @Test
    fun realFixtureLoadsCleanWithTheMeasuredShape() {
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name: String -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        assertEquals(5118, feed.stops.size, "A1/A2 hold on all 5,118 stops")
        assertEquals(62, feed.routes.size, "62 routes")
        assertEquals(9577, feed.trips.size, "9,577 trips (A14 headroom)")
        assertEquals(489011, feed.stopTimeCount, "A6/A7 hold on all 489,011 rows")
        assertEquals(1537, feed.maxMinute, "observed max time-of-service-day is 25:37 = 1,537 min (A13)")
        assertEquals(8, feed.serviceIds.size, "8 service ids incl. 319-T2 from trips")
        assertEquals(java.time.LocalDate.of(2026, 8, 30), feed.expiry, "expiry from 1.10 rule")
    }
}
