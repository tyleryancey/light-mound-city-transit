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

    /** Stops served by rail — each station is a single stop, both directions. */
    fun railStations(index: ScheduleIndex): List<Station> =
        stopsServedBy(index) { r -> RouteLabels.isRail(index, r) }
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
            when {
                RouteLabels.isRail(index, r) -> railByLabel.getOrPut(index.routeShortName(r)) { mutableListOf() }.add(r)
                RouteLabels.isIllinois(index.routeId(r)) -> il.add(BusRoute(RouteLabels.displayShortName(index, r), r))
                else -> mo.add(BusRoute(RouteLabels.displayShortName(index, r), r))
            }
        }
        val byNumber = compareBy<BusRoute> { it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
        return RouteGroups(
            missouri = mo.sortedWith(byNumber),
            illinois = il.sortedWith(byNumber),
            rail = railByLabel.entries.map { (label, idxs) -> RailLine(label, idxs) }.sortedBy { it.label },
        )
    }

    /** The route's stop sequence for a direction: its longest trip's stops
     * (doc 02 §3.4 — each route opens its stop list by direction). Row counts
     * come from one pass over the departures section; tripStops() is itself a
     * full scan, so calling it per candidate trip would be quadratic. */
    fun routeStops(index: ScheduleIndex, routeIdx: Int, direction: Int): List<Int> {
        val all = index.allServiceIdxs()
        val counts = IntArray(index.tripCount)
        for (stop in 0 until index.stopCount) {
            for (row in index.departures(stop, 0, all, limit = Int.MAX_VALUE)) {
                if (index.tripRoute(row.tripIdx) == routeIdx && index.tripDirection(row.tripIdx) == direction) {
                    counts[row.tripIdx]++
                }
            }
        }
        var bestTrip = -1
        var bestSize = 0
        for (t in 0 until index.tripCount) {
            if (counts[t] > bestSize) { bestSize = counts[t]; bestTrip = t }
        }
        if (bestTrip < 0) return emptyList()
        return index.tripStops(bestTrip, fromSeq = 0).map { it.stopIdx }.distinct()
    }

    private fun stopsServedBy(index: ScheduleIndex, pred: (Int) -> Boolean): List<Int> {
        val all = index.allServiceIdxs()
        return (0 until index.stopCount).filter { stop ->
            index.departures(stop, 0, all, limit = Int.MAX_VALUE)
                .any { pred(index.tripRoute(it.tripIdx)) }
        }
    }
}
