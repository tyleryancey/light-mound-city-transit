package moundcity.transit.data

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import moundcity.transit.core.gtfs.ScheduleRefresh

/**
 * The daily refresh state machine (doc 03 §5's pipeline diagram). Pure JVM:
 * the fetcher and sleeper are injected, so every path — 304, revocation,
 * retry-then-give-up, rebuild, refusal, vanished stops — runs under test
 * with no network and no clock.
 */
class RefreshRunner(
    private val fetch: (String?) -> ScheduleFetcher.Result,
    private val sleep: suspend (Long) -> Unit,
) {

    sealed interface RunOutcome {
        class Updated(val indexName: String) : RunOutcome
        object Unchanged : RunOutcome
        object Revoked : RunOutcome
        class Refused(val reason: String) : RunOutcome
        object NeedsRetry : RunOutcome
    }

    suspend fun run(filesDir: File, asset: () -> ByteArray, prefs: Prefs, nowEpoch: Long): RunOutcome {
        prefs.setLastRefreshAttempt(nowEpoch)
        var attempt = 0
        while (true) {
            when (val result = fetch(prefs.lastModifiedHeader())) {
                is ScheduleFetcher.Result.Fresh ->
                    return rebuildFrom(result, filesDir, asset, prefs, nowEpoch)
                ScheduleFetcher.Result.NotModified -> {
                    prefs.setSourceRevoked(false)
                    prefs.setLastRefreshSuccess(nowEpoch)
                    return RunOutcome.Unchanged
                }
                ScheduleFetcher.Result.Revoked -> {
                    prefs.setSourceRevoked(true)
                    return RunOutcome.Revoked
                }
                is ScheduleFetcher.Result.Transient -> {
                    if (attempt >= RefreshPolicy.retryDelaysMs.size) return RunOutcome.NeedsRetry
                    sleep(RefreshPolicy.retryDelaysMs[attempt])
                    attempt++
                }
            }
        }
    }

    private suspend fun rebuildFrom(
        fresh: ScheduleFetcher.Result.Fresh,
        filesDir: File,
        asset: () -> ByteArray,
        prefs: Prefs,
        nowEpoch: Long,
    ): RunOutcome {
        // ZipFile needs random access by entry name; a temp file is the price.
        val tmpZip = File(filesDir, "feed.zip.tmp")
        try {
            tmpZip.writeBytes(fresh.zipBytes)
            val store = IndexStore(filesDir, asset)
            val old = store.load()
            val saved = prefs.savedStops()
            val outcome = try {
                ZipFile(tmpZip).use { zip ->
                    ScheduleRefresh.rebuild(
                        { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() },
                        old.index,
                        saved,
                    )
                }
            } catch (e: java.io.IOException) {
                // A 200 whose body is not a zip (captive portal, CDN error
                // page) or a disk failure — transient, never a dead job.
                return RunOutcome.NeedsRetry
            }
            return when (outcome) {
                is ScheduleRefresh.Outcome.Rebuilt -> {
                    val name = "index-${dateStamp(nowEpoch)}.bin"
                    store.writeAtomically(name, outcome.indexBytes)
                    fresh.lastModified?.let { prefs.setLastModifiedHeader(it) }
                    prefs.setLastRefreshSuccess(nowEpoch)
                    prefs.setSourceRevoked(false)
                    prefs.setRefreshNotice(noticeFor(outcome.vanished))
                    RunOutcome.Updated(name)
                }
                is ScheduleRefresh.Outcome.Rejected -> {
                    // No Last-Modified stamp: a refused zip must be refetched
                    // whole next run, never 304'd into permanence. But the 200
                    // that carried it proves the licence is back — the
                    // revocation banner must not outlive its cause.
                    prefs.setSourceRevoked(false)
                    prefs.setRefreshNotice("Schedule update refused: ${outcome.reason}. Keeping the current schedule.")
                    RunOutcome.Refused(outcome.reason)
                }
            }
        } finally {
            tmpZip.delete()
        }
    }

    private fun dateStamp(epochSeconds: Long): String =
        Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC).toLocalDate()
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    private fun noticeFor(vanished: List<ScheduleRefresh.VanishedStop>): String? {
        if (vanished.isEmpty()) return null
        val plural = if (vanished.size == 1) "" else "s"
        val listed = vanished.joinToString(", ") { v ->
            v.oldName?.let { "${v.code} ($it)" } ?: "${v.code}"
        }
        return "Saved stop$plural not in the new schedule: $listed"
    }
}
