package moundcity.transit.core.query

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build plan 1.18 + 1.19 + 1.21. The capture instant is 2026-08-03 11:49:12 CDT
 * = 16:49:12Z; goldens were probed from the fixtures via the Python oracle.
 */
class DepartureBoardTest {

    private val chicago = ZoneId.of("America/Chicago")
    private val captureInstant = Instant.parse("2026-08-03T16:49:12Z")
    private val index get() = QueryTestData.index

    @Test
    fun goldenRailBoardIsEntirelyScheduledEvenWithRealtimeSupplied() {
        val rows = DepartureBoard.at(
            index, captureInstant, chicago, index.resolveStop(10624)!!, limit = 8,
            rt = QueryTestData.rtTrips, vehicles = QueryTestData.rtVehicles,
        )
        assertEquals(listOf(710, 712, 720, 722, 730, 732, 740, 742), rows.map { it.minute }, "the doc-04 golden board minutes")
        assertTrue(rows.all { it.status == RowStatus.Scheduled }, "zero rail realtime: every train row says scheduled (1.21)")
        assertTrue(rows.all { it.vehicle == null }, "no vehicle ever attaches to a rail row")
        assertTrue(rows.all { it.serviceDate == LocalDate.of(2026, 8, 3) }, "all from the capture's service date")
    }

    @Test
    fun goldenLiveBusBoardAtStop7855() {
        val rows = DepartureBoard.at(
            index, captureInstant, chicago, index.resolveStop(7855)!!, limit = 8,
            rt = QueryTestData.rtTrips, vehicles = QueryTestData.rtVehicles,
        )
        assertEquals(listOf(718, 722, 722, 723, 723, 726, 727, 729), rows.map { it.minute }, "probed golden minutes")
        assertEquals(
            listOf(0, 420, 480, 180, 540, 60, 120, 60),
            rows.map { (it.status as RowStatus.Live).delaySeconds },
            "all eight are live; trip 3405075's absent delay reads as on time (A5), never as scheduled",
        )
        assertTrue(
            rows.all { it.vehicle != null && it.vehicle!!.tripId == index.tripId(it.tripIdx).toString() },
            "every golden row carries its own trip's vehicle fix",
        )
    }

    @Test
    fun canceledTripIsShownStruckNeverRemoved() {
        val rows = DepartureBoard.at(
            index, captureInstant, chicago, index.resolveStop(7653)!!, limit = 8,
            rt = QueryTestData.rtTrips, vehicles = QueryTestData.rtVehicles,
        )
        val canceled = rows.first { it.minute == 710 && index.tripId(it.tripIdx) == 3404706 }
        assertEquals(RowStatus.Canceled, canceled.status, "doc 02: canceled trips are shown and struck, not silently removed")
    }

    @Test
    fun midnightQueryUnionsYesterdaysLateTripsWithTodaysFirst() {
        // 2026-08-04 01:20 CDT = 06:20Z. Yesterday's (08-03) service-day minute
        // 1520 catches its last late trip (25:33); today's minute 80 catches the
        // 04:26/04:41/04:51 openers. Unsliced probe of stop 14330's tail:
        // 1533/3410765 is the final >24:00 row.
        val rows = DepartureBoard.at(
            index, Instant.parse("2026-08-04T06:20:00Z"), chicago, index.resolveStop(14330)!!, limit = 4,
        )
        assertEquals(
            listOf(1533, 266, 281, 291),
            rows.map { it.minute },
            "yesterday's 25:33 sorts before today's 04:26, 04:41, 04:51",
        )
        assertEquals(
            listOf("2026-08-03", "2026-08-04", "2026-08-04", "2026-08-04"),
            rows.map { it.serviceDate.toString() },
            "service-date attribution across the midnight boundary",
        )
        assertTrue(rows.zipWithNext().all { (a, b) -> !a.departure.isAfter(b.departure) }, "absolute instants sort the union")
        assertEquals("2026-08-04T06:33:00Z", rows[0].departure.toString(), "25:33 on 08-03 resolves to 01:33 CDT on 08-04")
    }

    @Test
    fun justAfterMidnightTheWholeBoardIsYesterdaysService() {
        // 00:05 CDT: yesterday's tail alone fills the board — 1446 through 1471 —
        // because 04:26 is hours away. The original probe truncated this list and
        // the implementation was right; the test expectation was the bug.
        val rows = DepartureBoard.at(
            index, Instant.parse("2026-08-04T05:05:00Z"), chicago, index.resolveStop(14330)!!, limit = 8,
        )
        assertEquals(
            listOf(1446, 1451, 1453, 1456, 1456, 1468, 1470, 1471),
            rows.map { it.minute },
            "eight >24:00 rows, all from service date 08-03",
        )
        assertTrue(rows.all { it.serviceDate == LocalDate.of(2026, 8, 3) }, "no 08-04 row belongs on this board yet")
    }

