package moundcity.transit.data

import moundcity.transit.core.rt.RtAlerts
import moundcity.transit.core.rt.RtDecoder
import moundcity.transit.core.rt.RtTrips
import moundcity.transit.core.rt.RtVehicles
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The realtime fetch, foreground only (D10): departures/viewer on tap, alerts
 * once per open when nothing is cached, all behind the 30 s floor. Fetches
 * whole deliberately — at the feed's 21–42 s republish cadence (M1), a ≥30 s
 * re-tap almost always finds new bytes, so conditional GETs would buy nothing.
 */
object RtFetcher {

    private const val BASE = "https://www.metrostlouis.org/RealTimeData"
    private const val UA = "Mound City Transit/1.0.0 (Light Phone 3 tool; contact: tyleryancey5@gmail.com)"

    data class Snapshot(val trips: RtTrips, val vehicles: RtVehicles, val alerts: RtAlerts, val fetchedEpoch: Long)

    // Doc 03 §5: 10 s timeouts; the 30 s ceiling means no refresh can outlive
    // the manual-refresh floor it sits behind (review finding, Phase 3).
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Throws on any network or decode failure; callers degrade to scheduled and say so. */
    fun fetchAll(nowEpoch: Long): Snapshot {
        val trips = RtDecoder.decodeTrips(get("$BASE/StlRealTimeTrips.pb"))
        val vehicles = RtDecoder.decodeVehicles(get("$BASE/StlRealTimeVehicles.pb"))
        val alerts = RtDecoder.decodeAlerts(get("$BASE/StlRealTimeAlerts.pb"))
        return Snapshot(trips, vehicles, alerts, nowEpoch)
    }

    private fun get(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
            resp.body!!.bytes()
        }
}
