package moundcity.transit.core.rt

/** One StopTimeUpdate, reduced to what the app can use (doc 03 §2: parse and discard). */
data class RtStu(val stopId: String, val delay: Int?, val time: Long?)

data class RtTripEntity(val tripId: String, val canceled: Boolean, val stus: List<RtStu>) {

    /** True when every STU appears exactly twice, pairwise adjacent (doc 01 §5c). */
    fun isFullyAdjacentDuplicated(): Boolean {
        if (stus.size < 2 || stus.size % 2 != 0) return false
        return (stus.indices step 2).all { stus[it] == stus[it + 1] }
    }

    /** Collapses adjacent duplicates; a no-op on clean trips. */
    fun dedupedStus(): List<RtStu> {
        val out = ArrayList<RtStu>(stus.size)
        for (s in stus) if (out.isEmpty() || out.last() != s) out.add(s)
        return out
    }
}

data class RtTrips(val headerTimestamp: Long, val entities: List<RtTripEntity>) {

    /** The 207 KB feed's 1,071 useful bytes: one delay (or null = on time) per live trip. */
    fun delayByTrip(): Map<Int, Int?> =
        entities.filter { !it.canceled }
            .associate { e -> e.tripId.toInt() to e.stus.firstNotNullOfOrNull { it.delay } }

    fun canceledTrips(): Set<Int> =
        entities.filter { it.canceled }.map { it.tripId.toInt() }.toSet()
}

data class RtVehicle(
    val tripId: String,
    val latMicro: Int,
    val lonMicro: Int,
    val timestamp: Long,
    val vehicleId: String,
    val label: String,
)

data class RtVehicles(
    val headerTimestamp: Long,
    val fixes: List<RtVehicle>,
    /** Fields the feed has never sent; a name appearing here means it started (task 1.14). */
    val forbiddenFieldsSeen: Set<String>,
)

data class RtAlert(
    val activePeriods: List<Pair<Long, Long?>>,
    val informedRouteIds: List<String>,
    val informedStopIds: List<String>,
    val cause: Int?,
    val effectSeen: Boolean,
    val header: String,
    val description: String,
)

data class RtAlerts(val headerTimestamp: Long, val alerts: List<RtAlert>)

/**
 * Hand-rolled GTFS-RT decoder (decision D4): the 12 fields this feed actually
 * uses, everything else skipped by wire type. Field numbers mirror
 * harness/gtfsrt.py — the oracle these decodes are verified against.
 */
object RtDecoder {

    private const val SR_CANCELED = 3

    fun decodeTrips(bytes: ByteArray): RtTrips {
        var headerTs = 0L
        val entities = mutableListOf<RtTripEntity>()
        forEachTopLevel(bytes,
            onHeader = { headerTs = it },
            onEntity = { entity ->
                var trip: Pair<String, Boolean>? = null
                val stus = mutableListOf<RtStu>()
                while (entity.hasMore()) {
                    val tag = entity.readTag()
                    when (tag ushr 3) {
                        3 -> { // trip_update
                            val tu = entity.readLengthDelimited()
                            while (tu.hasMore()) {
                                val t2 = tu.readTag()
                                when (t2 ushr 3) {
                                    1 -> trip = readTripDescriptor(tu.readLengthDelimited())
                                    2 -> stus.add(readStu(tu.readLengthDelimited()))
                                    else -> tu.skip(t2 and 7)
                                }
                            }
                        }
                        else -> entity.skip(tag and 7)
                    }
                }
                val (tripId, canceled) = trip
                    ?: throw RtDecodeException("trip_update entity without a TripDescriptor")
                entities.add(RtTripEntity(tripId, canceled, stus))
            })
        return RtTrips(headerTs, entities)
    }

    fun decodeVehicles(bytes: ByteArray): RtVehicles {
        var headerTs = 0L
        val fixes = mutableListOf<RtVehicle>()
        val forbidden = mutableSetOf<String>()
        forEachTopLevel(bytes,
            onHeader = { headerTs = it },
            onEntity = { entity ->
                var tripId = ""
                var lat = 0.0f
                var lon = 0.0f
                var ts = 0L
                var vehId = ""
                var label = ""
                while (entity.hasMore()) {
                    val tag = entity.readTag()
                    when (tag ushr 3) {
                        4 -> { // vehicle (VehiclePosition)
                            val vp = entity.readLengthDelimited()
                            while (vp.hasMore()) {
                                val t2 = vp.readTag()
                                when (t2 ushr 3) {
                                    1 -> tripId = readTripDescriptor(vp.readLengthDelimited()).first
                                    2 -> { // Position
                                        val p = vp.readLengthDelimited()
                                        while (p.hasMore()) {
                                            val t3 = p.readTag()
                                            when (t3 ushr 3) {
                                                1 -> lat = p.readFloat()
                                                2 -> lon = p.readFloat()
                                                3 -> { forbidden.add("bearing"); p.skip(t3 and 7) }
                                                4 -> { forbidden.add("odometer"); p.skip(t3 and 7) }
                                                5 -> { forbidden.add("speed"); p.skip(t3 and 7) }
                                                else -> p.skip(t3 and 7)
                                            }
                                        }
                                    }
                                    3 -> { forbidden.add("current_stop_sequence"); vp.skip(t2 and 7) }
                                    4 -> { forbidden.add("current_status"); vp.skip(t2 and 7) }
                                    5 -> ts = vp.readVarint()
                                    7 -> { forbidden.add("stop_id"); vp.skip(t2 and 7) }
                                    8 -> { // VehicleDescriptor
                                        val vd = vp.readLengthDelimited()
                                        while (vd.hasMore()) {
                                            val t3 = vd.readTag()
                                            when (t3 ushr 3) {
                                                1 -> vehId = vd.readString()
                                                2 -> label = vd.readString()
                                                else -> vd.skip(t3 and 7)
                                            }
                                        }
                                    }
                                    else -> vp.skip(t2 and 7)
                                }
                            }
                        }
                        else -> entity.skip(tag and 7)
                    }
                }
                fixes.add(
                    RtVehicle(
                        tripId = tripId,
                        latMicro = (lat.toDouble() * 1e6).toInt(),
                        lonMicro = (lon.toDouble() * 1e6).toInt(),
                        timestamp = ts,
                        vehicleId = vehId,
                        label = label,
                    )
                )
            })
        return RtVehicles(headerTs, fixes, forbidden)
    }

