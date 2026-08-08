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
    private val lastModifiedKey = stringPreferencesKey("last_modified_header")
    private val refreshNoticeKey = stringPreferencesKey("refresh_notice")
    private val revokedKey = booleanPreferencesKey("source_revoked")

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

    suspend fun lastModifiedHeader(): String? = optString(lastModifiedKey)
    suspend fun setLastModifiedHeader(value: String?) = store.edit { it[lastModifiedKey] = value.orEmpty() }

    suspend fun refreshNotice(): String? = optString(refreshNoticeKey)
    suspend fun setRefreshNotice(value: String?) = store.edit { it[refreshNoticeKey] = value.orEmpty() }

    /** Empty-string-as-absent, the file's one nullable-string convention. */
    private suspend fun optString(key: Preferences.Key<String>) = store.data.first()[key]?.ifEmpty { null }

    suspend fun sourceRevoked(): Boolean = store.data.first()[revokedKey] ?: false
    suspend fun setSourceRevoked(value: Boolean) = store.edit { it[revokedKey] = value }

    /** Bad tokens drop rather than throw (review finding, Phase 2): a single
     * corrupted byte must never brick the class that holds all mutable state —
     * the next write persists a clean string, completing the self-heal. */
    private fun parseStops(raw: String?): List<Int> =
        raw?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
}
