package moundcity.transit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build plan 4.2/4.5: how a refresh reacts to what the server says. Pure
 * policy — the HTTP glue stays thin and untested, this is the part that
 * must not drift.
 */
class RefreshPolicyTest {

    @Test
    fun classifiesEveryCodeTheFeedCanReturn() {
        assertEquals(RefreshPolicy.Kind.FRESH, RefreshPolicy.classify(200), "200 carries a new zip")
        assertEquals(RefreshPolicy.Kind.NOT_MODIFIED, RefreshPolicy.classify(304), "If-Modified-Since replay works (M2)")
        assertEquals(RefreshPolicy.Kind.REVOKED, RefreshPolicy.classify(403), "the licence is revocable and 403 is how it says so (4.5)")
        assertEquals(RefreshPolicy.Kind.REVOKED, RefreshPolicy.classify(410), "410 Gone is the other revocation signal")
        assertEquals(RefreshPolicy.Kind.TRANSIENT, RefreshPolicy.classify(500), "server errors retry")
        assertEquals(RefreshPolicy.Kind.TRANSIENT, RefreshPolicy.classify(429), "rate limiting retries, later")
        assertEquals(RefreshPolicy.Kind.TRANSIENT, RefreshPolicy.classify(404), "anything else retries then fails visibly — never a permanent silent state")
    }

    @Test
    fun twoRetriesWithRisingBackoff() {
        assertEquals(2, RefreshPolicy.retryDelaysMs.size, "doc 03 §5: two retries with backoff, then fail to the bundled schedule")
        assertTrue(RefreshPolicy.retryDelaysMs[0] < RefreshPolicy.retryDelaysMs[1], "backoff rises between attempts")
    }
}
