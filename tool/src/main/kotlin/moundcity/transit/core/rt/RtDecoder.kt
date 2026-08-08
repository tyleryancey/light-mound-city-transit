package moundcity.transit.core.rt

/** One StopTimeUpdate, reduced to what the app can use (doc 03 §2: parse and discard). */
data class RtStu(val stopId: String, val delay: Int?, val time: Long?)

data class RtTripEntity(val tripId: String, val canceled: Boolean, val stus: List<RtStu>)

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
    /** Shape anomalies: field names the feed has never sent, plus
     * "entity_without_vehicle". A name appearing here means the feed changed
     * shape (task 1.14) — the fixture test asserts this set is empty. */
    val forbiddenFieldsSeen: Set<String>,
) {
    /** RT tripId is the decimal string of the static trip_id (the measured
     *  100% join) — this owns that fact so no screen re-encodes it. */
    fun fixByTripId(): Map<Int, RtVehicle> =
        fixes.mapNotNull { f -> f.tripId.toIntOrNull()?.let { it to f } }.toMap()
}

data class RtAlert(
    val activePeriods: List<Pair<Long, Long?>>,
    val informedRouteIds: List<String>,
    val informedStopIds: List<String>,
    val effectSeen: Boolean,
    val header: String,
    val description: String,
)

data class RtAlerts(val headerTimestamp: Long, val alerts: List<RtAlert>)

/**
 * Hand-rolled GTFS-RT decoder (decision D4): the 12 fields this feed actually
 * uses, everything else skipped by wire type. Field numbers mirror
 * harness/gtfsrt.py — the oracle these decodes are verified against.
 *
 * Dispatch is guarded by wire type as well as field number (review finding,
 * Phase 1c): a known field number arriving with the wrong wire type is treated
 * as an unknown field and skipped by its ACTUAL type — canonical protobuf
 * behavior, matching the oracle's library — never misparsed into data.
 */
object RtDecoder {

    private const val SR_CANCELED = 3

    // --- wire-type-guarded reads: mismatch → skip as unknown, return null ---

    private fun lenOrSkip(w: RtWire, tag: Int): RtWire? =
        if (tag and 7 == 2) w.readLengthDelimited() else { w.skip(tag and 7); null }

    private fun varintOrSkip(w: RtWire, tag: Int): Long? =
        if (tag and 7 == 0) w.readVarint() else { w.skip(tag and 7); null }

    private fun stringOrSkip(w: RtWire, tag: Int): String? =
        if (tag and 7 == 2) w.readString() else { w.skip(tag and 7); null }

