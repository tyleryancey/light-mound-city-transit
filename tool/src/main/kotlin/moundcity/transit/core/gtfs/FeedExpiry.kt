package moundcity.transit.core.gtfs

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object GtfsDates {
    private val BASIC = DateTimeFormatter.BASIC_ISO_DATE

    /** GTFS dates are yyyyMMdd. */
    fun parse(value: String): LocalDate = LocalDate.parse(value, BASIC)
}

object FeedExpiry {

    /**
     * The feed carries no feed_info.txt, so expiry is derived from service data:
     * the last date anything runs. Empty input is an error — "couldn't find an
     * expiry" must never read as "doesn't expire".
     */
    fun expiryDate(calendarEndDates: List<LocalDate>, calendarDateDates: List<LocalDate>): LocalDate =
        (calendarEndDates + calendarDateDates).maxOrNull()
            ?: throw IllegalStateException("no calendar.txt end_dates and no calendar_dates.txt rows — cannot derive expiry")
}
