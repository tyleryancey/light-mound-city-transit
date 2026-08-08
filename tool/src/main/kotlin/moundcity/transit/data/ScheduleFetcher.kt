package moundcity.transit.data

import okhttp3.Request

/**
 * The static-feed conditional GET (build plan 4.2). If-Modified-Since is the
 * whole data budget: the server ignores Accept-Encoding (correction 9), so an
 * unchanged feed costs a 304 and a changed one costs the full ~3.7 MB zip.
 * All decisions live in RefreshPolicy; this is transport only.
 */
object ScheduleFetcher {

    private const val URL = "https://www.metrostlouis.org/Transit/google_transit.zip"

    sealed interface Result {
        class Fresh(val zipBytes: ByteArray, val lastModified: String?) : Result
        object NotModified : Result
        object Revoked : Result
        class Transient(val detail: String) : Result
    }

    // The zip is ~3.7 MB on a phone-grade connection; bounded, not eternal.
    private val client = MetroHttp.client(callTimeoutSeconds = 120)

    fun fetch(ifModifiedSince: String?): Result = try {
        val request = Request.Builder().url(URL).header("User-Agent", MetroHttp.USER_AGENT)
            .apply { if (ifModifiedSince != null) header("If-Modified-Since", ifModifiedSince) }
            .build()
        client.newCall(request).execute().use { resp ->
            when (RefreshPolicy.classify(resp.code)) {
                RefreshPolicy.Kind.FRESH -> Result.Fresh(resp.body!!.bytes(), resp.header("Last-Modified"))
                RefreshPolicy.Kind.NOT_MODIFIED -> Result.NotModified
                RefreshPolicy.Kind.REVOKED -> Result.Revoked
                RefreshPolicy.Kind.TRANSIENT -> Result.Transient("HTTP ${resp.code}")
            }
        }
    } catch (e: java.io.IOException) {
        Result.Transient(e.message ?: e.toString())
    }
}
