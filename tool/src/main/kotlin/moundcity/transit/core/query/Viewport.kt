package moundcity.transit.core.query

/**
 * D13 zoom/pan over fitted canvas coordinates. Positions transform; paint
 * does not — stroke widths and glyph radii stay constant on screen. Pan is
 * clamped so the fitted rect always covers the canvas, and 1× snaps pan to
 * zero: fit-to-screen remains a reachable, exact state (double-tap reset).
 */
class Viewport private constructor(
    val width: Float,
    val height: Float,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
) {
    constructor(width: Float, height: Float) : this(width, height, 1f, 0f, 0f)

    val isIdentity: Boolean get() = zoom == 1f && panX == 0f && panY == 0f

    fun x(fittedX: Float): Float = fittedX * zoom + panX
    fun y(fittedY: Float): Float = fittedY * zoom + panY
    fun fromScreenX(sx: Float): Float = (sx - panX) / zoom
    fun fromScreenY(sy: Float): Float = (sy - panY) / zoom

    fun transformed(centroidX: Float, centroidY: Float, panDX: Float, panDY: Float, zoomChange: Float): Viewport {
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == MIN_ZOOM) return Viewport(width, height)
        // Hold the point under the centroid still while the scale changes,
        // then apply the drag, then clamp to the content edges.
        val scaleRatio = newZoom / zoom
        val newPanX = ((panX - centroidX) * scaleRatio + centroidX + panDX)
            .coerceIn(width - width * newZoom, 0f)
        val newPanY = ((panY - centroidY) * scaleRatio + centroidY + panDY)
            .coerceIn(height - height * newZoom, 0f)
        return Viewport(width, height, newZoom, newPanX, newPanY)
    }

    fun reset(): Viewport = Viewport(width, height)

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}