    private fun floatOrSkip(w: RtWire, tag: Int): Float? =
        if (tag and 7 == 5) w.readFloat() else { w.skip(tag and 7); null }

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
                        3 -> lenOrSkip(entity, tag)?.let { tu ->
                            while (tu.hasMore()) {
                                val t2 = tu.readTag()
                                when (t2 ushr 3) {
                                    1 -> lenOrSkip(tu, t2)?.let { trip = readTripDescriptor(it) }
                                    2 -> lenOrSkip(tu, t2)?.let { stus.add(readStu(it)) }
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
                var sawVehicle = false
                var tripId = ""
                var lat = 0.0f
                var lon = 0.0f
                var ts = 0L
                var vehId = ""
                var label = ""
                while (entity.hasMore()) {
                    val tag = entity.readTag()
                    when (tag ushr 3) {
                        4 -> lenOrSkip(entity, tag)?.let { vp ->
                            sawVehicle = true
                            while (vp.hasMore()) {
                                val t2 = vp.readTag()
                                when (t2 ushr 3) {
                                    1 -> lenOrSkip(vp, t2)?.let { tripId = readTripDescriptor(it).first }
                                    2 -> lenOrSkip(vp, t2)?.let { p ->
                                        while (p.hasMore()) {
                                            val t3 = p.readTag()
                                            when (t3 ushr 3) {
                                                1 -> floatOrSkip(p, t3)?.let { lat = it }
                                                2 -> floatOrSkip(p, t3)?.let { lon = it }
                                                3 -> { forbidden.add("bearing"); p.skip(t3 and 7) }
                                                4 -> { forbidden.add("odometer"); p.skip(t3 and 7) }
                                                5 -> { forbidden.add("speed"); p.skip(t3 and 7) }
                                                else -> p.skip(t3 and 7)
                                            }
                                        }
                                    }
                                    3 -> { forbidden.add("current_stop_sequence"); vp.skip(t2 and 7) }
                                    4 -> { forbidden.add("current_status"); vp.skip(t2 and 7) }
                                    5 -> varintOrSkip(vp, t2)?.let { ts = it }
                                    7 -> { forbidden.add("stop_id"); vp.skip(t2 and 7) }
                                    8 -> lenOrSkip(vp, t2)?.let { vd ->
                                        while (vd.hasMore()) {
                                            val t3 = vd.readTag()
                                            when (t3 ushr 3) {
                                                1 -> stringOrSkip(vd, t3)?.let { vehId = it }
                                                2 -> stringOrSkip(vd, t3)?.let { label = it }
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
                if (sawVehicle) {
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
                } else {
                    forbidden.add("entity_without_vehicle")
                }
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
                        5 -> lenOrSkip(entity, tag)?.let { a ->
                            val periods = mutableListOf<Pair<Long, Long?>>()
                            val routeIds = mutableListOf<String>()
                            val stopIds = mutableListOf<String>()
                            var effectSeen = false
                            var header = ""
                            var description = ""
                            while (a.hasMore()) {
                                val t2 = a.readTag()
                                when (t2 ushr 3) {
                                    1 -> lenOrSkip(a, t2)?.let { tr ->
                                        var start = 0L
                                        var end: Long? = null
                                        while (tr.hasMore()) {
                                            val t3 = tr.readTag()
                                            when (t3 ushr 3) {
                                                1 -> varintOrSkip(tr, t3)?.let { start = it }
                                                2 -> varintOrSkip(tr, t3)?.let { end = it }
                                                else -> tr.skip(t3 and 7)
                                            }
                                        }
                                        periods.add(start to end)
                                    }
                                    5 -> lenOrSkip(a, t2)?.let { sel ->
                                        while (sel.hasMore()) {
                                            val t3 = sel.readTag()
                                            when (t3 ushr 3) {
                                                2 -> stringOrSkip(sel, t3)?.let { routeIds.add(it) }
                                                5 -> stringOrSkip(sel, t3)?.let { stopIds.add(it) }
                                                else -> sel.skip(t3 and 7)
                                            }
                                        }
                                    }
                                    7 -> { effectSeen = true; a.skip(t2 and 7) }
                                    10 -> lenOrSkip(a, t2)?.let { header = readTranslatedString(it) }
                                    11 -> lenOrSkip(a, t2)?.let { description = readTranslatedString(it) }
                                    else -> a.skip(t2 and 7)
                                }
                            }
                            alerts.add(RtAlert(periods, routeIds, stopIds, effectSeen, header, description))
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
                1 -> lenOrSkip(w, tag)?.let { h ->
                    while (h.hasMore()) {
                        val t2 = h.readTag()
                        when (t2 ushr 3) {
                            3 -> varintOrSkip(h, t2)?.let { onHeader(it) }
                            else -> h.skip(t2 and 7)
                        }
                    }
                }
                2 -> lenOrSkip(w, tag)?.let { onEntity(it) }
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
                1 -> stringOrSkip(td, tag)?.let { tripId = it }
                4 -> varintOrSkip(td, tag)?.let { canceled = it.toInt() == SR_CANCELED }
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
                1 -> lenOrSkip(ts, tag)?.let { tr ->
                    while (tr.hasMore()) {
                        val t2 = tr.readTag()
                        when (t2 ushr 3) {
                            1 -> stringOrSkip(tr, t2)?.let { if (text.isEmpty()) text = it }
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
                3 -> lenOrSkip(stu, tag)?.let { ste ->
                    while (ste.hasMore()) {
                        val t2 = ste.readTag()
                        when (t2 ushr 3) {
                            1 -> varintOrSkip(ste, t2)?.let { delay = it.toInt() }
                            2 -> varintOrSkip(ste, t2)?.let { time = it }
                            else -> ste.skip(t2 and 7)
                        }
                    }
                }
                4 -> stringOrSkip(stu, tag)?.let { stopId = it }
                else -> stu.skip(tag and 7)
            }
        }
        return RtStu(stopId, delay, time)
    }
}
