package moundcity.transit.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * GTFS service-day arithmetic. A service day starts at "noon minus 12 hours" in the
 * agency zone — equal to local midnight except on the two DST transition days — and
 * a stop time is that start plus its elapsed seconds, which is how 24:12:00 lands on
 * the next calendar day. The subtraction and addition happen on [Instant]: doing them
 * on a zoned wall-clock value silently loses the DST hour.
 */
object ServiceDay {

    fun serviceDayStart(date: LocalDate, zone: ZoneId): Instant =
        date.atTime(LocalTime.NOON).atZone(zone).toInstant().minusSeconds(12 * 3600L)

    fun resolve(date: LocalDate, gtfsSeconds: Int, zone: ZoneId): Instant =
        serviceDayStart(date, zone).plusSeconds(gtfsSeconds.toLong())
}
