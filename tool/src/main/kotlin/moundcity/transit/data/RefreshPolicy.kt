package moundcity.transit.data

/** Build plan 4.2/4.5: pure refresh policy — classification and backoff. */
object RefreshPolicy {

    enum class Kind { FRESH, NOT_MODIFIED, REVOKED, TRANSIENT }

    fun classify(httpCode: Int): Kind = when (httpCode) {
        200 -> Kind.FRESH
        304 -> Kind.NOT_MODIFIED
        // The licence is revocable (Terms); these are a state, not an error.
        403, 410 -> Kind.REVOKED
        else -> Kind.TRANSIENT
    }

    /** Doc 03 §5: two retries with rising backoff, then fail visibly. */
    val retryDelaysMs: LongArray = longArrayOf(2_000, 8_000)

    /** M1: the feed republishes every 21-42 s; a manual re-tap sooner than
     *  this buys nothing and costs the agency a request. */
    const val MANUAL_REFRESH_FLOOR_SECONDS = 30L
}
