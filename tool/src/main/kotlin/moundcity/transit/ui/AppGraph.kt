package moundcity.transit.ui

import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import moundcity.transit.core.query.DataAge
import moundcity.transit.data.IndexStore
import moundcity.transit.data.Prefs
import moundcity.transit.data.RefreshPolicy
import moundcity.transit.data.RtFetcher
import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * The tool's single shared graph: the loaded index, prefs, the last realtime
 * snapshot, and the bundled reference JSON. Initialized on first screen show;
 * everything here rebuilds from durable storage after process death (doc 03).
 */
object AppGraph {

    @Volatile private var loaded: IndexStore.Loaded? = null
    @Volatile var prefs: Prefs? = null
        private set
    @Volatile var snapshot: RtFetcher.Snapshot? = null
        private set
    @Volatile var lastAttemptEpoch: Long = 0
        private set
    @Volatile var lastError: Boolean = false
        private set
    @Volatile var referenceJson: Map<String, String> = emptyMap()
        private set

    /** Everything above is plain fields; this is their change signal — bumped
     *  on refresh outcomes and index swaps ONLY. Prefs mutations rely on each
     *  screen's onScreenShow reload instead; a new mutation path must pick one
     *  of those two lanes deliberately. The footer is the collector. */
    val dataGeneration = MutableStateFlow(0)

    /** Bumped ONLY when the index swaps (reloadFromDisk) — the invalidation
     *  key for caches derived from the static index. dataGeneration also bumps
     *  on every realtime refresh, which would thrash those caches. */
    @Volatile var indexGeneration = 0
        private set

    @Volatile private var dailyJobEnqueued = false

    /** Once per process: UPDATE policy makes the re-enqueue idempotent anyway
     *  (doc 03 §5), but there is no reason to run a WorkManager transaction on
     *  every return to Home. */
    fun ensureDailyJob(ctx: SealedLightContext) {
        if (dailyJobEnqueued) return
        dailyJobEnqueued = true
        LightWork.enqueuePeriodic(ctx, "schedule-refresh", 24.hours)
    }

    val index: ScheduleIndex get() = loaded!!.index

    fun ensure(ctx: SealedLightContext) {
        // Each field guards itself: the refresh job can initialize `loaded`
        // in a UI-less process (reloadFromDisk), and a later ensure() must
        // still bring up prefs and the reference JSON — a single loaded-null
        // guard left them dead for the process's life (review, Phase 4).
        if (loaded == null || prefs == null) {
            synchronized(this) {
                if (loaded == null) {
                    loaded = IndexStore(ctx.filesDir, { ctx.readAsset("index.bin") }).load()
                }
                if (prefs == null) {
                    prefs = Prefs(ctx.dataStore)
                    referenceJson = mapOf(
                        "fares" to String(ctx.readAsset("fares.json"), Charsets.UTF_8),
                        "holidays" to String(ctx.readAsset("holidays.json"), Charsets.UTF_8),
                        "contacts" to String(ctx.readAsset("contacts.json"), Charsets.UTF_8),
                    )
                }
            }
        }
    }

    /** The refresh job swapped the on-disk index; make this process see it. */
    fun reloadFromDisk(ctx: SealedLightContext) {
        synchronized(this) {
            loaded = IndexStore(ctx.filesDir, { ctx.readAsset("index.bin") }).load()
        }
        indexGeneration++
        dataGeneration.value++
    }

    /** Manual refresh with the measured 30 s floor; false = floored, unchanged.
     *  Synchronized: two screens refreshing at once must not double-fetch. */
    @Synchronized
    fun refresh(nowEpoch: Long): Boolean {
        if (nowEpoch - lastAttemptEpoch < RefreshPolicy.MANUAL_REFRESH_FLOOR_SECONDS) return false
        lastAttemptEpoch = nowEpoch
        return try {
            snapshot = RtFetcher.fetchAll()
            lastError = false
            true
        } catch (e: Exception) {
            lastError = true
            false
        } finally {
            dataGeneration.value++
        }
    }

    /** The snapshot, unless it has aged to scheduled (15 min — build plan 3.8).
     *  Alert readers use raw [snapshot] on purpose: alert windows span years,
     *  so the 15-minute aging that guards departure times does not apply. */
    fun liveSnapshot(nowEpoch: Long): RtFetcher.Snapshot? =
        snapshot?.takeIf { !DataAge.liveIsStale(nowEpoch, it.trips.headerTimestamp) }
}
