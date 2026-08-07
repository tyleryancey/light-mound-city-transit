package moundcity.transit.core.gtfs

import java.io.Reader
import java.io.StringReader
import java.util.zip.ZipFile
import moundcity.transit.core.query.QueryTestData
import moundcity.transit.data.AssetPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Build plan 4.3/4.4: the on-device rebuild. A fetched zip becomes an index
 * through the SAME parse + assertions + writer as the bundled one — so a
 * clean rebuild of the fixture feed must reproduce the shipped asset byte
 * for byte, and a feed that breaks an assumption is refused with the
 * observed value, keeping the old index.
 */
class ScheduleRefreshTest {

    private fun opener(zip: ZipFile): (String) -> Reader =
        { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }

    @Test
    fun rebuildReproducesTheShippedIndexByteForByte() {
        ZipFile(FixturePaths.gtfsZip).use { zip ->
            val out = ScheduleRefresh.rebuild(opener(zip), QueryTestData.index, savedCodes = listOf(10624))
            val rebuilt = assertIs<ScheduleRefresh.Outcome.Rebuilt>(out, "the fixture feed rebuilds cleanly")
            val shipped = AssetPaths.assetsDir.resolve("index.bin").readBytes()
            assertEquals(shipped.size, rebuilt.indexBytes.size, "same container size as the shipped asset")
            assertTrue(rebuilt.indexBytes.contentEquals(shipped), "byte-identical to the shipped index — one pipeline, no drift")
            assertEquals(emptyList(), rebuilt.vanished, "every saved stop present in the feed survives")
        }
    }

    @Test
    fun vanishedNamesTheSavedStopTheNewScheduleLost() {
        ZipFile(FixturePaths.gtfsZip).use { zip ->
            val out = ScheduleRefresh.rebuild(opener(zip), QueryTestData.index, savedCodes = listOf(10624, 99999))
            val rebuilt = assertIs<ScheduleRefresh.Outcome.Rebuilt>(out)
            assertEquals(
                listOf(ScheduleRefresh.VanishedStop(99999, null)),
                rebuilt.vanished,
                "a saved stop missing from the new stop set is reported, with the old name when one exists (4.4)",
            )
        }
    }

    @Test
    fun aTamperedFeedIsRejectedQuotingTheObservedValue() {
        ZipFile(FixturePaths.gtfsZip).use { zip ->
            val tampered: (String) -> Reader = { name ->
                if (name == "stops.txt") {
                    StringReader(
                        "stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n" +
                            "100,999,FAKE STOP,38.6,-90.2,0\n",
                    )
                } else {
                    opener(zip)(name)
                }
            }
            val out = ScheduleRefresh.rebuild(tampered, QueryTestData.index, emptyList())
            val rejected = assertIs<ScheduleRefresh.Outcome.Rejected>(out, "an assumption break refuses the rebuild, old index kept (4.3)")
            assertEquals(
                "A1: stop_code != stop_id on 1 of 1 stops",
                rejected.reason,
                "the refusal quotes the observed value, like every assertion since 1.6",
            )
        }
    }
}
