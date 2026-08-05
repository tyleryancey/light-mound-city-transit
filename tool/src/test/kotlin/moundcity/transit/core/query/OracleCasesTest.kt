package moundcity.transit.core.query

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 exit gate: the stl_oracle_cases sweep. Most of the 19 cases are
 * pinned across the existing suites; this file closes the last three that
 * had no direct test. The three holiday cases stay with M4/Phase 4.7 — the
 * feed window contains no holiday to measure.
 */
class OracleCasesTest {

    private val chicago = ZoneId.of("America/Chicago")
    private val index get() = QueryTestData.index

    @Test
    fun sundayBoardRunsSundaysOwnPattern() {
        // stl_oracle_cases "sunday": 2026-08-09, 11:49:12 CDT, stop 10624.
        // Same minutes as the weekday golden but DIFFERENT trips (3413423…),
        // from services {325-B3 (idx 4), 325-T3 (idx 7)}.
        assertEquals(setOf(4, 7), index.activeServiceIdxs(LocalDate.of(2026, 8, 9)), "Sunday's service set")
        val rows = DepartureBoard.at(
            index, Instant.parse("2026-08-09T16:49:12Z"), chicago, index.resolveStop(10624)!!, limit = 4,
        )
        assertEquals(listOf(710, 712, 720, 722), rows.map { it.minute }, "Sunday rail keeps the 10-minute headway at midday")
        assertEquals(
            listOf(3413423, 3413298, 3413424, 3413299),
            rows.map { index.tripId(it.tripIdx) },
            "Sunday's OWN trips, not the weekday golden's",
        )
    }

    @Test
    fun pastExpiryBoardIsEmptyNotAnError() {
        // stl_oracle_cases "feed_expired" + "last_departure_of_day": past
        // 2026-08-30 no service is defined on either queried date; the board's
        // correct answer is an empty list (the UI layer turns the expiry date
        // into the distinct visible state, D9).
        val rows = DepartureBoard.at(
            index, Instant.parse("2026-08-31T14:00:00Z"), chicago, index.resolveStop(10624)!!, limit = 8,
        )
        assertEquals(emptyList(), rows, "no active services past expiry — empty, never an exception")
    }

    @Test
    fun noStopServesBothBusAndRailInThisFeed() {
        // stl_oracle_cases "multimodal_stop", pinned as a tripwire: this feed
        // has ZERO stops served by both modes (rail stations are rail-only ids;
        // transit centers are separate bus stops). The board therefore never
        // mixes modes at one stop. If a future feed merges them, this trips and
        // the case gets a real golden.
        val rail = setOf("19731B", "19731R", "19870B", "19870R")
        var multimodal = 0
        for (stop in 0 until index.stopCount) {
            var sawRail = false
            var sawBus = false
            for (d in index.departures(stop, 0, (0 until index.serviceCount).toSet(), limit = Int.MAX_VALUE)) {
                if (index.routeId(index.tripRoute(d.tripIdx)) in rail) sawRail = true else sawBus = true
                if (sawRail && sawBus) break
            }
            if (sawRail && sawBus) multimodal++
        }
        assertEquals(0, multimodal, "zero multimodal stops in the 2026-07-30 pick")
        assertTrue(index.stopCount == 5118, "sweep actually walked all 5,118 stops")
    }
}
