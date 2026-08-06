package moundcity.transit.ui

import com.thelightphone.sdk.SealedLightContext
import moundcity.transit.core.query.DataAge
import moundcity.transit.data.IndexStore
import moundcity.transit.data.Prefs
import moundcity.transit.data.RtFetcher
import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * The tool's single shared graph: the loaded index, prefs, the last realtime
 * snapshot, and the bundled reference JSON. Initialized on first screen show;
 * everything here rebuilds from durable storage after process death (doc 03).
 */
object AppGraph {

    private const val REFRESH_FLOOR_SECONDS = 30L

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

    /** Everything above is plain fields; this is their change signal. The
     *  footer collects it so a failed refresh is VISIBLE, not just recorded. */
    val dataGeneration = kotlinx.coroutines.flow.MutableStateFlow(0)

    val index: ScheduleIndex get() = loaded!!.index

    fun ensure(ctx: SealedLightContext) {
        if (loaded == null) {
            synchronized(this) {
                if (loaded == null) {
                    loaded = IndexStore(ctx.filesDir, { ctx.readAsset("index.bin") }).load()
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
        dataGeneration.value++
    }

    /** Manual refresh with the measured 30 s floor; false = floored, unchanged.
     *  Synchronized: two screens refreshing at once must not double-fetch. */
    @Synchronized
    fun refresh(nowEpoch: Long): Boolean {
        if (nowEpoch - lastAttemptEpoch < REFRESH_FLOOR_SECONDS) return false
        lastAttemptEpoch = nowEpoch
        return try {
            snapshot = RtFetcher.fetchAll(nowEpoch)
            lastError = false
            true
        } catch (e: Exception) {
            lastError = true
            false
        } finally {
            dataGeneration.value++
        }
    }

    /** The snapshot, unless it has aged to scheduled (15 min — build plan 3.8). */
    fun liveSnapshot(nowEpoch: Long): RtFetcher.Snapshot? =
        snapshot?.takeIf { !DataAge.liveIsStale(nowEpoch, it.trips.headerTimestamp) }
}
