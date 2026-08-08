package moundcity.transit.core.gtfs

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DepartureRow(val minute: Int, val tripIdx: Int, val seq: Int)

data class StopTimeRow(val stopIdx: Int, val minute: Int, val seq: Int)

/**
 * Reader over the MCT1 container (doc 03 §3). Stops are addressed by their
 * position in numeric-id order; trips by file position, which the writer
 * guarantees equals sorted-id position — that equality is what makes
 * [tripIndexOf]'s binary search return a usable trip_meta index.
 */
class ScheduleIndex(container: ByteArray) {

    private val sections = IndexContainer.parse(container)

    private fun buf(name: String): ByteBuffer =
        ByteBuffer.wrap(sections.getValue(name)).order(ByteOrder.LITTLE_ENDIAN)

    private val departures = buf("departures")
    private val stopOffsets = buf("stop_offsets")
    private val tripMeta = buf("trip_meta")
    private val tripIdSorted = buf("trip_id_sorted")
    private val stopCodes = buf("stop_codes")
    private val stopGeo = buf("stop_geo")
    private val wheelchair = sections.getValue("wheelchair")

    val stopCount: Int = wheelchair.size
    val tripCount: Int = sections.getValue("trip_meta").size / 5

    private val stopNames = StringTable(sections.getValue("stop_names"), stopCount)
    private val headsigns = StringTable(sections.getValue("headsigns"))
    private val routeNames = StringTable(sections.getValue("route_names"))

    val routeCount: Int get() = routeNames.size

    // --- stop lookup ---

