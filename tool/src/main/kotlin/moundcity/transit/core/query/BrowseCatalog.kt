package moundcity.transit.core.query

import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * Browse's three finite lists (doc 02 §3.4): 38 rail stations, 30 merged
 * transit centers, 62 routes grouped Missouri / Illinois / Rail. Every leaf
 * is a stop; no free-text search exists anywhere.
 */
object BrowseCatalog {

    data class Station(val name: String, val stopIdx: Int)
    data class Center(val name: String, val stopIdxs: List<Int>)
    data class BusRoute(val label: String, val routeIdx: Int)
    data class RailLine(val label: String, val routeIdxs: List<Int>)
    data class RouteGroups(val missouri: List<BusRoute>, val illinois: List<BusRoute>, val rail: List<RailLine>)

    private val RAIL_IDS = setOf("19731B", "19731R", "19870B", "19870R")

    /** Stops served by rail — each station is a single stop, both directions. */
    fun railStations(index: ScheduleIndex): List<Station> =
        stopsServedBy(index) { routeId -> routeId in RAIL_IDS }
            .map { Station(index.stopName(it), it) }
            .sortedBy { it.name }

    /** "TRANSIT CENTER" stops merged on the name up to and including the phrase. */
    fun transitCenters(index: ScheduleIndex): List<Center> =
        (0 until index.stopCount)
            .filter { "TRANSIT CENTER" in index.stopName(it) }
            .groupBy { index.stopName(it).substringBefore("TRANSIT CENTER") + "TRANSIT CENTER" }
            .map { (name, idxs) -> Center(name, idxs) }
            .sortedBy { it.name }

    fun routesGrouped(index: ScheduleIndex): RouteGroups {
        val mo = mutableListOf<BusRoute>()
        val il = mutableListOf<BusRoute>()
        val railByLabel = LinkedHashMap<String, MutableList<Int>>()
        for (r in 0 until index.routeCount) {
            val id = index.routeId(r)
            when {
                id in RAIL_IDS -> railByLabel.getOrPut(index.routeShortName(r)) { mutableListOf() }.add(r)
                RouteLabels.isIllinois(id) -> il.add(BusRoute(RouteLabels.displayShortName(index, r), r))
                else -> mo.add(BusRoute(RouteLabels.displayShortName(index, r), r))
            }
        }
        return RouteGroups(
            missouri = mo.sortedBy { it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE },
            illinois = il.sortedBy { it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE },
            rail = railByLabel.entries.map { (label, idxs) -> RailLine(label, idxs) }.sortedBy { it.label },
        )
    }

    /** The route's stop sequence for a direction: its longest trip's stops
     * (doc 02 §3.4 — each route opens its stop list by direction). */
    fun routeStops(index: ScheduleIndex, routeIdx: Int, direction: Int): List<Int> {
        var bestTrip = -1
        var bestSize = -1
        for (t in 0 until index.tripCount) {
            if (index.tripRoute(t) == routeIdx && index.tripDirection(t) == direction) {
                val n = index.tripStops(t, fromSeq = 0).size
                if (n > bestSize) { bestSize = n; bestTrip = t }
            }
        }
        if (bestTrip < 0) return emptyList()
        return index.tripStops(bestTrip, fromSeq = 0).map { it.stopIdx }.distinct()
    }

    private fun stopsServedBy(index: ScheduleIndex, pred: (String) -> Boolean): List<Int> {
        val all = (0 until index.serviceCount).toSet()
        return (0 until index.stopCount).filter { stop ->
            index.departures(stop, 0, all, limit = Int.MAX_VALUE)
                .any { pred(index.routeId(index.tripRoute(it.tripIdx))) }
        }
    }
}
