package moundcity.transit.core.gtfs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the columnar index of doc 03 §3. Every encoding decision here mirrors
 * harness/build_index.py — the two must produce byte-identical sections from the
 * same feed (build plan 1.9), so any change lands in both in the same commit.
 *
 * Orderings that byte-identity depends on:
 *  - stops sorted by numeric id (also the stop_codes ordering)
 *  - trips and routes in file order; services sorted lexicographically
 *  - headsigns sorted lexicographically, deduplicated
 *  - each stop's departures sorted by (minute, tripIdx, seq)
 */
class BuiltIndex internal constructor(val sections: LinkedHashMap<String, ByteArray>) {

    /** Single-file container: "MCT1", u32 version, u32 count, u32 lengths, payloads. */
    fun container(): ByteArray {
        val header = ByteBuffer.allocate(12 + sections.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        header.put(IndexContainer.MAGIC)
        header.putInt(IndexContainer.VERSION)
        header.putInt(sections.size)
        for (bytes in sections.values) header.putInt(bytes.size)
        val out = ByteArrayOutputStream(header.capacity() + sections.values.sumOf { it.size })
        out.write(header.array())
        for (bytes in sections.values) out.write(bytes)
        return out.toByteArray()
    }
}

object IndexContainer {
    val MAGIC = byteArrayOf(0x4D, 0x43, 0x54, 0x31) // "MCT1"
    const val VERSION = 2

    /** Section names in the one true order; the container stores lengths only.
     * v2 (Phase 1d) appended route_ids/service_ids/calendar/calendar_dates —
     * the on-device board needs active-service computation and route-id joins
     * that doc 03 §3's original table omitted. */
    val SECTION_ORDER = listOf(
        "departures", "stop_offsets", "trip_meta", "trip_id_sorted", "stop_names",
        "headsigns", "route_names", "stop_codes", "stop_geo", "wheelchair",
        "route_ids", "service_ids", "calendar", "calendar_dates",
    )

    fun parse(container: ByteArray): LinkedHashMap<String, ByteArray> {
        val buf = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        check(magic.contentEquals(MAGIC)) { "not an index container (bad magic)" }
        check(buf.int == VERSION) { "unsupported index container version" }
        val count = buf.int
        check(count == SECTION_ORDER.size) { "expected ${SECTION_ORDER.size} sections, container has $count" }
        val lengths = IntArray(count) { buf.int }
        val out = LinkedHashMap<String, ByteArray>()
        for (i in 0 until count) {
            val bytes = ByteArray(lengths[i]).also { buf.get(it) }
            out[SECTION_ORDER[i]] = bytes
        }
        check(!buf.hasRemaining()) { "container has ${buf.remaining()} trailing bytes" }
        return out
    }
}

object IndexWriter {