    fun resolveStop(code: Int): Int? {
        var lo = 0
        var hi = stopCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (stopCodes.getInt(mid * 4) < code) lo = mid + 1 else hi = mid
        }
        return if (lo < stopCount && stopCodes.getInt(lo * 4) == code) lo else null
    }

    fun stopCode(stopIdx: Int): Int = stopCodes.getInt(stopIdx * 4)
    fun stopName(stopIdx: Int): String = stopNames[stopIdx]
    fun stopLatMicro(stopIdx: Int): Int = stopGeo.getInt(stopIdx * 8)
    fun stopLonMicro(stopIdx: Int): Int = stopGeo.getInt(stopIdx * 8 + 4)
    fun wheelchair(stopIdx: Int): Int = wheelchair[stopIdx].toInt() and 0xFF

    // --- departures ---

    fun departures(stop: Int, fromMinute: Int, services: Set<Int>, limit: Int): List<DepartureRow> {
        val start = stopOffsets.getInt(stop * 4)
        val end = stopOffsets.getInt((stop + 1) * 4)
        val n = (end - start) / 6
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (minuteAt(start, mid) < fromMinute) lo = mid + 1 else hi = mid
        }
        // capacity from the slice, never the caller's limit — a generous
        // limit must not drive allocation (found by the multimodal sweep)
        val out = ArrayList<DepartureRow>(minOf(limit, n - lo))
        var j = lo
        while (j < n && out.size < limit) {
            val row = rowAt(start, j)
            if (tripService(row.tripIdx) in services) out.add(row)
            j++
        }
        return out
    }

    private fun minuteAt(sliceStart: Int, j: Int): Int =
        departures.getShort(sliceStart + j * 6).toInt() and 0xFFFF

    private fun rowAt(sliceStart: Int, j: Int): DepartureRow {
        val base = sliceStart + j * 6
        return DepartureRow(
            minute = departures.getShort(base).toInt() and 0xFFFF,
            tripIdx = departures.getShort(base + 2).toInt() and 0xFFFF,
            seq = departures.getShort(base + 4).toInt() and 0xFFFF,
        )
    }

    /** Remaining stops of a trip from a sequence number, in stop order. */
    fun tripStops(trip: Int, fromSeq: Int): List<StopTimeRow> {
        val out = mutableListOf<StopTimeRow>()
        for (stop in 0 until stopCount) {
            val start = stopOffsets.getInt(stop * 4)
            val end = stopOffsets.getInt((stop + 1) * 4)
            var p = start
            while (p < end) {
                val t = departures.getShort(p + 2).toInt() and 0xFFFF
                if (t == trip) {
                    val seq = departures.getShort(p + 4).toInt() and 0xFFFF
                    if (seq >= fromSeq) {
                        out.add(StopTimeRow(stop, departures.getShort(p).toInt() and 0xFFFF, seq))
                    }
                }
                p += 6
            }
        }
        out.sortBy { it.seq }
        return out
    }

    // --- trips ---

    fun tripIndexOf(rtTripId: Int): Int? {
        var lo = 0
        var hi = tripCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tripIdSorted.getInt(mid * 4) < rtTripId) lo = mid + 1 else hi = mid
        }
        return if (lo < tripCount && tripIdSorted.getInt(lo * 4) == rtTripId) lo else null
    }

    fun tripId(tripIdx: Int): Int = tripIdSorted.getInt(tripIdx * 4)
    fun tripRoute(tripIdx: Int): Int = tripMeta.get(tripIdx * 5).toInt() and 0xFF
    fun tripService(tripIdx: Int): Int = tripMeta.get(tripIdx * 5 + 1).toInt() and 0xFF
    fun tripDirection(tripIdx: Int): Int = tripMeta.get(tripIdx * 5 + 2).toInt() and 0xFF
    fun tripHeadsign(tripIdx: Int): Int = tripMeta.getShort(tripIdx * 5 + 3).toInt() and 0xFFFF

    /**
     * Each trip's first departure minute — one lazy pass over the departures
     * section, cached for the process. D13 uses it to tell "hasn't left yet"
     * apart from "should be running but the feed is silent".
     */
    private val firstMinutes: IntArray by lazy {
        val out = IntArray(tripCount) { Int.MAX_VALUE }
        for (stop in 0 until stopCount) {
            val start = stopOffsets.getInt(stop * 4)
            val end = stopOffsets.getInt((stop + 1) * 4)
            var p = start
            while (p < end) {
                val minute = departures.getShort(p).toInt() and 0xFFFF
                val t = departures.getShort(p + 2).toInt() and 0xFFFF
                if (minute < out[t]) out[t] = minute
                p += 6
            }
        }
        out
    }

    fun tripFirstMinute(tripIdx: Int): Int = firstMinutes[tripIdx]

    // --- v2: route ids, services, calendar ---

    private val routeIds = StringTable(sections.getValue("route_ids"))
    private val serviceIds = StringTable(sections.getValue("service_ids"))
    private val calendarBuf = buf("calendar")
    private val calendarDatesBuf = buf("calendar_dates")

    val serviceCount: Int get() = serviceIds.size

    /** Every service index — the "any service" query set callers keep building. */
    fun allServiceIdxs(): Set<Int> = (0 until serviceCount).toSet()

    fun routeId(routeIdx: Int): String = routeIds[routeIdx]

    fun routeIndexOf(routeId: String): Int? {
        for (i in 0 until routeIds.size) if (routeIds[i] == routeId) return i
        return null
    }

    fun serviceId(serviceIdx: Int): String = serviceIds[serviceIdx]

    /** Active service indexes for a date, computed entirely from the index. */
    fun activeServiceIdxs(date: java.time.LocalDate): Set<Int> {
        val calendar = (0 until serviceCount).mapNotNull { i ->
            val start = calendarBuf.getInt(i * 9)
            val end = calendarBuf.getInt(i * 9 + 4)
            val bits = calendarBuf.get(i * 9 + 8).toInt() and 0xFF
            if (start == 0) null else moundcity.transit.core.time.CalendarRow(
                serviceId = serviceId(i),
                startDate = intDate(start),
                endDate = intDate(end),
                activeDays = java.time.DayOfWeek.entries.filter { bits and (1 shl (it.value - 1)) != 0 }.toSet(),
            )
        }
        val exceptions = (0 until sections.getValue("calendar_dates").size / 6).map { j ->
            moundcity.transit.core.time.CalendarDateRow(
                serviceId = serviceId(calendarDatesBuf.get(j * 6).toInt() and 0xFF),
                exceptionType = calendarDatesBuf.get(j * 6 + 1).toInt() and 0xFF,
                date = intDate(calendarDatesBuf.getInt(j * 6 + 2)),
            )
        }
        val activeIds = moundcity.transit.core.time.ServiceCalendar.activeServices(date, calendar, exceptions)
        return (0 until serviceCount).filter { serviceId(it) in activeIds }.toSet()
    }

    /** Min calendar start date — the "Schedule YYYY-MM-DD" the footer names. */
    fun feedStartDate(): java.time.LocalDate =
        (0 until serviceCount).mapNotNull { i ->
            calendarBuf.getInt(i * 9).takeIf { it != 0 }?.let { intDate(it) }
        }.min()

    /** The 1.10 expiry rule, derived from the index's own sections. */
    fun expiryDate(): java.time.LocalDate = FeedExpiry.expiryDate(
        calendarEndDates = (0 until serviceCount).mapNotNull { i ->
            calendarBuf.getInt(i * 9 + 4).takeIf { it != 0 }?.let { intDate(it) }
        },
        calendarDateDates = (0 until sections.getValue("calendar_dates").size / 6).map { j ->
            intDate(calendarDatesBuf.getInt(j * 6 + 2))
        },
    )

    private fun intDate(v: Int): java.time.LocalDate =
        java.time.LocalDate.of(v / 10000, (v / 100) % 100, v % 100)

    // --- v3: route shapes (D12) ---

    private val shapeOffsets = buf("shape_offsets")
    private val shapePts = buf("shape_pts")

    /** Interleaved [latMicro0, lonMicro0, latMicro1, ...] or null when absent. */
    fun routeShape(routeIdx: Int, directionId: Int): IntArray? {
        val keys = sections.getValue("shape_keys")
        for (i in 0 until keys.size / 2) {
            if ((keys[i * 2].toInt() and 0xFF) == routeIdx && (keys[i * 2 + 1].toInt() and 0xFF) == directionId) {
                val start = shapeOffsets.getInt(i * 4)
                val end = shapeOffsets.getInt((i + 1) * 4)
                val out = IntArray((end - start) / 4)
                for (j in out.indices) out[j] = shapePts.getInt(start + j * 4)
                return out
            }
        }
        return null
    }

    // --- strings ---

    fun headsign(headsignIdx: Int): String = headsigns[headsignIdx]
    fun routeShortName(routeIdx: Int): String = routeNames[routeIdx].substringBefore('\u001F')
    fun routeLongName(routeIdx: Int): String = routeNames[routeIdx].substringAfter('\u001F')

    /**
     * (n+1) u32 offsets then a UTF-8 blob. The count is not stored; when the
     * caller cannot supply it, it is solved from the layout equation
     * total = 4·(n+1) + offsets[n], validated by monotonicity.
     */
    private class StringTable(private val bytes: ByteArray, knownCount: Int = -1) {
        private val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size: Int = if (knownCount >= 0) knownCount else solveCount()

        private fun solveCount(): Int {
            var k = 1
            while ((k + 1) * 4 <= bytes.size) {
                if ((k + 1) * 4 + b.getInt(k * 4) == bytes.size) return k
                k++
            }
            error("not a string table: no count satisfies the layout equation (${bytes.size} bytes)")
        }

        operator fun get(i: Int): String {
            val start = b.getInt(i * 4)
            val end = b.getInt((i + 1) * 4)
            val blobBase = (size + 1) * 4
            return String(bytes, blobBase + start, end - start, Charsets.UTF_8)
        }
    }
}
