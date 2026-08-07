package moundcity.transit.core.gtfs

import java.io.Reader

/** Build plan 4.3/4.4: rebuild an index from a fetched feed, or refuse. */
object ScheduleRefresh {

    sealed interface Outcome {
        class Rebuilt(val indexBytes: ByteArray, val vanished: List<VanishedStop>) : Outcome
        class Rejected(val reason: String) : Outcome
    }

    data class VanishedStop(val code: Int, val oldName: String?)

    /**
     * One pipeline, bundled and refreshed alike: GtfsFeed.load's assertions
     * refuse a feed that breaks an encoding assumption, quoting the observed
     * value — the caller keeps the old index on Rejected. The vanished list
     * exists because stop_id stability across picks is unverified (M5); the
     * old index supplies the name the new one no longer knows.
     */
    fun rebuild(openEntry: (String) -> Reader, oldIndex: ScheduleIndex, savedCodes: List<Int>): Outcome =
        try {
            val feed = GtfsFeed.load(openEntry)
            val bytes = IndexWriter.build(feed).container()
            val newIndex = ScheduleIndex(bytes)
            val vanished = savedCodes.filter { newIndex.resolveStop(it) == null }.map { code ->
                VanishedStop(code, oldIndex.resolveStop(code)?.let { oldIndex.stopName(it) })
            }
            Outcome.Rebuilt(bytes, vanished)
        } catch (e: Exception) {
            Outcome.Rejected(e.message ?: e.toString())
        }
}