    fun build(feed: GtfsFeed): BuiltIndex {
        val stopIdsSorted = feed.stops.map { it.id.toInt() }.sorted()
        val stopPos = HashMap<Int, Int>(stopIdsSorted.size * 2)
        stopIdsSorted.forEachIndexed { i, id -> stopPos[id] = i }
        val stopById = feed.stops.associateBy { it.id.toInt() }

        val serviceIdx = feed.serviceIds.withIndex().associate { (i, s) -> s to i }
        val routeIdx = feed.routes.withIndex().associate { (i, r) -> r.id to i }
        val headsigns = feed.trips.map { it.headsign }.distinct().sorted()
        val headsignIdx = headsigns.withIndex().associate { (i, h) -> h to i }
        check(feed.routes.size <= 255 && feed.serviceIds.size <= 255) {
            "trip_meta u8 indices need <=255 routes and services; observed ${feed.routes.size}/${feed.serviceIds.size}"
        }

        // departures: per-stop buckets of (minute, tripIdx, seq), tuple-sorted
        val buckets = Array(stopIdsSorted.size) { LongArrayList() }
        for (i in 0 until feed.stopTimeCount) {
            val pos = stopPos.getValue(feed.stStopId[i])
            // seq must fit u16 BEFORE packing: a masked overflow would silently
            // corrupt both the sort order and trip attribution, where the Python
            // mirror's struct.pack('<H') refuses. Minute is bounded by A13,
            // tripIdx by A14; stop_sequence has no assertion of its own.
            check(feed.stSeq[i] <= 0xFFFF) {
                "stop_sequence ${feed.stSeq[i]} exceeds u16 at stop_times row $i — refusing to build"
            }
            val packed = (feed.stMinute[i].toLong() shl 32) or
                (feed.stTripIdx[i].toLong() shl 16) or feed.stSeq[i].toLong()
            buckets[pos].add(packed)
        }
        val depBuf = ByteBuffer.allocate(feed.stopTimeCount * 6).order(ByteOrder.LITTLE_ENDIAN)
        val offsets = IntArray(stopIdsSorted.size + 1)
        for (pos in buckets.indices) {
            offsets[pos] = depBuf.position()
            val bucket = buckets[pos]
            bucket.sort()
            for (j in 0 until bucket.size) {
                val v = bucket[j]
                depBuf.putShort(((v ushr 32) and 0xFFFF).toShort())
                depBuf.putShort(((v ushr 16) and 0xFFFF).toShort())
                depBuf.putShort((v and 0xFFFF).toShort())
            }
        }
        offsets[stopIdsSorted.size] = depBuf.position()
        val offBuf = ByteBuffer.allocate(offsets.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (o in offsets) offBuf.putInt(o)

        // trip meta: (routeIdx u8, serviceIdx u8, directionId u8, headsignIdx u16)
        val tripBuf = ByteBuffer.allocate(feed.trips.size * 5).order(ByteOrder.LITTLE_ENDIAN)
        for (t in feed.trips) {
            tripBuf.put(routeIdx.getValue(t.routeId).toByte())
            tripBuf.put(serviceIdx.getValue(t.serviceId).toByte())
            tripBuf.put(t.directionId.toByte())
            tripBuf.putShort(headsignIdx.getValue(t.headsign).toShort())
        }

        // The RT join (ScheduleIndex.tripIndexOf) equates position in this sorted
        // array with file-order tripIdx, which only holds if trips.txt arrives
        // numerically sorted with no duplicates. Refuse loudly if that changes.
        val tripIdInts = feed.trips.map { it.id.toInt() }
        check(tripIdInts.zipWithNext().all { (a, b) -> a < b }) {
            "trips.txt is no longer strictly sorted by numeric trip_id — " +
                "the realtime join would silently return wrong trips"
        }
        val tidBuf = ByteBuffer.allocate(feed.trips.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (id in tripIdInts) tidBuf.putInt(id)

        val codeBuf = ByteBuffer.allocate(stopIdsSorted.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (id in stopIdsSorted) codeBuf.putInt(id)

        val geoBuf = ByteBuffer.allocate(stopIdsSorted.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (id in stopIdsSorted) {
            val s = stopById.getValue(id)
            geoBuf.putInt((s.lat * 1e6).toInt())
            geoBuf.putInt((s.lon * 1e6).toInt())
        }

        val wbBytes = ByteArray(stopIdsSorted.size) { i -> stopById.getValue(stopIdsSorted[i]).wheelchair.toByte() }

        val sections = LinkedHashMap<String, ByteArray>()
        sections["departures"] = depBuf.array()
        sections["stop_offsets"] = offBuf.array()
        sections["trip_meta"] = tripBuf.array()
        sections["trip_id_sorted"] = tidBuf.array()
        sections["stop_names"] = stringTable(stopIdsSorted.map { stopById.getValue(it).name })
        sections["headsigns"] = stringTable(headsigns)
        sections["route_names"] = stringTable(feed.routes.map { "${it.shortName}\u001F${it.longName}" })
        sections["stop_codes"] = codeBuf.array()
        sections["stop_geo"] = geoBuf.array()
        sections["wheelchair"] = wbBytes

        // v2: calendar + route ids, so the board can run from the index alone
        sections["route_ids"] = stringTable(feed.routes.map { it.id })
        sections["service_ids"] = stringTable(feed.serviceIds)
        val calByService = feed.calendar.associateBy { it.serviceId }
        val calBuf = ByteBuffer.allocate(feed.serviceIds.size * 9).order(ByteOrder.LITTLE_ENDIAN)
        for (sid in feed.serviceIds) {
            val row = calByService[sid]
            if (row == null) {
                calBuf.putInt(0); calBuf.putInt(0); calBuf.put(0)
            } else {
                calBuf.putInt(dateInt(row.startDate))
                calBuf.putInt(dateInt(row.endDate))
                var bits = 0
                for (d in row.activeDays) bits = bits or (1 shl (d.value - 1)) // Monday=bit0
                calBuf.put(bits.toByte())
            }
        }
        sections["calendar"] = calBuf.array()
        val cdBuf = ByteBuffer.allocate(feed.calendarDates.size * 6).order(ByteOrder.LITTLE_ENDIAN)
        for (cd in feed.calendarDates) {
            cdBuf.put(serviceIdx.getValue(cd.serviceId).toByte())
            cdBuf.put(cd.exceptionType.toByte())
            cdBuf.putInt(dateInt(cd.date))
        }
        sections["calendar_dates"] = cdBuf.array()
        return BuiltIndex(sections)
    }

    private fun dateInt(d: java.time.LocalDate): Int = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    /** u32 LE offsets (n+1 entries into the blob, first 0, last = blob length), then UTF-8 blob. */
    private fun stringTable(items: List<String>): ByteArray {
        val encoded = items.map { it.toByteArray(Charsets.UTF_8) }
        val buf = ByteBuffer.allocate((items.size + 1) * 4 + encoded.sumOf { it.size })
            .order(ByteOrder.LITTLE_ENDIAN)
        var off = 0
        for (e in encoded) { buf.putInt(off); off += e.size }
        buf.putInt(off)
        for (e in encoded) buf.put(e)
        return buf.array()
    }

    /** Growable long list without boxing; 489k appends is the hot path. */
    private class LongArrayList {
        private var a = LongArray(8)
        var size = 0; private set
        fun add(v: Long) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        operator fun get(i: Int): Long = a[i]
        fun sort() = a.sort(0, size)
    }
}
