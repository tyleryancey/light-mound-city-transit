package moundcity.transit.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * The tool's ENTIRE mutable state (doc 03 §3): saved stops, refresh
 * timestamps, the active index filename, and the expiry-warning latch.
 * Everything else rebuilds from the index and the network.
 */
class Prefs(private val store: DataStore<Preferences>) {

    private val savedStopsKey = stringPreferencesKey("saved_stops")
    private val lastAttemptKey = longPreferencesKey("last_refresh_attempt")
    private val lastSuccessKey = longPreferencesKey("last_refresh_success")
    private val activeIndexKey = stringPreferencesKey("active_index_name")
    private val expiryWarnedKey = booleanPreferencesKey("expiry_warning_seen")

    companion object {
        /** Doc 02: the saved-stops list is bounded at 12, by refusal not truncation. */
        const val MAX_SAVED_STOPS = 12
    }

    suspend fun savedStops(): List<Int> = parseStops(store.data.first()[savedStopsKey])

    /** False when the list is full or the code is already saved — never a silent drop. */
    suspend fun addSavedStop(code: Int): Boolean {
        var added = false
        store.edit { prefs ->
            val current = parseStops(prefs[savedStopsKey])
            if (current.size < MAX_SAVED_STOPS && code !in current) {
                prefs[savedStopsKey] = (current + code).joinToString(",")
                added = true
            }
        }
        return added
    }

    suspend fun removeSavedStop(code: Int) {
        store.edit { prefs ->
            prefs[savedStopsKey] = parseStops(prefs[savedStopsKey]).filter { it != code }.joinToString(",")
        }
    }

    suspend fun lastRefreshAttempt(): Long? = store.data.first()[lastAttemptKey]
    suspend fun setLastRefreshAttempt(epochSeconds: Long) = store.edit { it[lastAttemptKey] = epochSeconds }

    suspend fun lastRefreshSuccess(): Long? = store.data.first()[lastSuccessKey]
    suspend fun setLastRefreshSuccess(epochSeconds: Long) = store.edit { it[lastSuccessKey] = epochSeconds }

    suspend fun activeIndexName(): String? = store.data.first()[activeIndexKey]?.ifEmpty { null }
    suspend fun setActiveIndexName(name: String?) = store.edit { it[activeIndexKey] = name.orEmpty() }

    suspend fun expiryWarningSeen(): Boolean = store.data.first()[expiryWarnedKey] ?: false
    suspend fun markExpiryWarningSeen() = store.edit { it[expiryWarnedKey] = true }

    private fun parseStops(raw: String?): List<Int> =
        raw?.takeIf { it.isNotEmpty() }?.split(',')?.map { it.toInt() } ?: emptyList()
}
