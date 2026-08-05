package moundcity.transit.core.time

import java.time.DayOfWeek
import java.time.LocalDate

data class CalendarRow(
    val serviceId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val activeDays: Set<DayOfWeek>,
)

data class CalendarDateRow(
    val serviceId: String,
    val date: LocalDate,
    /** GTFS exception_type: 1 = service added, 2 = service removed. */
    val exceptionType: Int,
)

/**
 * A service id can be active purely via a calendar_dates addition — the fixture's
 * 319-T2 has no calendar row at all — so exceptions are applied to the union, not
 * used as a filter over calendar rows.
 */
object ServiceCalendar {

    fun activeServices(
        date: LocalDate,
        calendar: List<CalendarRow>,
        calendarDates: List<CalendarDateRow>,
    ): Set<String> {
        val fromCalendar = calendar
            .filter { date >= it.startDate && date <= it.endDate && date.dayOfWeek in it.activeDays }
            .map { it.serviceId }
        val added = calendarDates
            .filter { it.date == date && it.exceptionType == 1 }
            .map { it.serviceId }
        val removed = calendarDates
            .filter { it.date == date && it.exceptionType == 2 }
            .map { it.serviceId }
        return (fromCalendar + added).toSet() - removed.toSet()
    }
}
