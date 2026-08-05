package moundcity.transit.core.gtfs

import java.io.Reader
import java.time.DayOfWeek
import java.time.LocalDate
import moundcity.transit.core.time.CalendarDateRow
import moundcity.transit.core.time.CalendarRow

data class Stop(
    val id: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val wheelchair: Int,
)

data class Route(val id: String, val shortName: String, val longName: String)

data class Trip(
    val id: String,
    val routeId: String,
    val serviceId: String,
    val directionId: Int,
    val headsign: String,
    val shapeId: String,
)

data class ShapePoint(val lat: Double, val lon: Double)

/**
 * The parsed static feed, with the build-refusing assertions of build plan 1.6
 * (doc 01 §9: A1, A2, A6, A7, A13, A14) applied during the load. A violation
 * throws with the observed value in the message — an index built from a feed
 * that broke an encoding assumption would silently truncate, which is worse.
 *
 * Stop_times are held columnar: parallel arrays indexed 0..stopTimeCount-1,
 * trips by file position, stops by their numeric id.
 */
class GtfsFeed private constructor(
    val stops: List<Stop>,
    val routes: List<Route>,
    val trips: List<Trip>,
    val serviceIds: List<String>,
    val calendar: List<CalendarRow>,
    val calendarDates: List<CalendarDateRow>,
    val shapes: Map<String, List<ShapePoint>>,
    val expiry: LocalDate,
    val stMinute: IntArray,
    val stTripIdx: IntArray,
    val stStopId: IntArray,
    val stSeq: IntArray,
    val stopTimeCount: Int,
    val maxMinute: Int,
) {
    companion object {

        private val WEEKDAY_COLUMNS = listOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
        )

        fun load(openEntry: (String) -> Reader): GtfsFeed {
            val stops = mutableListOf<Stop>()
            openEntry("stops.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    stops.add(
                        Stop(
                            id = row["stop_id"],
                            code = row["stop_code"],
                            name = row["stop_name"],
                            lat = row["stop_lat"].toDouble(),
                            lon = row["stop_lon"].toDouble(),
                            wheelchair = row["wheelchair_boarding"].ifEmpty { "0" }.toInt(),
                        )
                    )
                }
            }
            assertStops(stops)

            val routes = mutableListOf<Route>()
            openEntry("routes.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    routes.add(Route(row["route_id"], row["route_short_name"], row["route_long_name"]))
                }
            }

            val trips = mutableListOf<Trip>()
            openEntry("trips.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    trips.add(
                        Trip(
                            id = row["trip_id"],
                            routeId = row["route_id"],
                            serviceId = row["service_id"],
                            directionId = row["direction_id"].toInt(),
                            headsign = row["trip_headsign"],
                            shapeId = row["shape_id"],
                        )
                    )
                }
            }
            check(stops.size <= 65535 && trips.size <= 65535) {
                "A14: u16 indices require <= 65535 stops and trips; observed ${stops.size} stops, ${trips.size} trips"
            }

            val tripIdxById = HashMap<String, Int>(trips.size * 2)
            trips.forEachIndexed { i, t -> tripIdxById[t.id] = i }

            var minute = IntArray(1 shl 16)
            var tripIdx = IntArray(1 shl 16)
            var stopId = IntArray(1 shl 16)
            var seq = IntArray(1 shl 16)
            var n = 0
            var maxMinute = 0
            var arrivalMismatches = 0
            var firstBadSeconds: String? = null
            var badSeconds = 0
            openEntry("stop_times.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    if (n == minute.size) {
                        minute = minute.copyOf(n * 2)
                        tripIdx = tripIdx.copyOf(n * 2)
                        stopId = stopId.copyOf(n * 2)
                        seq = seq.copyOf(n * 2)
                    }
                    val dep = row["departure_time"]
                    if (row["arrival_time"] != dep) arrivalMismatches++
                    val parts = dep.split(':')
                    check(parts.size == 3) { "stop_times departure_time '$dep' is not HH:MM:SS" }
                    if (parts[2] != "00") {
                        badSeconds++
                        if (firstBadSeconds == null) firstBadSeconds = dep
                    }
                    val m = parts[0].toInt() * 60 + parts[1].toInt()
                    if (m > maxMinute) maxMinute = m
                    minute[n] = m
                    tripIdx[n] = tripIdxById.getValue(row["trip_id"])
                    stopId[n] = row["stop_id"].toInt()
                    seq[n] = row["stop_sequence"].toInt()
                    n++
                }
            }
            val violations = mutableListOf<String>()
            if (arrivalMismatches > 0) {
                violations.add("A6: arrival_time != departure_time on $arrivalMismatches of $n rows")
            }
            if (badSeconds > 0) {
                violations.add("A7: non-zero seconds on $badSeconds of $n rows (first observed: $firstBadSeconds)")
            }
            if (maxMinute >= 1680) {
                violations.add("A13: max time-of-service-day is $maxMinute min, at or past the 1680 min (28:00) bound")
            }
            check(violations.isEmpty()) { violations.joinToString("; ") }

            val calendar = mutableListOf<CalendarRow>()
            openEntry("calendar.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    val days = WEEKDAY_COLUMNS.filter { (col, _) -> row[col] == "1" }.map { it.second }.toSet()
                    calendar.add(
                        CalendarRow(row["service_id"], GtfsDates.parse(row["start_date"]), GtfsDates.parse(row["end_date"]), days)
                    )
                }
            }
            val calendarDates = mutableListOf<CalendarDateRow>()
            openEntry("calendar_dates.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    calendarDates.add(
                        CalendarDateRow(row["service_id"], GtfsDates.parse(row["date"]), row["exception_type"].toInt())
                    )
                }
            }

            val shapeAccum = HashMap<String, MutableList<Pair<Int, ShapePoint>>>()
            openEntry("shapes.txt").use { r ->
                GtfsCsv.forEachRow(r) { row ->
                    shapeAccum.getOrPut(row["shape_id"]) { mutableListOf() }.add(
                        row["shape_pt_sequence"].toInt() to
                            ShapePoint(row["shape_pt_lat"].toDouble(), row["shape_pt_lon"].toDouble())
                    )
                }
            }
            for ((sid, pts) in shapeAccum) {
                val dupSeq = pts.groupingBy { it.first }.eachCount().filterValues { it > 1 }
                check(dupSeq.isEmpty()) {
                    "A17: duplicate shape_pt_sequence ${dupSeq.keys.first()} in shape $sid — refusing to build"
                }
            }
            val shapes = shapeAccum.mapValues { (_, v) -> v.sortedBy { it.first }.map { it.second } }
            val dangling = trips.asSequence()
                .map { it.shapeId }
                .filter { it.isNotEmpty() && it !in shapes }
                .distinct().sorted().toList()
            check(dangling.isEmpty()) {
                "A16: ${dangling.size} shape_id(s) referenced by trips but absent from shapes.txt " +
                    "(first: ${dangling.first()}) — refusing to build"
            }
            val unshaped = trips.groupBy { it.routeId to it.directionId }
                .filterValues { group -> group.none { it.shapeId.isNotEmpty() } }
            check(unshaped.isEmpty()) {
                "A15: ${unshaped.size} route+direction pair(s) with no shaped trip " +
                    "(first: ${unshaped.keys.first().first}) — the route viewer cannot draw them"
            }

            return GtfsFeed(
                stops = stops,
                routes = routes,
                trips = trips,
                serviceIds = trips.map { it.serviceId }.distinct().sorted(),
                calendar = calendar,
                calendarDates = calendarDates,
                shapes = shapes,
                expiry = FeedExpiry.expiryDate(calendar.map { it.endDate }, calendarDates.map { it.date }),
                stMinute = minute,
                stTripIdx = tripIdx,
                stStopId = stopId,
                stSeq = seq,
                stopTimeCount = n,
                maxMinute = maxMinute,
            )
        }

        private fun assertStops(stops: List<Stop>) {
            val codeNotId = stops.count { it.code != it.id }
            check(codeNotId == 0) { "A1: stop_code != stop_id on $codeNotId of ${stops.size} stops" }
            val nonNumeric = stops.count { s -> s.code.isEmpty() || s.code.any { !it.isDigit() } }
            check(nonNumeric == 0) { "A2: non-numeric stop_code on $nonNumeric of ${stops.size} stops" }
            val duplicates = stops.groupingBy { it.code }.eachCount().count { it.value > 1 }
            check(duplicates == 0) { "A2: $duplicates stop_code values shared by more than one stop" }
        }
    }
}
