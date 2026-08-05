package moundcity.transit.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

    private fun <T> withPrefs(block: suspend (Prefs) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            return runBlocking { block(Prefs(store)) }
        } finally {
            scope.cancel()
            file.delete()
        }
    }

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
    fun expiryWarningFlagLatches() = withPrefs { prefs ->
        assertFalse(prefs.expiryWarningSeen(), "unseen by default")
        prefs.markExpiryWarningSeen()
        assertTrue(prefs.expiryWarningSeen(), "latched")
    }
}
