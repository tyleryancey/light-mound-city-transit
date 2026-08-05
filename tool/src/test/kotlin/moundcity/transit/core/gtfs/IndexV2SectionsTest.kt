package moundcity.transit.core.gtfs

import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 1d additions to the container (v2): route_ids, service_ids, calendar,
 * calendar_dates. Discovered while building the join — the on-device board
 * cannot compute active services without calendar data, cannot join alerts or
 * detect Illinois routes without route_ids, and doc 03 §3's section table
 * simply omitted both (the off-device oracle re-reads the feed instead).
 */
class IndexV2SectionsTest {

    private val index: ScheduleIndex by lazy {
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        ScheduleIndex(IndexWriter.build(feed).container())
    }

    @Test
    fun routeIdsRoundTripInFileOrder() {
        assertEquals("19731B", index.routeId(index.routeIndexOf("19731B")!!), "rail ids with letter suffixes survive")
        assertEquals("19855", index.routeId(index.routeIndexOf("19855")!!), "the first Illinois route")
        assertEquals(null, index.routeIndexOf("99999"), "unknown route id resolves to nothing")
    }

    @Test
    fun serviceIdsAreTheSortedEight() {
        assertEquals(8, index.serviceCount, "eight service ids")
        assertEquals("319-T1", index.serviceId(0), "sorted lexicographically, matching trip_meta's serviceIdx")
        assertEquals("319-T2", index.serviceId(1), "the calendar_dates-only service is present")
        assertEquals("325-B1", index.serviceId(2), "weekday bus")
    }

    @Test
    fun activeServiceIdxsFromTheIndexMatchTheOracle() {
        assertEquals(
            setOf(0, 2),
            index.activeServiceIdxs(LocalDate.of(2026, 8, 3)),
            "Monday 2026-08-03: 319-T1 (0) + 325-B1 (2), the golden board's service set",
        )
        assertEquals(
            setOf(1, 3),
            index.activeServiceIdxs(LocalDate.of(2026, 8, 8)),
            "Saturday 2026-08-08: 319-T2 added by exception (idx 1), 325-B2 (idx 3); 325-T2 removed — the stl_gtfs_calendar oracle's exact answer",
        )
    }
}
