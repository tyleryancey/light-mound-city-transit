package moundcity.transit.core.query

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3 state builders: everything the screens render, pure JVM (doc 03:
 * no logic in ui/). Formats are doc 02 §3.1–§3.3 verbatim.
 */
class ScreenStatesTest {

    private val chicago = ZoneId.of("America/Chicago")
    private val index get() = QueryTestData.index

    // --- data age (3.1 footer + 3.8 states) ---

    @Test
    fun indexKnowsItsOwnWindow() {
        assertEquals(LocalDate.of(2026, 7, 30), index.feedStartDate(), "min calendar start")
        assertEquals(LocalDate.of(2026, 8, 30), index.expiryDate(), "max end/exception date — the 1.10 rule from the sections")
    }

    @Test
    fun scheduleLineCountsDownAndStates() {
        assertEquals(
            "Schedule 2026-07-30 · expires in 25 days",
            DataAge.scheduleLine(index, LocalDate.of(2026, 8, 5)),
            "doc 02 §3.1 wording",
        )
        assertEquals(DataAge.State.FRESH, DataAge.state(index, LocalDate.of(2026, 8, 5)), "26 days out is fresh")
        assertEquals(DataAge.State.EXPIRING, DataAge.state(index, LocalDate.of(2026, 8, 25)), "5 days left is expiring (≤7)")
        assertEquals(DataAge.State.EXPIRED, DataAge.state(index, LocalDate.of(2026, 8, 31)), "past expiry replaces the list (D9)")
        assertEquals(
            "Schedule expired 2026-08-30",
            DataAge.scheduleLine(index, LocalDate.of(2026, 9, 1)),
            "an expired schedule says so, never a negative countdown",
        )
    }

    @Test
    fun scheduleLineSpeaksSingularAndToday() {
        assertEquals(
            "Schedule 2026-07-30 · expires in 1 day",
            DataAge.scheduleLine(index, LocalDate.of(2026, 8, 29)),
            "one day left is singular (review finding 10)",
        )
        assertEquals(
            "Schedule 2026-07-30 · expires today",
            DataAge.scheduleLine(index, LocalDate.of(2026, 8, 30)),
            "the expiry day itself reads 'today', never 'in 0 days'",
        )
    }

    @Test
    fun liveAgeLineAndStaleness() {
        assertEquals("Live 41s ago", DataAge.liveLine(nowEpoch = 1000L + 41, headerTs = 1000L), "doc 02 §3.1")
        assertEquals("Live 3m ago", DataAge.liveLine(nowEpoch = 1000L + 200, headerTs = 1000L), "minutes past 60s")
        assertTrue(!DataAge.liveIsStale(nowEpoch = 1000L + 899, headerTs = 1000L), "14m59s is still live")
        assertTrue(DataAge.liveIsStale(nowEpoch = 1000L + 900, headerTs = 1000L), "live ages to scheduled at 15 minutes (3.8)")
    }

    // --- row formats (3.2) ---

    @Test
    fun timeAndDelayFormats() {
        assertEquals("11:52", RowFormat.timeText(712), "minute of service day")
        assertEquals("00:12", RowFormat.timeText(1452), "24:12 renders as tonight's 00:12")
        assertEquals("on time", RowFormat.delayText(0), "zero delay")
        assertEquals("on time", RowFormat.delayText(null), "absent delay = on time (A5)")
        assertEquals("3 min late", RowFormat.delayText(180), "late in whole minutes")
        assertEquals("4 min early", RowFormat.delayText(-240), "early is the ordinary case, 24.3%")
    }

