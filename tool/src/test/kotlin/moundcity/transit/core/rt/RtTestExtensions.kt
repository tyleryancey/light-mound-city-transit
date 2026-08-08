package moundcity.transit.core.rt

/** Feed-shape evidence helpers (doc 01 §5c) — test-only; production
 *  delayByTrip() is dedup-insensitive by construction. */

/** True when every STU appears exactly twice, pairwise adjacent. */
fun RtTripEntity.isFullyAdjacentDuplicated(): Boolean {
    if (stus.size < 2 || stus.size % 2 != 0) return false
    return (stus.indices step 2).all { stus[it] == stus[it + 1] }
}

/** Collapses adjacent duplicates; a no-op on clean trips. */
fun RtTripEntity.dedupedStus(): List<RtStu> {
    val out = ArrayList<RtStu>(stus.size)
    for (s in stus) if (out.isEmpty() || out.last() != s) out.add(s)
    return out
}
