package moundcity.transit.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moundcity.transit.core.gtfs.FixturePaths
import moundcity.transit.core.gtfs.ScheduleIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build plan 4.1–4.5: every arrow of the doc 03 §5 pipeline diagram, driven
 * by a scripted fetcher — no network, no real sleeps.
 */
class RefreshRunnerTest {

    private val asset: () -> ByteArray = { AssetPaths.assetsDir.resolve("index.bin").readBytes() }

    private fun withHarness(
        results: List<ScheduleFetcher.Result>,
        block: suspend (RefreshRunner, File, Prefs, MutableList<Long>) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dir = kotlin.io.path.createTempDirectory("refresh").toFile()
        val prefsFile = File(dir, "prefs.preferences_pb")
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { prefsFile }
            val prefs = Prefs(store)
            val queue = ArrayDeque(results)
            val sleeps = mutableListOf<Long>()
            val runner = RefreshRunner(
                fetch = { queue.removeFirst() },
                sleep = { sleeps.add(it) },
            )
            runBlocking { block(runner, dir, prefs, sleeps) }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aThreeOhFourStampsSuccessAndTouchesNothing() = withHarness(
        listOf(ScheduleFetcher.Result.NotModified),
    ) { runner, dir, prefs, sleeps ->
        val out = runner.run(dir, asset, prefs, nowEpoch = 1_786_300_000)
        assertIs<RefreshRunner.RunOutcome.Unchanged>(out, "304 is the cheap, common case (M2)")
        assertEquals(1_786_300_000, prefs.lastRefreshSuccess(), "an unchanged feed is still a successful check")
        assertEquals(emptyList(), sleeps, "no retries on a clean 304")
        assertFalse(prefs.sourceRevoked(), "and it clears any stale revocation")
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("index-") }, "no index written for a 304")
    }

    @Test
    fun transientFailuresRetryTwiceThenGiveUp() = withHarness(
        listOf(
            ScheduleFetcher.Result.Transient("HTTP 500"),
            ScheduleFetcher.Result.Transient("timeout"),
            ScheduleFetcher.Result.Transient("HTTP 502"),
        ),
    ) { runner, dir, prefs, sleeps ->
        val out = runner.run(dir, asset, prefs, nowEpoch = 100)
        assertIs<RefreshRunner.RunOutcome.NeedsRetry>(out, "after two backoffs the job hands retry to WorkManager")
        assertEquals(RefreshPolicy.retryDelaysMs.toList(), sleeps, "exactly the policy's delays, in order")
        assertNull(prefs.lastRefreshSuccess(), "a failed check never claims success")
    }

    @Test
    fun aRetryThatRecoversIsAnOrdinarySuccess() = withHarness(
        listOf(ScheduleFetcher.Result.Transient("HTTP 500"), ScheduleFetcher.Result.NotModified),
    ) { runner, dir, prefs, sleeps ->
        val out = runner.run(dir, asset, prefs, nowEpoch = 100)
        assertIs<RefreshRunner.RunOutcome.Unchanged>(out, "one bad response then a 304 is fine")
        assertEquals(listOf(RefreshPolicy.retryDelaysMs[0]), sleeps, "only the first backoff was needed")
    }

    @Test
    fun revocationIsRememberedAndAlaterCheckClearsIt() = withHarness(
        listOf(ScheduleFetcher.Result.Revoked, ScheduleFetcher.Result.NotModified),
    ) { runner, dir, prefs, _ ->
        assertIs<RefreshRunner.RunOutcome.Revoked>(runner.run(dir, asset, prefs, 100), "403/410 is its own state (4.5)")
        assertTrue(prefs.sourceRevoked(), "remembered across process death")
        assertIs<RefreshRunner.RunOutcome.Unchanged>(runner.run(dir, asset, prefs, 200), "the licence can come back")
        assertFalse(prefs.sourceRevoked(), "and the state clears when it does")
    }

    @Test
    fun aFreshZipBecomesTheActiveIndex() = withHarness(
        listOf(ScheduleFetcher.Result.Fresh(FixturePaths.gtfsZip.readBytes(), "Wed, 05 Aug 2026 12:00:00 GMT")),
    ) { runner, dir, prefs, _ ->
        prefs.addSavedStop(10624)
        val out = runner.run(dir, asset, prefs, nowEpoch = 1_786_300_000)
        val updated = assertIs<RefreshRunner.RunOutcome.Updated>(out, "a 200 rebuilds")
        assertTrue(updated.indexName.startsWith("index-") && updated.indexName.endsWith(".bin"), "date-stamped name; got ${updated.indexName}")
        val written = File(dir, updated.indexName)
        assertTrue(written.isFile, "the index landed on disk atomically")
        assertEquals(5118, run { var n = 0; ScheduleIndex(written.readBytes()).let { n = it.stopCount }; n }, "and it parses to the full stop set")
        assertEquals(updated.indexName, prefs.activeIndexName(), "recorded as active")
        assertEquals("Wed, 05 Aug 2026 12:00:00 GMT", prefs.lastModifiedHeader(), "the server's stamp feeds the next If-Modified-Since")
        assertEquals(1_786_300_000, prefs.lastRefreshSuccess(), "success stamped")
        assertNull(prefs.refreshNotice(), "no notice when every saved stop survived")
        assertTrue(File(dir, "feed.zip.tmp").exists().not(), "the temp zip is cleaned up")
    }

    @Test
    fun aVanishedSavedStopIsNamedInTheNotice() = withHarness(
        listOf(ScheduleFetcher.Result.Fresh(FixturePaths.gtfsZip.readBytes(), null)),
    ) { runner, dir, prefs, _ ->
        prefs.addSavedStop(10624)
        prefs.addSavedStop(99999)
        assertIs<RefreshRunner.RunOutcome.Updated>(runner.run(dir, asset, prefs, 100))
        assertEquals(
            "Saved stop not in the new schedule: 99999",
            prefs.refreshNotice(),
            "the diff names what vanished (4.4) — the worst failure mode is a silent one",
        )
    }

    @Test
    fun aTamperedZipIsRefusedAndTheOldIndexStays() {
        val tamperedZip = buildTamperedZip()
        withHarness(
            listOf(ScheduleFetcher.Result.Fresh(tamperedZip, "Wed, 05 Aug 2026 12:00:00 GMT")),
        ) { runner, dir, prefs, _ ->
            prefs.setActiveIndexName("index-20260805.bin")
            val out = runner.run(dir, asset, prefs, nowEpoch = 100)
            val refused = assertIs<RefreshRunner.RunOutcome.Refused>(out, "an assumption break refuses the swap (4.3)")
            assertEquals("A1: stop_code != stop_id on 1 of 1 stops", refused.reason, "quoting the observed value")
            assertEquals(
                "Schedule update refused: A1: stop_code != stop_id on 1 of 1 stops. Keeping the current schedule.",
                prefs.refreshNotice(),
                "surfaced, not swallowed",
            )
            assertEquals("index-20260805.bin", prefs.activeIndexName(), "the old index stays active")
            assertNull(prefs.lastModifiedHeader(), "a refused zip must be refetched next time, not 304'd away")
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("index-") }, "nothing new written")
        }
    }

    @Test
    fun aCaptivePortalTwoHundredIsTransientNotACrash() = withHarness(
        listOf(ScheduleFetcher.Result.Fresh("<html>sign in to airport wifi</html>".toByteArray(), "Wed, 05 Aug 2026 12:00:00 GMT")),
    ) { runner, dir, prefs, _ ->
        val out = runner.run(dir, asset, prefs, nowEpoch = 100)
        assertIs<RefreshRunner.RunOutcome.NeedsRetry>(out, "a 200 that is not a zip is a transient condition, never a dead job")
        assertNull(prefs.refreshNotice(), "nothing was refused — no notice")
        assertNull(prefs.lastModifiedHeader(), "the portal's stamp is never recorded")
        assertTrue(
            dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.name.startsWith("index-") },
            "no index written and no stranded temp zip",
        )
    }

    @Test
    fun aRefusedZipStillProvesTheFeedIsBack() = withHarness(
        listOf(ScheduleFetcher.Result.Revoked, ScheduleFetcher.Result.Fresh(buildTamperedZip(), null)),
    ) { runner, dir, prefs, _ ->
        assertIs<RefreshRunner.RunOutcome.Revoked>(runner.run(dir, asset, prefs, 100))
        assertTrue(prefs.sourceRevoked(), "day 1: 403 remembered")
        assertIs<RefreshRunner.RunOutcome.Refused>(runner.run(dir, asset, prefs, 200))
        assertFalse(
            prefs.sourceRevoked(),
            "a 200 — even one whose zip is refused — proves the licence is back; the two banners must not contradict",
        )
    }

    /** The fixture zip with stops.txt replaced by one row violating A1. */
    private fun buildTamperedZip(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipFile(FixturePaths.gtfsZip).use { zip ->
                for (entry in zip.entries()) {
                    zos.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "stops.txt") {
                        zos.write(
                            ("stop_id,stop_code,stop_name,stop_lat,stop_lon,wheelchair_boarding\n" +
                                "100,999,FAKE STOP,38.6,-90.2,0\n").toByteArray(),
                        )
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
