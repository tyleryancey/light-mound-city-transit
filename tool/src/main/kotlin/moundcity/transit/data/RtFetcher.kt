package moundcity.transit.data

import java.io.IOException
import java.util.concurrent.CompletableFuture
import moundcity.transit.core.rt.RtAlerts
import moundcity.transit.core.rt.RtDecoder
import moundcity.transit.core.rt.RtTrips
import moundcity.transit.core.rt.RtVehicles
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * The realtime fetch, foreground only (D10): departures/viewer on tap, alerts
 * once per open when nothing is cached, all behind the 30 s floor. Fetches
 * whole deliberately — at the feed's 21–42 s republish cadence (M1), a ≥30 s
 * re-tap almost always finds new bytes, so conditional GETs would buy nothing.
 * The three files are independent; fetching them concurrently saves two full
 * round-trips on the hottest user action.
 */
object RtFetcher {

    private const val BASE = "https://www.metrostlouis.org/RealTimeData"

    data class Snapshot(val trips: RtTrips, val vehicles: RtVehicles, val alerts: RtAlerts)

    private val client = MetroHttp.client(callTimeoutSeconds = 30)

    /** Throws on any network or decode failure; callers degrade to scheduled and say so. */
    fun fetchAll(): Snapshot {
        val trips = getAsync("$BASE/StlRealTimeTrips.pb")
        val vehicles = getAsync("$BASE/StlRealTimeVehicles.pb")
        val alerts = getAsync("$BASE/StlRealTimeAlerts.pb")
        return Snapshot(
            trips = RtDecoder.decodeTrips(trips.join()),
            vehicles = RtDecoder.decodeVehicles(vehicles.join()),
            alerts = RtDecoder.decodeAlerts(alerts.join()),
        )
    }

    private fun getAsync(url: String): CompletableFuture<ByteArray> {
        val future = CompletableFuture<ByteArray>()
        val request = Request.Builder().url(url).header("User-Agent", MetroHttp.USER_AGENT).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // A body-read failure here is NOT redelivered to onFailure once
                // onResponse has been signalled (OkHttp only logs it) — complete
                // exceptionally ourselves or join() waits forever inside the
                // @Synchronized refresh monitor, wedging refresh process-wide.
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
                        future.complete(resp.body!!.bytes())
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
        })
        // Belt over the 30 s callTimeout: no code path may leave this future
        // incomplete, so no path may block refresh forever.
        return future.orTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
    }
}
