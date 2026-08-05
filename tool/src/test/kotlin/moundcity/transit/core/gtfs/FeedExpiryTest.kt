package moundcity.transit.core.gtfs

import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Build plan 1.10: expiry = max(calendar.end_date, calendar_dates.date). The feed
 * has NO feed_info.txt — a reader that looks for one and finds nothing must not
 * conclude "no expiry", so the derivation takes only calendar data and refuses
 * to answer from nothing.
 */
class FeedExpiryTest {

    @Test
    fun fixtureExpiresAtCalendarEnd() {
        ZipFile(FixturePaths.gtfsZip).use { zip ->
            val ends = mutableListOf<LocalDate>()
            zip.getInputStream(zip.getEntry("calendar.txt")).bufferedReader().use { r ->
                GtfsCsv.forEachRow(r) { row -> ends.add(GtfsDates.parse(row["end_date"])) }
            }
            val exceptions = mutableListOf<LocalDate>()
            zip.getInputStream(zip.getEntry("calendar_dates.txt")).bufferedReader().use { r ->
                GtfsCsv.forEachRow(r) { row -> exceptions.add(GtfsDates.parse(row["date"])) }
            }
            assertEquals(
                LocalDate.of(2026, 8, 30),
                FeedExpiry.expiryDate(ends, exceptions),
                "fixture calendar ends 2026-08-30 and both exceptions predate it",
            )
        }
    }

    @Test
    fun lateExceptionExtendsExpiryPastCalendarEnd() {
        assertEquals(
            LocalDate.of(2026, 9, 7),
            FeedExpiry.expiryDate(
                listOf(LocalDate.of(2026, 8, 30)),
                listOf(LocalDate.of(2026, 9, 7)),
            ),
            "a calendar_dates row after every end_date is the true expiry (it is service too)",
        )
    }

    @Test
    fun noCalendarDataRefusesRatherThanMeaningNoExpiry() {
        assertFailsWith<IllegalStateException>("empty calendar data must throw, never read as 'no expiry'") {
            FeedExpiry.expiryDate(emptyList(), emptyList())
        }
    }

    @Test
    fun gtfsDateParsesBasicIsoDigits() {
        assertEquals(LocalDate.of(2026, 8, 8), GtfsDates.parse("20260808"), "GTFS dates are yyyyMMdd")
    }
}