    @Test
    fun statusLinesMatchTheSpec() {
        assertEquals("scheduled", RowFormat.statusText(RowStatus.Scheduled, nowEpoch = 0, headerTs = 0), "trains always read scheduled")
        assertEquals(
            "3 min late · live 22s ago",
            RowFormat.statusText(RowStatus.Live(180), nowEpoch = 1022, headerTs = 1000),
            "doc 02 §3.2 exact",
        )
        assertEquals("canceled", RowFormat.statusText(RowStatus.Canceled, nowEpoch = 0, headerTs = 0), "shown struck, never removed")
        assertEquals(
            "scheduled · not started yet",
            RowFormat.statusText(RowStatus.ScheduledNotStarted, nowEpoch = 0, headerTs = 0),
            "live data exists; this trip simply has not left its first stop (D13)",
        )
        assertEquals(
            "scheduled · no live data",
            RowFormat.statusText(RowStatus.ScheduledNoData, nowEpoch = 0, headerTs = 0),
            "should be running, feed silent — the measured ~9% of in-service buses",
        )
    }

    @Test
    fun wheelchairLineComesFromTheFeedFlag() {
        assertEquals("wheelchair accessible", RowFormat.wheelchairText(1), "flag 1 = accessible (doc 02 §3.6: shown as a line on departures)")
        assertEquals("not wheelchair accessible", RowFormat.wheelchairText(2), "flag 2 = not accessible — 2,978 of 5,118 stops")
        assertNull(RowFormat.wheelchairText(0), "flag 0 = unknown; say nothing rather than guess")
    }

    // --- home (3.1): next departure per saved stop ---

    @Test
    fun homeNextDeparturePerSavedStop() {
        val rows = HomeState.savedStopRows(
            index, listOf(10624, 7855), Instant.parse("2026-08-03T16:49:12Z"), chicago,
            QueryTestData.rtTrips,
        )
        assertEquals(2, rows.size, "one row per saved stop")
        assertEquals("10624", rows[0].code.toString(), "stop code first")
        assertTrue("METROLINK" in rows[0].name, "station name attached")
        // The builder owns the whole phrase — the screen must never prepend
        // "next" onto "no more today" or "not in this schedule" (review, Phase 4).
        assertEquals("next 11:50", rows[0].nextText, "next rail departure at the golden instant")
        assertEquals("next 11:58", rows[1].nextText, "next live bus at 7855 (718)")
    }

    @Test
    fun aSavedStopMissingFromTheScheduleStaysVisible() {
        val rows = HomeState.savedStopRows(
            index, listOf(10624, 99999), Instant.parse("2026-08-03T16:49:12Z"), chicago,
        )
        assertEquals(2, rows.size, "a vanished saved stop is never silently dropped (4.4)")
        assertEquals("stop 99999", rows[1].name, "no name to show — the code is the identity")
        assertEquals("not in this schedule", rows[1].nextText, "the row says why there is no next time")
        assertNull(rows[1].stopIdx, "no index = the screen renders the row inert, no fake tap target")
        assertEquals(index.resolveStop(10624), rows[0].stopIdx, "a live row carries its stop for the tap")
    }

    @Test
    fun expiredScheduleReachesTheHomeRowsToo() {
        // Past expiry, active services are empty and every row would read
        // "no more today" — a lie D9 exists to prevent.
        val rows = HomeState.savedStopRows(
            index, listOf(10624), Instant.parse("2026-09-02T16:00:00Z"), chicago,
        )
        assertEquals("schedule expired", rows[0].nextText, "an expired schedule says so on every surface (D9)")
    }

    @Test
    fun alertPhrasesHaveOneOwner() {
        assertEquals("Alerts", AlertMatch.badgeLabel(0), "no count when nothing affects your stops")
        assertEquals("Alerts (1 affects your stops)", AlertMatch.badgeLabel(1), "singular verb")
        assertEquals("Alerts (3 affect your stops)", AlertMatch.badgeLabel(3), "plural verb")
        assertNull(AlertMatch.bannerLabel(0), "no banner when nothing matches")
        assertEquals("1 alert affects this stop →", AlertMatch.bannerLabel(1), "doc 02 §3.2 banner, singular")
        assertEquals("3 alerts affect this stop →", AlertMatch.bannerLabel(3), "plural")
    }

