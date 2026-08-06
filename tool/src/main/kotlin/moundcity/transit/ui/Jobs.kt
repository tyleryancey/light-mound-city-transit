package moundcity.transit.ui

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import java.time.Instant
import kotlinx.coroutines.delay
import moundcity.transit.data.Prefs
import moundcity.transit.data.RefreshRunner
import moundcity.transit.data.ScheduleFetcher

/**
 * Build plan 4.1: the daily schedule refresh. Every decision lives in the
 * tested RefreshRunner; this handler is transport, outcome mapping, and the
 * in-process index swap. Refused maps to Success on purpose — the refusal is
 * recorded for the user and retrying the same rejected zip cannot help;
 * tomorrow's run fetches whole again because no Last-Modified was stamped.
 */
@LightJob("schedule-refresh")
val scheduleRefresh: LightJobHandler = { ctx, _ ->
    val runner = RefreshRunner(fetch = ScheduleFetcher::fetch, sleep = { delay(it) })
    val outcome = runner.run(
        ctx.filesDir,
        { ctx.readAsset("index.bin") },
        Prefs(ctx.dataStore),
        Instant.now().epochSecond,
    )
    when (outcome) {
        is RefreshRunner.RunOutcome.Updated -> {
            AppGraph.reloadFromDisk(ctx)
            LightJobResult.Success()
        }
        RefreshRunner.RunOutcome.Unchanged -> LightJobResult.Success()
        RefreshRunner.RunOutcome.Revoked -> LightJobResult.Success()
        is RefreshRunner.RunOutcome.Refused -> LightJobResult.Success()
        RefreshRunner.RunOutcome.NeedsRetry -> LightJobResult.Retry
    }
}