    @Test
    fun fallBackEarlyMorningStillShowsTodaysTrips() {
        // Review finding (Phase 1d): on 2026-11-01 the service day starts 01:00
        // CDT, so a query at 00:30 CDT gives NEGATIVE elapsed for `today` — the
        // old guard skipped today's leg entirely, hiding its early trips. The
        // fixture's calendar ends in August, so this needs a synthetic one.
        val files = mapOf(
            "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n100,100,A,38.6,-90.2,1\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nR1,70,Grand\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign,shape_id\nR1,S1,10,0,OUT,SH1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n10,02:00:00,02:00:00,100,1\n",
            "calendar.txt" to "service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nS1,20261001,20261130,1,1,1,1,1,1,1\n",
            "calendar_dates.txt" to "service_id,exception_type,date\n",
            "shapes.txt" to "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\nSH1,1,38.6,-90.2\nSH1,2,38.7,-90.2\n",
        )
        val feed = moundcity.transit.core.gtfs.GtfsFeed.load { name -> java.io.StringReader(files.getValue(name)) }
        val idx = moundcity.transit.core.gtfs.ScheduleIndex(moundcity.transit.core.gtfs.IndexWriter.build(feed).container())
        // 2026-11-01 00:30 CDT = 05:30Z; today's 02:00 departure exists (at 08:00Z)
        val rows = DepartureBoard.at(idx, Instant.parse("2026-11-01T05:30:00Z"), chicago, idx.resolveStop(100)!!, limit = 4)
        assertEquals(
            listOf(120),
            rows.filter { it.serviceDate == LocalDate.of(2026, 11, 1) }.map { it.minute },
            "today's 02:00 trip must survive the negative-elapsed window before the DST-shifted service start",
        )
    }

    @Test
    fun scheduledSaysWhichKindWhenLiveDataExists() {
        // D13: with a live snapshot in hand, a bus row that is merely
        // "scheduled" has two very different meanings. Two trips, one already
        // out (first stop 09:40) and one not (first stop 10:20), queried at
        // 09:50 — the feed names neither, which is the honest ~9% case.
        val files = mapOf(
            "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n" +
                "100,100,ORIGIN,38.6,-90.2,1\n200,200,LATER,38.65,-90.2,1\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nR1,70,Grand\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign,shape_id\n" +
                "R1,S1,10,0,OUT,SH1\nR1,S1,20,0,OUT,SH1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "10,09:40:00,09:40:00,100,1\n10,10:00:00,10:00:00,200,2\n" +
                "20,10:20:00,10:20:00,100,1\n20,10:30:00,10:30:00,200,2\n",
            "calendar.txt" to "service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday\n" +
                "S1,20260901,20260930,1,1,1,1,1,1,1\n",
            "calendar_dates.txt" to "service_id,exception_type,date\n",
            "shapes.txt" to "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\nSH1,1,38.6,-90.2\nSH1,2,38.65,-90.2\n",
        )
        val feed = moundcity.transit.core.gtfs.GtfsFeed.load { name -> java.io.StringReader(files.getValue(name)) }
        val idx = moundcity.transit.core.gtfs.ScheduleIndex(moundcity.transit.core.gtfs.IndexWriter.build(feed).container())
        val rt = moundcity.transit.core.rt.RtTrips(headerTimestamp = 1L, entities = emptyList())
        // 2026-09-16 09:50 CDT = 14:50Z
        val rows = DepartureBoard.at(
            idx, Instant.parse("2026-09-16T14:50:00Z"), chicago, idx.resolveStop(200)!!, limit = 4, rt = rt,
        )
        assertEquals(listOf(600, 630), rows.map { it.minute }, "both later-stop departures are still ahead")
        assertEquals(RowStatus.ScheduledNoData, rows[0].status, "already out and absent from the feed = no live data")
        assertEquals(RowStatus.ScheduledNotStarted, rows[1].status, "first stop still ahead = not started yet")

        val noSnapshot = DepartureBoard.at(
            idx, Instant.parse("2026-09-16T14:50:00Z"), chicago, idx.resolveStop(200)!!, limit = 4,
        )
        assertTrue(
            noSnapshot.all { it.status is RowStatus.Scheduled },
            "without a snapshot there is nothing to distinguish — plain scheduled",
        )
    }

    @Test
    fun tripFirstMinuteIsTheTripsOwnStart() {
        val trip = index.tripIndexOf(3405037)!!
        assertEquals(
            index.tripStops(trip, fromSeq = 0).first().minute,
            index.tripFirstMinute(trip),
            "the cached first minute equals the trip's first stop time",
        )
    }

    @Test
    fun fromMinuteCeilsPartialMinutes() {
        // 11:49:12 must exclude a 11:49:00 departure (it left 12 s ago) — the
        // golden rail board's first row is 11:50, not 11:49.
        val rows = DepartureBoard.at(index, captureInstant, chicago, index.resolveStop(10624)!!, limit = 1)
        assertEquals(710, rows.single().minute, "first departure at or after ceil(11:49:12) = 11:50")
    }
}