    fun decodeAlerts(bytes: ByteArray): RtAlerts {
        var headerTs = 0L
        val alerts = mutableListOf<RtAlert>()
        forEachTopLevel(bytes,
            onHeader = { headerTs = it },
            onEntity = { entity ->
                while (entity.hasMore()) {
                    val tag = entity.readTag()
                    when (tag ushr 3) {
                        5 -> { // alert
                            val a = entity.readLengthDelimited()
                            val periods = mutableListOf<Pair<Long, Long?>>()
                            val routeIds = mutableListOf<String>()
                            val stopIds = mutableListOf<String>()
                            var cause: Int? = null
                            var effectSeen = false
                            var header = ""
                            var description = ""
                            while (a.hasMore()) {
                                val t2 = a.readTag()
                                when (t2 ushr 3) {
                                    1 -> { // TimeRange
                                        val tr = a.readLengthDelimited()
                                        var start = 0L
                                        var end: Long? = null
                                        while (tr.hasMore()) {
                                            val t3 = tr.readTag()
                                            when (t3 ushr 3) {
                                                1 -> start = tr.readVarint()
                                                2 -> end = tr.readVarint()
                                                else -> tr.skip(t3 and 7)
                                            }
                                        }
                                        periods.add(start to end)
                                    }
                                    5 -> { // EntitySelector
                                        val sel = a.readLengthDelimited()
                                        while (sel.hasMore()) {
                                            val t3 = sel.readTag()
                                            when (t3 ushr 3) {
                                                2 -> routeIds.add(sel.readString())
                                                5 -> stopIds.add(sel.readString())
                                                else -> sel.skip(t3 and 7)
                                            }
                                        }
                                    }
                                    6 -> cause = a.readVarint().toInt()
                                    7 -> { effectSeen = true; a.skip(t2 and 7) }
                                    10 -> header = readTranslatedString(a.readLengthDelimited())
                                    11 -> description = readTranslatedString(a.readLengthDelimited())
                                    else -> a.skip(t2 and 7)
                                }
                            }
                            alerts.add(RtAlert(periods, routeIds, stopIds, cause, effectSeen, header, description))
                        }
                        else -> entity.skip(tag and 7)
                    }
                }
            })
        return RtAlerts(headerTs, alerts)
    }

    // --- shared walkers ---

    private fun forEachTopLevel(bytes: ByteArray, onHeader: (Long) -> Unit, onEntity: (RtWire) -> Unit) {
        val w = RtWire(bytes)
        while (w.hasMore()) {
            val tag = w.readTag()
            when (tag ushr 3) {
                1 -> { // FeedHeader
                    val h = w.readLengthDelimited()
                    while (h.hasMore()) {
                        val t2 = h.readTag()
                        when (t2 ushr 3) {
                            3 -> onHeader(h.readVarint())
                            else -> h.skip(t2 and 7)
                        }
                    }
                }
                2 -> onEntity(w.readLengthDelimited())
                else -> w.skip(tag and 7)
            }
        }
    }

    /** Returns (trip_id, canceled). */
    private fun readTripDescriptor(td: RtWire): Pair<String, Boolean> {
        var tripId = ""
        var canceled = false
        while (td.hasMore()) {
            val tag = td.readTag()
            when (tag ushr 3) {
                1 -> tripId = td.readString()
                4 -> canceled = td.readVarint().toInt() == SR_CANCELED
                else -> td.skip(tag and 7)
            }
        }
        return tripId to canceled
    }

    /** TranslatedString → the first translation's text (the feed is monolingual). */
    private fun readTranslatedString(ts: RtWire): String {
        var text = ""
        while (ts.hasMore()) {
            val tag = ts.readTag()
            when (tag ushr 3) {
                1 -> { // Translation
                    val tr = ts.readLengthDelimited()
                    while (tr.hasMore()) {
                        val t2 = tr.readTag()
                        when (t2 ushr 3) {
                            1 -> if (text.isEmpty()) text = tr.readString() else tr.skip(t2 and 7)
                            else -> tr.skip(t2 and 7)
                        }
                    }
                }
                else -> ts.skip(tag and 7)
            }
        }
        return text
    }

    private fun readStu(stu: RtWire): RtStu {
        var stopId = ""
        var delay: Int? = null
        var time: Long? = null
        while (stu.hasMore()) {
            val tag = stu.readTag()
            when (tag ushr 3) {
                3 -> { // departure StopTimeEvent
                    val ste = stu.readLengthDelimited()
                    while (ste.hasMore()) {
                        val t2 = ste.readTag()
                        when (t2 ushr 3) {
                            1 -> delay = ste.readVarint().toInt()
                            2 -> time = ste.readVarint()
                            else -> ste.skip(t2 and 7)
                        }
                    }
                }
                4 -> stopId = stu.readString()
                else -> stu.skip(tag and 7)
            }
        }
        return RtStu(stopId, delay, time)
    }
}
