package moundcity.transit.core.rt

import moundcity.transit.core.query.QueryTestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build plan 1.14: 127 fixes, and the four absent fields stay absent — a future
 * feed that starts sending bearing/speed/stop_id/current_stop_sequence should
 * trip a test, not go unnoticed.
 */
class RtVehiclesTest {

    private val vehicles: RtVehicles get() = QueryTestData.rtVehicles

    @Test
    fun oneHundredTwentySevenFixes() {
        assertEquals(1785775752L, vehicles.headerTimestamp, "byte-identical with the Trips header")
        assertEquals(127, vehicles.fixes.size, "127 bus positions")
    }

    @Test
    fun absentFieldsStayAbsent() {
        assertEquals(
            emptySet(),
            vehicles.forbiddenFieldsSeen,
            "bearing/speed/odometer/current_stop_sequence/stop_id/current_status must all be absent — presence means the feed changed shape",
        )
    }

    @Test
    fun fixesCarryStLouisCoordinatesAndLabels() {
        assertTrue(
            vehicles.fixes.all { it.latMicro in 38_000_000..39_100_000 && it.lonMicro in -90_800_000..-89_600_000 },
            "every fix is inside the metro area",
        )
        assertTrue(vehicles.fixes.all { it.label.length <= 42 }, "labels truncate at 42 chars (doc 01)")
        assertTrue(vehicles.fixes.all { it.tripId.isNotEmpty() && it.timestamp > 0 }, "every fix joins a trip and carries a time")
    }

    @Test
    fun goldenVehicleForTrip3407211() {
        val fix = vehicles.fixes.single { it.tripId == "3407211" }
        assertEquals(38_734_340, fix.latMicro, "the Approach golden's latitude, via float→double→trunc")
        assertEquals(-90_354_400, fix.lonMicro, "and longitude")
    }

    @Test
    fun theTripJoinConventionHasOneOwner() {
        // RT tripId is the decimal string of the static trip_id (the measured
        // 100% join) — fixByTripId owns that fact so no screen re-encodes it.
        val byTrip = vehicles.fixByTripId()
        assertEquals(127, byTrip.size, "every fix keys on its trip")
        assertEquals(38_734_340, byTrip[3407211]?.latMicro, "the golden fix resolves by Int trip id")
    }
}
