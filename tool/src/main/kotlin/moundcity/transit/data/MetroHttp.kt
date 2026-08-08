package moundcity.transit.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** One identity and one timeout policy for every request to the agency —
 *  a version or contact change must never need two edits. */
object MetroHttp {

    const val USER_AGENT = "Mound City Transit/1.0.0 (Light Phone 3 tool; contact: tyleryancey5@gmail.com)"

    /** Doc 03 §5: 10 s connect/read everywhere; the call ceiling is the
     *  caller's — 30 s for realtime, 120 s for the 3.7 MB schedule zip. */
    fun client(callTimeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .build()
}
