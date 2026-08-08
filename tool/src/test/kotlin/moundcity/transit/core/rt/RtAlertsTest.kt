package moundcity.transit.core.rt

import moundcity.transit.core.query.QueryTestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Build plan 1.15: 24 alerts, 27 informed entities, `effect` unset on all 24. */
class RtAlertsTest {

    private val alerts: RtAlerts get() = QueryTestData.rtAlerts

    @Test
    fun twentyFourAlertsTwentySevenInformedEntities() {
        assertEquals(1785775731L, alerts.headerTimestamp, "21 s behind the Trips/Vehicles header")
        assertEquals(24, alerts.alerts.size, "24 alerts")
        assertEquals(27, alerts.alerts.sumOf { it.informedRouteIds.size }, "27 informed entities, route_id on every one")
        assertEquals(2, alerts.alerts.sumOf { it.informedStopIds.size }, "stop_id present on exactly 2 selectors")
    }

    @Test
    fun effectIsNeverSet() {
        assertEquals(0, alerts.alerts.count { it.effectSeen }, "effect unset on all 24 — classification cannot rely on it")
    }

    @Test
    fun textsAndPeriodsArePresent() {
        assertTrue(alerts.alerts.all { it.header.isNotEmpty() }, "every alert has a header text")
        assertTrue(alerts.alerts.all { it.activePeriods.isNotEmpty() }, "every alert has at least one active period")
    }
}
