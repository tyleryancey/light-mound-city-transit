package moundcity.transit.core.query

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import moundcity.transit.core.gtfs.ScheduleIndex
import moundcity.transit.core.rt.RtTrips
import moundcity.transit.core.rt.RtVehicle
import moundcity.transit.core.rt.RtVehicles
import moundcity.transit.core.time.ServiceDay

sealed interface RowStatus {
    /** A realtime record exists; absent delay means on time (assumption A5). */
    data class Live(val delaySeconds: Int) : RowStatus
    data object Scheduled : RowStatus
    data object Canceled : RowStatus
}

data class BoardRow(
    val serviceDate: LocalDate,
    val minute: Int,
    /** The scheduled instant; a live row's prediction is this plus the delay. */
    val departure: Instant,
    val tripIdx: Int,
    val seq: Int,
    val routeIdx: Int,
    val headsignIdx: Int,
    val status: RowStatus,
    val vehicle: RtVehicle?,
)

/**
 * The merge of build plan 1.18: static departures joined with the realtime
 * snapshot. A query runs against two service dates — yesterday's, whose
 * >24:00:00 trips spill past midnight, and today's — unioned and sorted by
 * absolute instant (doc 03 §3: how 24:12:00 correctly reads "00:12 tonight").
 * Canceled trips are shown, never removed (doc 02 §3.2).
 */
object DepartureBoard {

    fun at(
        index: ScheduleIndex,
        now: Instant,
        zone: ZoneId,
        stop: Int,
        limit: Int,
        rt: RtTrips? = null,
        vehicles: RtVehicles? = null,
    ): List<BoardRow> {
        val today = now.atZone(zone).toLocalDate()
        val delays = rt?.delayByTrip() ?: emptyMap()
        val canceled = rt?.canceledTrips() ?: emptySet()
        val fixByTrip = vehicles?.fixes?.associateBy { it.tripId } ?: emptyMap()

        val rows = mutableListOf<BoardRow>()
        for (date in listOf(today.minusDays(1), today)) {
            val elapsedSeconds = now.epochSecond - ServiceDay.serviceDayStart(date, zone).epochSecond
            // Negative elapsed is real: on the fall-back day the service day
            // starts 01:00 local, so 00:00–01:00 queries precede it. Clamp to
            // zero — every one of that day's trips is still ahead — rather than
            // skipping the day (review finding, Phase 1d).
            val fromMinute = ((elapsedSeconds + 59) / 60).toInt().coerceAtLeast(0)
            val services = index.activeServiceIdxs(date)
            for (d in index.departures(stop, fromMinute, services, limit)) {
                val tripId = index.tripId(d.tripIdx)
                val status = when {
                    tripId in canceled -> RowStatus.Canceled
                    tripId in delays -> RowStatus.Live(delays[tripId] ?: 0)
                    else -> RowStatus.Scheduled
                }
                rows.add(
                    BoardRow(
                        serviceDate = date,
                        minute = d.minute,
                        departure = ServiceDay.resolve(date, d.minute * 60, zone),
                        tripIdx = d.tripIdx,
                        seq = d.seq,
                        routeIdx = index.tripRoute(d.tripIdx),
                        headsignIdx = index.tripHeadsign(d.tripIdx),
                        status = status,
                        vehicle = fixByTrip[tripId.toString()],
                    )
                )
            }
        }
        rows.sortBy { it.departure }
        return rows.take(limit)
    }
}
