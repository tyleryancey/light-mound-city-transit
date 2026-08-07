package moundcity.transit.core.query

import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * Build plan 1.22. Eight bus numbers are shared between Missouri and Illinois
 * routes and carry a discreet MO/IL marker (doc 02 §3.4). MLB/MLR also repeat
 * — the same physical lines under old- and new-pick route_ids — and render
 * bare: a marker there would invent a distinction riders shouldn't see.
 */
object RouteLabels {

    private val ILLINOIS_IDS = 19855..19868

    fun isIllinois(routeId: String): Boolean = routeId.toIntOrNull() in ILLINOIS_IDS

    /** Rail identity keys on short name: rail route_ids rotate at pick
     *  changes (correction 3), MLB/MLR do not. */
    fun isRail(index: ScheduleIndex, routeIdx: Int): Boolean =
        index.routeShortName(routeIdx).let { it == "MLB" || it == "MLR" }

    fun displayShortName(index: ScheduleIndex, routeIdx: Int): String {
        val short = index.routeShortName(routeIdx)
        val sharers = (0 until index.routeCount).filter { index.routeShortName(it) == short }
        if (sharers.size < 2) return short
        // Only a collision that spans MO and IL gets markers; the rail pick
        // twins are both Missouri and stay bare.
        if (sharers.none { isIllinois(index.routeId(it)) }) return short
        return if (isIllinois(index.routeId(routeIdx))) "$short IL" else "$short MO"
    }
}
