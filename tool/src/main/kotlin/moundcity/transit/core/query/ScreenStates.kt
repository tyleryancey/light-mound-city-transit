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

    /** The one expiry question every screen asks (D9). */
    fun isExpired(index: ScheduleIndex, now: Instant, zone: ZoneId): Boolean =
        state(index, now.atZone(zone).toLocalDate()) == State.EXPIRED
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
        is RowStatus.ScheduledNotStarted -> "scheduled · not started yet"
        is RowStatus.ScheduledNoData -> "scheduled · no live data"
        is RowStatus.Canceled -> "canceled"
        is RowStatus.Live -> {
            val age = (nowEpoch - headerTs).coerceAtLeast(0)
            "${delayText(status.delaySeconds)} · live ${age}s ago"
        }
    }

    /** "1 alert" / "3 alerts" — the singular/plural rule, once. */
    fun counted(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** Doc 02 §3.6: the per-stop wheelchair flag, shown as a line on the
     *  departures screen. 0 = unknown; say nothing rather than guess. */
    fun wheelchairText(flag: Int): String? = when (flag) {
        1 -> "wheelchair accessible"
        2 -> "not wheelchair accessible"
        else -> null
    }
}

/** Home's saved-stop rows: number, name, next departure (doc 02 §3.1). */
object HomeState {

    /** stopIdx is null when the schedule no longer resolves the code — the
     *  screen renders that row inert instead of faking a tap target. */
    data class SavedStopRow(val code: Int, val name: String, val nextText: String, val stopIdx: Int?)

    fun savedStopRows(
        index: ScheduleIndex,
        savedCodes: List<Int>,
        now: Instant,
        zone: ZoneId,
        rt: RtTrips? = null,
    ): List<SavedStopRow> {
        // Past expiry every board is empty and "no more today" would be a lie —
        // the expired phrase reaches these rows too (D9).
        val expired = DataAge.isExpired(index, now, zone)
        return savedCodes.map { code ->
            // A code the schedule no longer resolves keeps its row and says why —
            // silently dropping a saved stop is the worst failure mode (doc 03 §5).
            val stop = index.resolveStop(code)
                ?: return@map SavedStopRow(code, "stop $code", "not in this schedule", stopIdx = null)
            val next = if (expired) null else DepartureBoard.at(index, now, zone, stop, limit = 1, rt = rt).firstOrNull()
            SavedStopRow(
                code = code,
                name = index.stopName(stop),
                // The builder owns the whole phrase — a screen prepending "next"
                // would produce "next no more today" (review, Phase 4).
                nextText = when {
                    expired -> "schedule expired"
                    next != null -> "next ${RowFormat.timeText(next.minute)}"
                    else -> "no more today"
                },
                stopIdx = stop,
            )
        }
    }
}

/** Routes serving a stop — the alert filter's key (doc 02 §3.5). */
object StopRoutes {
    fun routesServing(index: ScheduleIndex, stop: Int): Set<Int> =
        index.departures(stop, 0, index.allServiceIdxs(), limit = Int.MAX_VALUE)
            .map { index.tripRoute(it.tripIdx) }
            .toSet()

    /** The union of routes serving the saved stops — every alert-filtering
     *  surface goes through here, so unresolvable codes drop identically. */
    fun routesServingSaved(index: ScheduleIndex, savedCodes: List<Int>): Set<Int> =
        savedCodes.mapNotNull { index.resolveStop(it) }
            .flatMap { routesServing(index, it) }
            .toSet()
}

/** Alert filtering and window phrasing (doc 02 §3.5). */
object AlertMatch {

    data class Matched(
        val header: String,
        val description: String,
        val routeLabels: List<String>,
        /** The routes this alert named, resolved — D13's alert→viewer links. */
        val routeIdxs: List<Int>,
        val alert: RtAlert,
    )

    /** Home's badge source. No saved stops = zero matches — an empty route
     *  union must never fall into the null show-everything sentinel. */
    fun forSavedStops(alerts: RtAlerts, index: ScheduleIndex, savedCodes: List<Int>): List<Matched> =
        if (savedCodes.isEmpty()) emptyList()
        else forRoutes(alerts, index, StopRoutes.routesServingSaved(index, savedCodes))

    /** Home's alerts row (doc 02 §3.1). */
    fun badgeLabel(count: Int): String = when {
        count == 1 -> "Alerts (1 affects your stops)"
        count > 1 -> "Alerts ($count affect your stops)"
        else -> "Alerts"
    }

    /** The departures banner (doc 02 §3.2); null = no banner. */
    fun bannerLabel(count: Int): String? =
        if (count < 1) null else "${RowFormat.counted(count, "alert")} affect${if (count == 1) "s" else ""} this stop →"

    data class FilterState(val filter: Set<Int>?, val label: String)

    /**
     * The alert list's filter and its label, decided together so they can
     * never disagree (the Phase 3 finding-1 lesson). stopRoutes non-null =
     * opened from a departures banner; savedRoutes empty = nothing saved,
     * which shows all but must SAY so, never "your stops".
     */
    fun filterState(stopRoutes: Set<Int>?, showAll: Boolean, savedRoutes: Set<Int>?): FilterState = when {
        stopRoutes != null && showAll -> FilterState(null, "Showing all alerts — tap for this stop")
        stopRoutes != null -> FilterState(stopRoutes, "Showing this stop — tap for all")
        showAll -> FilterState(null, "Showing all alerts — tap for your stops")
        savedRoutes.isNullOrEmpty() -> FilterState(null, "No saved stops — showing all")
        else -> FilterState(savedRoutes, "Showing your stops — tap for all")
    }

    /** null routeFilter = all alerts; otherwise only those naming a filtered route. */
    fun forRoutes(alerts: RtAlerts, index: ScheduleIndex, routeFilter: Set<Int>?): List<Matched> =
        alerts.alerts.mapNotNull { a ->
            val idxs = a.informedRouteIds.mapNotNull { index.routeIndexOf(it) }
            if (routeFilter != null && idxs.none { it in routeFilter }) return@mapNotNull null
            Matched(
                header = a.header,
                description = a.description,
                routeLabels = idxs.map { RouteLabels.displayShortName(index, it) }.distinct(),
                routeIdxs = idxs,
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
