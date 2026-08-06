package moundcity.transit.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build plan 2.4: DataStore is the ENTIRE mutable state — saved stops (≤12),
 * refresh timestamps, active index name, expiry-warning flag (doc 03 §3).
 * Runs on plain JVM via preferences-core over a temp file.
 */
class PrefsTest {

    private fun <T> withStoreAndPrefs(block: suspend (DataStore<Preferences>, Prefs) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            return runBlocking { block(store, Prefs(store)) }
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    private fun <T> withPrefs(block: suspend (Prefs) -> T): T =
        withStoreAndPrefs { _, prefs -> block(prefs) }

    @Test
    fun savedStopsKeepInsertionOrderAndCapAtTwelve() = withPrefs { prefs ->
        assertEquals(emptyList(), prefs.savedStops(), "starts empty")
        for (code in 100 until 112) assertTrue(prefs.addSavedStop(code), "the first twelve save")
        assertFalse(prefs.addSavedStop(999), "the thirteenth is refused, never silently dropped")
        assertEquals((100 until 112).toList(), prefs.savedStops(), "insertion order, all twelve")
        assertFalse(prefs.addSavedStop(105), "re-adding an existing stop is a no-op refusal")
        prefs.removeSavedStop(100)
        assertEquals((101 until 112).toList(), prefs.savedStops(), "removal preserves the rest")
        assertTrue(prefs.addSavedStop(999), "room again after removal")
    }

    @Test
    fun refreshTimestampsAndIndexNameRoundTrip() = withPrefs { prefs ->
        assertNull(prefs.lastRefreshAttempt(), "no attempt yet")
        assertNull(prefs.lastRefreshSuccess(), "no success yet")
        prefs.setLastRefreshAttempt(1_785_949_785L)
        prefs.setLastRefreshSuccess(1_785_949_800L)
        assertEquals(1_785_949_785L, prefs.lastRefreshAttempt(), "attempt epoch round-trips")
        assertEquals(1_785_949_800L, prefs.lastRefreshSuccess(), "success epoch round-trips")

        assertNull(prefs.activeIndexName(), "asset is the implicit default")
        prefs.setActiveIndexName("index-20260812.bin")
        assertEquals("index-20260812.bin", prefs.activeIndexName(), "active index name round-trips")
    }

    @Test
    fun corruptedSavedStopsTokenIsDroppedAndHealedOnNextWrite() = withStoreAndPrefs { store, prefs ->
        // Review finding (Phase 2): a single bad token bricked the whole class
        // with NumberFormatException — remove could never repair what it could
        // not parse. Bad tokens drop; the next write persists a clean string.
        store.edit { it[stringPreferencesKey("saved_stops")] = "10624,1x27,127" }
        assertEquals(listOf(10624, 127), prefs.savedStops(), "bad token dropped, not thrown")
        assertTrue(prefs.addSavedStop(999), "list still writable")
        assertEquals(
            "10624,127,999",
            store.data.first()[stringPreferencesKey("saved_stops")],
            "the next write persists a clean string — self-heal complete",
        )
    }

    @Test
    fun expiryWarningFlagLatches() = withPrefs { prefs ->
        assertFalse(prefs.expiryWarningSeen(), "unseen by default")
        prefs.markExpiryWarningSeen()
        assertTrue(prefs.expiryWarningSeen(), "latched")
    }

    @Test
    fun refreshBookkeepingRoundTrips() = withPrefs { prefs ->
        assertNull(prefs.lastModifiedHeader(), "no header before the first 200")
        prefs.setLastModifiedHeader("Wed, 05 Aug 2026 12:00:00 GMT")
        assertEquals(
            "Wed, 05 Aug 2026 12:00:00 GMT", prefs.lastModifiedHeader(),
            "If-Modified-Since replays the server's own stamp verbatim (M2)",
        )

        assertNull(prefs.refreshNotice(), "no notice until something needs saying")
        prefs.setRefreshNotice("Saved stop 9464 (GRAND AT SHENANDOAH) is not in the new schedule")
        assertEquals(
            "Saved stop 9464 (GRAND AT SHENANDOAH) is not in the new schedule",
            prefs.refreshNotice(),
            "the notice names the stop that vanished (4.4)",
        )
        prefs.setRefreshNotice(null)
        assertNull(prefs.refreshNotice(), "notices clear once resolved")

        assertFalse(prefs.sourceRevoked(), "not revoked by default")
        prefs.setSourceRevoked(true)
        assertTrue(prefs.sourceRevoked(), "403/410 is a remembered state, not a transient error (4.5)")
    }
}
