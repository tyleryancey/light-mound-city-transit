package moundcity.transit.core.query

/**
 * D13 scale bar: the largest "nice" mile length whose bar fits the budget.
 * Null when even the shortest rung does not fit — a wrong bar is worse than
 * no bar on a screen whose whole point is honesty about what it shows.
 */
object ScaleBar {

    data class Bar(val label: String, val widthPx: Float)

    private const val METERS_PER_MILE = 1609.344

    private val LADDER = listOf(
        0.25 to "¼ mi",
        0.5 to "½ mi",
        1.0 to "1 mi",
        2.0 to "2 mi",
        5.0 to "5 mi",
        10.0 to "10 mi",
    )

    fun pick(metersPerPixel: Double, maxWidthPx: Float): Bar? {
        if (metersPerPixel <= 0.0 || maxWidthPx <= 0f) return null
        return LADDER.lastOrNull { (miles, _) -> miles * METERS_PER_MILE / metersPerPixel <= maxWidthPx }
            ?.let { (miles, label) -> Bar(label, (miles * METERS_PER_MILE / metersPerPixel).toFloat()) }
    }
}
