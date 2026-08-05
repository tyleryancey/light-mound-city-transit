package moundcity.transit.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Build plan 1.4: active services = calendar weekday bits (within the date window,
 * both ends inclusive) UNION calendar_dates type 1, MINUS type 2. The rows below are
 * the complete real calendar of the 2026-07-30 fixture: 7 calendar rows plus 319-T2,
 * which exists ONLY in calendar_dates and carries 250 rail trips.
 */
class ServiceCalendarTest {

    private val weekdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun row(id: String, start: String, end: String, days: Set<DayOfWeek>) =
        CalendarRow(id, LocalDate.parse(start), LocalDate.parse(end), days)

    private val calendar = listOf(
        row("325-B1", "2026-07-30", "2026-08-30", weekdays),
        row("325-B2", "2026-07-30", "2026-08-30", setOf(DayOfWeek.SATURDAY)),
        row("325-B3", "2026-07-30", "2026-08-30", setOf(DayOfWeek.SUNDAY)),
        row("319-T1", "2026-07-30", "2026-08-07", weekdays),
        row("325-T1", "2026-08-10", "2026-08-30", weekdays),
        row("325-T2", "2026-08-01", "2026-08-30", setOf(DayOfWeek.SATURDAY)),
        row("325-T3", "2026-08-02", "2026-08-30", setOf(DayOfWeek.SUNDAY)),
    )

    private val calendarDates = listOf(
        CalendarDateRow("319-T2", LocalDate.parse("2026-08-08"), 1),
        CalendarDateRow("325-T2", LocalDate.parse("2026-08-08"), 2),
    )

    @Test
    fun exceptionDayAddsServiceWithNoCalendarRowAndRemovesTheRegularOne() {
        assertEquals(
            setOf("325-B2", "319-T2"),
            ServiceCalendar.activeServices(LocalDate.parse("2026-08-08"), calendar, calendarDates),
            "2026-08-08 (Sat): 319-T2 is added by calendar_dates despite no calendar row; " +
                "325-T2's Saturday bit is overridden by its type-2 removal; 325-B2 runs normally",
        )
    }

    @Test
    fun ordinaryWeekdayUsesWeekdayBitsWithinWindow() {
        assertEquals(
            setOf("325-B1", "319-T1"),
            ServiceCalendar.activeServices(LocalDate.parse("2026-08-05"), calendar, calendarDates),
            "2026-08-05 (Wed): 325-B1 and 319-T1 are in window; 325-T1 has not started",
        )
    }

    @Test
    fun weekdayAfterOldPickWindowClosesSwitchesToNewPick() {
        assertEquals(
            setOf("325-B1", "325-T1"),
            ServiceCalendar.activeServices(LocalDate.parse("2026-08-12"), calendar, calendarDates),
            "2026-08-12 (Wed): 319-T1's window ended 2026-08-07; 325-T1 has taken over",
        )
    }

    @Test
    fun endDateIsInclusive() {
        assertEquals(
            setOf("325-B1", "319-T1"),
            ServiceCalendar.activeServices(LocalDate.parse("2026-08-07"), calendar, calendarDates),
            "2026-08-07 (Fri) is 319-T1's end_date and end_date is inclusive",
        )
    }

    @Test
    fun startDateIsInclusive() {
        assertEquals(
            setOf("325-B1", "325-T1"),
            ServiceCalendar.activeServices(LocalDate.parse("2026-08-10"), calendar, calendarDates),
            "2026-08-10 (Mon) is 325-T1's start_date and start_date is inclusive",
        )
    }
}
