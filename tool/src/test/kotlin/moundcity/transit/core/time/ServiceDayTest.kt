package moundcity.transit.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Build plan 1.1–1.3. The service day starts at noon minus 12 hours, computed on
 * an Instant — not local midnight, which differs on the two DST transition days.
 * The DST dates below are outside the feed window and synthetic by necessity
 * (doc 04 task 1.3); the rest are real observed values.
 */
class ServiceDayTest {

    private val chicago = ZoneId.of("America/Chicago")

    private fun seconds(h: Int, m: Int, s: Int) = h * 3600 + m * 60 + s

    // --- 1.1 serviceDayStart ---

    @Test
    fun ordinaryDayStartsAtLocalMidnight() {
        assertEquals(
            Instant.parse("2026-08-05T05:00:00Z"),
            ServiceDay.serviceDayStart(LocalDate.of(2026, 8, 5), chicago),
            "2026-08-05 is an ordinary CDT day; noon-minus-12h must equal local midnight (00:00 CDT = 05:00Z)",
        )
    }

    @Test
    fun springForwardDayStartsAt2300PreviousDayCst() {
        assertEquals(
            Instant.parse("2026-03-08T05:00:00Z"),
            ServiceDay.serviceDayStart(LocalDate.of(2026, 3, 8), chicago),
            "2026-03-08 (spring forward) starts 2026-03-07 23:00 CST = 05:00Z, one hour before local midnight",
        )
    }

    @Test
    fun fallBackDayStartsAt0100Cdt() {
        assertEquals(
            Instant.parse("2026-11-01T06:00:00Z"),
            ServiceDay.serviceDayStart(LocalDate.of(2026, 11, 1), chicago),
            "2026-11-01 (fall back) starts 2026-11-01 01:00 CDT = 06:00Z, one hour after local midnight",
        )
    }

    // --- 1.2 resolve, including times >= 24:00:00 ---

    @Test
    fun ordinaryTimeResolvesToLocalWallClock() {
        assertEquals(
            Instant.parse("2026-08-05T13:15:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 8, 5), seconds(8, 15, 0), chicago),
            "08:15:00 on an ordinary day is 08:15 CDT = 13:15Z",
        )
    }

    @Test
    fun after24hResolvesIntoNextCalendarDay() {
        assertEquals(
            Instant.parse("2026-08-06T05:12:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 8, 5), seconds(24, 12, 0), chicago),
            "24:12:00 on 2026-08-05 is 00:12 CDT on 2026-08-06 = 05:12Z",
        )
    }

    @Test
    fun observedMaxTimeResolves() {
        assertEquals(
            Instant.parse("2026-08-06T06:37:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 8, 5), seconds(25, 37, 0), chicago),
            "25:37:00 (the observed feed maximum) on 2026-08-05 is 01:37 CDT on 2026-08-06 = 06:37Z",
        )
    }

    @Test
    fun after24hOnSpringForwardDayIsElapsedNotWallClock() {
        assertEquals(
            Instant.parse("2026-03-09T05:12:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 3, 8), seconds(24, 12, 0), chicago),
            "24:12:00 on 2026-03-08 is start (05:00Z) + 24h12m elapsed = 05:12Z = 00:12 CDT on 2026-03-09",
        )
    }

    @Test
    fun after24hOnFallBackDayIsElapsedNotWallClock() {
        assertEquals(
            Instant.parse("2026-11-02T06:12:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 11, 1), seconds(24, 12, 0), chicago),
            "24:12:00 on 2026-11-01 is start (06:00Z) + 24h12m elapsed = 06:12Z = 00:12 CST on 2026-11-02",
        )
    }

    @Test
    fun timeInsideRepeatedHourResolvesToSecondPass() {
        assertEquals(
            Instant.parse("2026-11-01T07:30:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 11, 1), seconds(1, 30, 0), chicago),
            "01:30:00 on fall-back day is start (06:00Z) + 1h30m = 07:30Z = 01:30 CST, the second pass " +
                "through the repeated hour; a wall-clock reading picks the CDT first pass (06:30Z)",
        )
    }

    @Test
    fun timeInsideSpringGapResolvesAsElapsedNotGapShifted() {
        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z"),
            ServiceDay.resolve(LocalDate.of(2026, 3, 8), seconds(2, 30, 0), chicago),
            "02:30:00 on spring-forward day is start (05:00Z) + 2h30m = 07:30Z = 01:30 CST; the local " +
                "clock never shows 02:30 and a wall-clock reading gap-shifts to 08:30Z",
        )
    }

    @Test
    fun dstTransitionDaysDifferFromMidnightByExactlyOneHour() {
        val springStart = ServiceDay.serviceDayStart(LocalDate.of(2026, 3, 8), chicago)
        val springMidnight = LocalDate.of(2026, 3, 8).atStartOfDay(chicago).toInstant()
        assertEquals(
            -3600L,
            springStart.epochSecond - springMidnight.epochSecond,
            "spring-forward service day starts 3600 s before local midnight; a wall-clock subtraction reports 0 (correction 5)",
        )

        val fallStart = ServiceDay.serviceDayStart(LocalDate.of(2026, 11, 1), chicago)
        val fallMidnight = LocalDate.of(2026, 11, 1).atStartOfDay(chicago).toInstant()
        assertEquals(
            3600L,
            fallStart.epochSecond - fallMidnight.epochSecond,
            "fall-back service day starts 3600 s after local midnight",
        )
    }
}
