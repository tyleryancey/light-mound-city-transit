package moundcity.transit.core.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals

/** Route-viewer plan Task 3: representative selection + Douglas-Peucker. */
class ShapeSelectTest {

    private fun p(lat: Double, lon: Double) = ShapePoint(lat, lon)

    @Test
    fun straightLineCollapsesToEndpoints() {
        val line = (0..10).map { p(38.0 + it * 0.001, -90.0) }
        assertEquals(
            listOf(line.first(), line.last()),
            ShapeSelect.douglasPeucker(line, ShapeSelect.TOLERANCE_DEG),
            "collinear points reduce to the two endpoints",
        )
    }

    @Test
    fun rightAngleKeepsTheCorner() {
        val corner = p(38.010, -90.0)
        val path = (0..10).map { p(38.0 + it * 0.001, -90.0) } +
            (1..10).map { p(38.010, -90.0 + it * 0.001) }
        val out = ShapeSelect.douglasPeucker(path, ShapeSelect.TOLERANCE_DEG)
        assertEquals(listOf(path.first(), corner, path.last()), out, "the corner survives decimation")
    }

    @Test
    fun pointInsideToleranceIsDropped() {
        // 5e-5 deg ≈ 5.5 m lateral deviation, under the 10 m tolerance
        val path = listOf(p(38.0, -90.0), p(38.0005, -90.0 + 5e-5), p(38.001, -90.0))
        assertEquals(
            listOf(path.first(), path.last()),
            ShapeSelect.douglasPeucker(path, ShapeSelect.TOLERANCE_DEG),
            "a 5.5 m wiggle is under the 10 m tolerance",
        )
    }

    @Test
    fun mostUsedShapeWinsAndTiesBreakToSmallestId() {
        val files = mapOf(
            "stops.txt" to "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n100,100,A,38.6,-90.2,1\n",
            "routes.txt" to "route_id,route_short_name,route_long_name\nR1,1,Main\n",
            "trips.txt" to "route_id,service_id,trip_id,direction_id,trip_headsign,shape_id\n" +
                "R1,S1,10,0,OUT,SH2\nR1,S1,11,0,OUT,SH2\nR1,S1,12,0,OUT,SH1\n" +
                "R1,S1,13,1,BACK,SH4\nR1,S1,14,1,BACK,SH3\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "10,08:00:00,08:00:00,100,1\n11,08:10:00,08:10:00,100,1\n12,08:20:00,08:20:00,100,1\n" +
                "13,09:00:00,09:00:00,100,1\n14,09:10:00,09:10:00,100,1\n",
            "calendar.txt" to "service_id,start_date,end_date,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nS1,20260730,20260830,1,1,1,1,1,0,0\n",
            "calendar_dates.txt" to "service_id,exception_type,date\n",
            "shapes.txt" to "shape_id,shape_pt_sequence,shape_pt_lat,shape_pt_lon\n" +
                "SH1,1,38.0,-90.0\nSH1,2,38.1,-90.0\nSH2,1,38.0,-90.0\nSH2,2,38.2,-90.0\n" +
                "SH3,1,38.0,-90.0\nSH3,2,38.3,-90.0\nSH4,1,38.0,-90.0\nSH4,2,38.4,-90.0\n",
        )
        val feed = GtfsFeed.load { name -> java.io.StringReader(files.getValue(name)) }
        val reps = ShapeSelect.representatives(feed, mapOf("R1" to 0))
        assertEquals(2, reps.size, "one representative per route+direction pair")
        assertEquals(38.2, reps[0].points.last().lat, "dir 0 picks SH2, the most-used")
        assertEquals(38.3, reps[1].points.last().lat, "dir 1 tie breaks to SH3, the smaller id")
    }
}