    @Test
    fun alertFilterAndLabelAreOneDecision() {
        val stopRoutes = setOf(1, 2)
        val saved = setOf(3)
        // (stop-scoped, saved-scoped, toggled-to-all, nothing-saved) × label — the
        // four states finding 1 taught us to keep honest, decided in one place.
        AlertMatch.filterState(stopRoutes, showAll = false, savedRoutes = null).let {
            assertEquals(stopRoutes, it.filter, "banner navigation filters to the stop's routes")
            assertEquals("Showing this stop — tap for all", it.label, "and says so")
        }
        AlertMatch.filterState(stopRoutes, showAll = true, savedRoutes = null).let {
            assertEquals(null, it.filter, "toggled: everything")
            assertEquals("Showing all alerts — tap for this stop", it.label, "with the way back")
        }
        AlertMatch.filterState(null, showAll = false, savedRoutes = saved).let {
            assertEquals(saved, it.filter, "default: saved-stop routes")
            assertEquals("Showing your stops — tap for all", it.label, "doc 02 §3.5")
        }
        AlertMatch.filterState(null, showAll = true, savedRoutes = saved).let {
            assertEquals(null, it.filter, "toggled from saved: everything")
            assertEquals("Showing all alerts — tap for your stops", it.label, "and the way back")
        }
        AlertMatch.filterState(null, showAll = false, savedRoutes = emptySet()).let {
            assertEquals(null, it.filter, "nothing saved shows all")
            assertEquals("No saved stops — showing all", it.label, "but says so — never 'your stops' (finding 1)")
        }
    }

    // --- alerts (3.6) ---

    @Test
    fun alertsFilterToSavedStopRoutesAndCount() {
        val alerts = QueryTestData.rtAlerts
        val routes = StopRoutes.routesServing(index, index.resolveStop(10624)!!)
        assertTrue(routes.isNotEmpty(), "a rail station is served by rail routes")
        val all = AlertMatch.forRoutes(alerts, index, null)
        assertEquals(24, all.size, "null filter = the full bounded list")
        val mine = AlertMatch.forRoutes(alerts, index, routes)
        assertTrue(mine.size < all.size, "saved-stop filtering narrows; got ${mine.size}")
        assertTrue(all.all { it.header.isNotEmpty() }, "header text always says what happened")
    }

    @Test
    fun homeAlertCountIsZeroWhenNothingIsSaved() {
        val alerts = QueryTestData.rtAlerts
        assertEquals(
            0,
            AlertMatch.forSavedStops(alerts, index, emptyList()).size,
            "zero saved stops must never read as 'affect your stops' (review finding 1)",
        )
        val stop = index.resolveStop(10624)!!
        assertEquals(
            AlertMatch.forRoutes(alerts, index, StopRoutes.routesServing(index, stop)).size,
            AlertMatch.forSavedStops(alerts, index, listOf(10624)).size,
            "with saved stops the badge is the route-union filter, unchanged",
        )
    }

    @Test
    fun effectiveFromNeverClaimsInEffectNow() {
        val a = QueryTestData.rtAlerts.alerts.first()
        val text = AlertMatch.effectiveFrom(a, chicago)
        assertTrue(text.startsWith("Effective from "), "doc 02 §3.5: the window is wide; never say 'in effect now'; got '$text'")
    }

    // --- trip detail (3.3) ---

    @Test
    fun vehicleLineAndStraightLineSuffix() {
        assertEquals(
            "Vehicle 3835 · seen 22s ago",
            TripDetailState.vehicleLine("3835", nowEpoch = 1022, fixTs = 1000),
            "doc 02 §3.3 exact",
        )
        // The '(straight line)' words are load-bearing (doc 02 §3.3) — the
        // Approach phrasings carry them whenever a distance is shown.
        val stops = (0 until 6).map { moundcity.transit.core.gtfs.StopTimeRow(it, 480 + it, it + 1) }
        val e = Approach.estimate(stops, 3, 38_010_000, -90_000_000, { 38_000_000 + it * 10_000 }, { -90_000_000 })
        assertEquals(
            "about 1 stop away · 0.7 mi (straight line)",
            (e as ApproachEstimate.StopsAway).text(),
            "the distance figure never stands alone",
        )
    }
}
