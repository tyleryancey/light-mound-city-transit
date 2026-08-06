package moundcity.transit.core.query

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import moundcity.transit.core.gtfs.ScheduleIndex
import moundcity.transit.core.rt.RtAlert
import moundcity.transit.core.rt.RtAlerts
import moundcity.transit.core.rt.RtTrips

/** Data-age lines and states (doc 02 §3.1 footer, build plan 3.8, D9). */
object DataAge {

    enum class State { FRESH, EXPIRING, EXPIRED }

    private const val EXPIRING_DAYS = 7L
    private const val LIVE_STALE_SECONDS = 15L * 60

    fun state(index: ScheduleIndex, today: LocalDate): State {
        val expiry = index.expiryDate()
        return when {
            today.isAfter(expiry) -> State.EXPIRED
            !today.plusDays(EXPIRING_DAYS).isBefore(expiry) -> State.EXPIRING
            else -> State.FRESH
        }
    }

    fun scheduleLine(index: ScheduleIndex, today: LocalDate): String {
        val expiry = index.expiryDate()
        if (today.isAfter(expiry)) return "Schedule expired $expiry"
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, expiry)
        val tail = when (days) {
            0L -> "expires today"
            1L -> "expires in 1 day"
            else -> "expires in $days days"
        }
        return "Schedule ${index.feedStartDate()} · $tail"
    }

    fun liveLine(nowEpoch: Long, headerTs: Long): String {
        val age = (nowEpoch - headerTs).coerceAtLeast(0)
        return if (age < 60) "Live ${age}s ago" else "Live ${age / 60}m ago"
    }

    /** Live data ages to scheduled at 15 minutes (build plan 3.8). */
    fun liveIsStale(nowEpoch: Long, headerTs: Long): Boolean =
        nowEpoch - headerTs >= LIVE_STALE_SECONDS
}

/** Departure-row text (doc 02 §3.2, verbatim formats). */
object RowFormat {

    fun timeText(minute: Int): String {
        val m = minute % 1440
        return "%02d:%02d".format(java.util.Locale.US, m / 60, m % 60)
    }

    fun delayText(delaySeconds: Int?): String {
        val minutes = (delaySeconds ?: 0) / 60
        return when {
            minutes > 0 -> "$minutes min late"
            minutes < 0 -> "${-minutes} min early"
            else -> "on time"
        }
    }

    fun statusText(status: RowStatus, nowEpoch: Long, headerTs: Long): String = when (status) {
        is RowStatus.Scheduled -> "scheduled"
        is RowStatus.Canceled -> "canceled"
        is RowStatus.Live -> {
            val age = (nowEpoch - headerTs).coerceAtLeast(0)
            "${delayText(status.delaySeconds)} · live ${age}s ago"
        }
    }
}

/** Home's saved-stop rows: number, name, next departure (doc 02 §3.1). */
object HomeState {

    data class SavedStopRow(val code: Int, val name: String, val nextText: String)

    fun savedStopRows(
        index: ScheduleIndex,
        savedCodes: List<Int>,
        now: Instant,
        zone: ZoneId,
        rt: RtTrips? = null,
    ): List<SavedStopRow> = savedCodes.map { code ->
        // A code the schedule no longer resolves keeps its row and says why —
        // silently dropping a saved stop is the worst failure mode (doc 03 §5).
        val stop = index.resolveStop(code)
            ?: return@map SavedStopRow(code, "stop $code", "not in this schedule")
        val next = DepartureBoard.at(index, now, zone, stop, limit = 1, rt = rt).firstOrNull()
        SavedStopRow(
            code = code,
            name = index.stopName(stop),
            // The builder owns the whole phrase — a screen prepending "next"
            // would produce "next no more today" (review, Phase 4).
            nextText = next?.let { "next ${RowFormat.timeText(it.minute)}" } ?: "no more today",
        )
    }
}

/** Routes serving a stop — the alert filter's key (doc 02 §3.5). */
object StopRoutes {
    fun routesServing(index: ScheduleIndex, stop: Int): Set<Int> =
        index.departures(stop, 0, (0 until index.serviceCount).toSet(), limit = Int.MAX_VALUE)
            .map { index.tripRoute(it.tripIdx) }
            .toSet()
}

/** Alert filtering and window phrasing (doc 02 §3.5). */
object AlertMatch {

    data class Matched(val header: String, val description: String, val routeLabels: List<String>, val alert: RtAlert)

    /** Home's badge source. No saved stops = zero matches — an empty route
     *  union must never fall into the null show-everything sentinel. */
    fun forSavedStops(alerts: RtAlerts, index: ScheduleIndex, savedStopIdxs: List<Int>): List<Matched> =
        if (savedStopIdxs.isEmpty()) emptyList()
        else forRoutes(alerts, index, savedStopIdxs.flatMap { StopRoutes.routesServing(index, it) }.toSet())

    /** null routeFilter = all alerts; otherwise only those naming a filtered route. */
    fun forRoutes(alerts: RtAlerts, index: ScheduleIndex, routeFilter: Set<Int>?): List<Matched> =
        alerts.alerts.mapNotNull { a ->
            val idxs = a.informedRouteIds.mapNotNull { index.routeIndexOf(it) }
            if (routeFilter != null && idxs.none { it in routeFilter }) return@mapNotNull null
            Matched(
                header = a.header,
                description = a.description,
                routeLabels = idxs.map { RouteLabels.displayShortName(index, it) }.distinct(),
                alert = a,
            )
        }

    /** The windows span years and most bodies say AS NEEDED — never "in effect now". */
    fun effectiveFrom(alert: RtAlert, zone: ZoneId): String {
        val start = alert.activePeriods.minOfOrNull { it.first } ?: 0L
        val date = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
        return "Effective from $date"
    }
}

/** Trip-detail lines (doc 02 §3.3). */
object TripDetailState {
    fun vehicleLine(vehicleId: String, nowEpoch: Long, fixTs: Long): String {
        val age = (nowEpoch - fixTs).coerceAtLeast(0)
        return "Vehicle $vehicleId · seen ${age}s ago"
    }
}
