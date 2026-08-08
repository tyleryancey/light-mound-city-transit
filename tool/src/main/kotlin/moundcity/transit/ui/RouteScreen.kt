package moundcity.transit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moundcity.transit.core.query.BrowseCatalog
import moundcity.transit.core.query.ContextRoutes
import moundcity.transit.core.query.DataAge
import moundcity.transit.core.query.GlyphHitTest
import moundcity.transit.core.query.RouteBearing
import moundcity.transit.core.query.RouteLabels
import moundcity.transit.core.query.RowFormat
import moundcity.transit.core.query.ScaleBar
import moundcity.transit.core.query.ShapeProjection
import moundcity.transit.core.query.Viewport
import moundcity.transit.core.rt.RtVehicle

/** The canvas inset, shared by the drawing and the hit-test so both project
 *  through exactly the same transform. */
private const val CANVAS_PAD = 24f

/** A finger is wider than a 7px circle; the tap target is not the glyph. */
private const val TAP_RADIUS = 60f

class RouteViewModel(private val routeIdx: Int) : ReloadingViewModel() {

    data class ViewerState(
        val shape: IntArray?,
        val stopIdxs: List<Int>,
        val contextShapes: List<IntArray>,
        val vehicles: List<RtVehicle>,
        val isRail: Boolean,
        val liveLine: String?,
        val headsign: String?,
        val bearing: String?,
    )

    /** What a tap on the canvas resolved to; the screen does the navigating. */
    sealed interface GlyphTap {
        class StopTap(val stopIdx: Int) : GlyphTap
        class VehicleTap(val tripIdx: Int, val fromSeq: Int, val minute: Int) : GlyphTap
    }

    val direction = MutableStateFlow(0)
    val state = MutableStateFlow<ViewerState?>(null)
    val expired = MutableStateFlow(false)
    val viewport = MutableStateFlow<Viewport?>(null)
    val scaleLabel = MutableStateFlow<String?>(null)

    var onGlyphTap: ((GlyphTap) -> Unit)? = null

    /** Everything here is a pure function of the static index — computing it
     *  is a full departures-section pass, so a direction toggle or a vehicle
     *  refresh must not pay it again. Invalidated when the index swaps. */
    private class Geo(
        val shape: IntArray?,
        val stopIdxs: List<Int>,
        val contextShapes: List<IntArray>,
        val headsign: String?,
        val bearing: String?,
    )

    private val geometry = HashMap<Int, Geo>()
    private var geometryGeneration = -1

    /** A cached-direction reload finishes in microseconds while an uncached
     *  one pays the full scan — without this guard a quick double-toggle lets
     *  the slow, stale reload write state last. */
    private val loadGeneration = java.util.concurrent.atomic.AtomicInteger()

    @Volatile private var canvasW = 0f
    @Volatile private var canvasH = 0f

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    fun toggleDirection() {
        direction.value = 1 - direction.value
        reload()
    }

    fun canvasSized(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        synchronized(this) {
            if (canvasW == width && canvasH == height) return
            canvasW = width
            canvasH = height
        }
        viewport.value = Viewport(width, height)
        recomputeScale()
    }

    fun gesture(centroidX: Float, centroidY: Float, panDX: Float, panDY: Float, zoomChange: Float) {
        val current = viewport.value ?: return
        viewport.value = current.transformed(centroidX, centroidY, panDX, panDY, zoomChange)
        recomputeScale()
    }

    fun resetViewport() {
        viewport.value = viewport.value?.reset()
        recomputeScale()
    }

    /** Synchronized: called from the reload coroutine and from gestures on
     *  main. The drawn bar is always computed fresh in the draw, so only this
     *  label could go stale — serializing keeps it honest, and reading the
     *  size as a pair under the lock rules out a torn width/height. */
    @Synchronized
    private fun recomputeScale() {
        val shape = state.value?.shape
        val w = canvasW
        val h = canvasH
        if (shape == null || w <= 0f || h <= 0f) {
            scaleLabel.value = null
            return
        }
        val proj = ShapeProjection.fit(shape, w, h, CANVAS_PAD)
        val zoom = viewport.value?.zoom ?: 1f
        scaleLabel.value = ScaleBar.pick(proj.metersPerPixel / zoom, w * 0.5f)?.label
    }

    override fun reload() {
        val gen = loadGeneration.incrementAndGet()
        val dir = direction.value
        viewModelScope.launch(Dispatchers.IO) {
            val index = AppGraph.index
            val now = Instant.now()
            val isExpired = DataAge.isExpired(index, now, CHICAGO)
            if (gen != loadGeneration.get()) return@launch
            expired.value = isExpired
            if (isExpired) { state.value = null; return@launch }
            val isRail = RouteLabels.isRail(index, routeIdx)
            val live = AppGraph.liveSnapshot(now.epochSecond)
            val fixes = if (isRail || live == null) emptyList() else {
                live.vehicles.fixes.filter { fix ->
                    val t = index.tripIndexOf(fix.tripId.toIntOrNull() ?: return@filter false)
                    t != null && index.tripRoute(t) == routeIdx && index.tripDirection(t) == dir
                }
            }
            val indexGen = AppGraph.indexGeneration
            val cached = synchronized(geometry) {
                if (geometryGeneration != indexGen) { geometry.clear(); geometryGeneration = indexGen }
                geometry[dir]
            }
            val geo = cached ?: buildGeo(index, dir).also {
                synchronized(geometry) { if (geometryGeneration == indexGen) geometry[dir] = it }
            }
            if (gen != loadGeneration.get()) return@launch
            state.value = ViewerState(
                shape = geo.shape,
                stopIdxs = geo.stopIdxs,
                contextShapes = geo.contextShapes,
                vehicles = fixes,
                isRail = isRail,
                liveLine = live?.let { DataAge.liveLine(now.epochSecond, it.vehicles.headerTimestamp) },
                headsign = geo.headsign,
                bearing = geo.bearing,
            )
            recomputeScale()
        }
    }

    private fun buildGeo(index: moundcity.transit.core.gtfs.ScheduleIndex, dir: Int): Geo {
        val trip = BrowseCatalog.representativeTrip(index, routeIdx, dir)
        val shape = index.routeShape(routeIdx, dir)
        return Geo(
            shape = shape,
            stopIdxs = trip?.let { index.tripStops(it, fromSeq = 0).map { s -> s.stopIdx }.distinct() } ?: emptyList(),
            contextShapes = ContextRoutes.select(index, routeIdx, dir),
            headsign = trip?.let { index.headsign(index.tripHeadsign(it)) },
            bearing = shape?.let { RouteBearing.of(it) },
        )
    }

    /** Resolve a canvas tap through the same projection the draw used. */
    fun tapAt(sx: Float, sy: Float) {
        val s = state.value ?: return
        val v = viewport.value ?: return
        val shape = s.shape ?: return
        val index = AppGraph.index
        val proj = ShapeProjection.fit(shape, v.width, v.height, CANVAS_PAD)
        val n = s.stopIdxs.size + s.vehicles.size
        if (n == 0) return
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        s.stopIdxs.forEachIndexed { i, stop ->
            val (fx, fy) = proj.project(index.stopLatMicro(stop), index.stopLonMicro(stop))
            xs[i] = v.x(fx); ys[i] = v.y(fy)
        }
        s.vehicles.forEachIndexed { i, veh ->
            val (fx, fy) = proj.project(veh.latMicro, veh.lonMicro)
            xs[s.stopIdxs.size + i] = v.x(fx); ys[s.stopIdxs.size + i] = v.y(fy)
        }
        val hit = GlyphHitTest.nearest(xs, ys, sx, sy, TAP_RADIUS) ?: return
        if (hit < s.stopIdxs.size) {
            onGlyphTap?.invoke(GlyphTap.StopTap(s.stopIdxs[hit]))
            return
        }
        // A vehicle opens its whole run — fromSeq 0 keeps every stop, and the
        // cached first minute titles it. Deliberately NOT tripStops(): this
        // runs on the tap (main) thread and that is a full-section scan.
        val veh = s.vehicles[hit - s.stopIdxs.size]
        val tripIdx = veh.tripId.toIntOrNull()?.let { index.tripIndexOf(it) } ?: return
        onGlyphTap?.invoke(GlyphTap.VehicleTap(tripIdx, fromSeq = 0, minute = index.tripFirstMinute(tripIdx)))
    }
}

/**
 * The schematic route viewer (D12; D13 orientation and interaction): context
 * routes and a mile graticule behind the viewed polyline, hollow circles for
 * stops and filled squares for transit centers, filled dots for buses, a
 * north arrow and scale bar, pinch/drag zoom with double-tap reset, and every
 * glyph tappable. Still no basemap and never the rider's own position.
 */
class RouteScreen(sealedActivity: SealedLightActivity, private val routeIdx: Int) :
    LightScreen<Unit, RouteViewModel>(sealedActivity) {

    override val viewModelClass: Class<RouteViewModel> get() = RouteViewModel::class.java
    override fun createViewModel(): RouteViewModel = RouteViewModel(routeIdx)

    @Composable
    override fun Content() {
        val index = AppGraph.index
        val state by viewModel.state.collectAsState()
        val dir by viewModel.direction.collectAsState()
        val expired by viewModel.expired.collectAsState()
        val viewport by viewModel.viewport.collectAsState()
        val scaleLabel by viewModel.scaleLabel.collectAsState()

        viewModel.onGlyphTap = { tap ->
            when (tap) {
                is RouteViewModel.GlyphTap.StopTap ->
                    navigateTo({ sa -> DeparturesScreen(sa, tap.stopIdx) })
                is RouteViewModel.GlyphTap.VehicleTap ->
                    navigateTo({ sa -> TripDetailScreen(sa, tap.tripIdx, tap.fromSeq, tap.minute) })
            }
        }

        MctPage(
            title = "${RouteLabels.displayShortName(index, routeIdx)}  ${index.routeLongName(routeIdx)}",
            onBack = { goBack() },
        ) {
            LazyColumn {
                if (expired) {
                    // D9: expiry REPLACES the viewer, same as the departures list
                    item { ExpiredNotice() }
                    return@LazyColumn
                }
                item {
                    MctRow(
                        primary = directionLine(state, dir),
                        secondary = state?.let { s ->
                            if (s.isRail) "scheduled — no live train positions"
                            else s.liveLine?.let { "${RowFormat.counted(s.vehicles.size, "vehicle")} · $it" }
                                ?: "no live data — refresh below"
                        },
                        onTap = { viewModel.toggleDirection() },
                    )
                }
                item {
                    val s = state
                    if (s?.shape != null) {
                        val stroke = LightThemeTokens.colors.content
                        val faint = LightThemeTokens.colors.contentSecondary
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                // The context layer extends well past the viewed
                                // route's bbox, and Canvas does not clip on its
                                // own — without this it paints over the whole
                                // screen (found on device).
                                .clipToBounds()
                                .onSizeChanged { viewModel.canvasSized(it.width.toFloat(), it.height.toFloat()) }
                                // Keyed on Unit, not the state: ViewerState's
                                // liveLine changes on every reload, and a
                                // changing key cancels and relaunches both
                                // detectors mid-gesture (review finding). The
                                // handlers read fresh VM state anyway.
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { viewModel.resetViewport() },
                                        onTap = { at -> viewModel.tapAt(at.x, at.y) },
                                    )
                                }
                                // Keyed on Unit, not the state: ViewerState's
                                // liveLine changes on every reload, and a
                                // changing key cancels and relaunches both
                                // detectors mid-gesture (review finding). The
                                // handlers read fresh VM state anyway.
                                .pointerInput(Unit) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        viewModel.gesture(centroid.x, centroid.y, pan.x, pan.y, zoom)
                                    }
                                },
                        ) {
                            val v = viewport ?: Viewport(size.width, size.height)
                            val proj = ShapeProjection.fit(s.shape, size.width, size.height, CANVAS_PAD)
                            fun px(latMicro: Int, lonMicro: Int): Offset {
                                val (fx, fy) = proj.project(latMicro, lonMicro)
                                return Offset(v.x(fx), v.y(fy))
                            }
                            drawGraticule(proj.metersPerPixel, v, faint)
                            for (ctx in s.contextShapes) drawShape(ctx, faint, 1.5f, ::px)
                            drawShape(s.shape, stroke, 3f, ::px)
                            for (stop in s.stopIdxs) {
                                val at = px(index.stopLatMicro(stop), index.stopLonMicro(stop))
                                if (BrowseCatalog.isTransitCenter(index.stopName(stop))) {
                                    drawRect(stroke, topLeft = Offset(at.x - 8f, at.y - 8f), size = Size(16f, 16f))
                                } else {
                                    drawCircle(stroke, radius = 7f, center = at, style = Stroke(width = 3f))
                                }
                            }
                            for (veh in s.vehicles) drawCircle(stroke, radius = 9f, center = px(veh.latMicro, veh.lonMicro))
                            drawNorthArrow(stroke)
                            ScaleBar.pick(proj.metersPerPixel / v.zoom, size.width * 0.5f)?.let { drawScaleBar(it.widthPx, stroke) }
                        }
                    }
                }
                item {
                    MctRow(
                        primary = "○ stop · ■ center · ● bus",
                        secondary = listOfNotNull(
                            "north is up",
                            scaleLabel?.let { "bar = $it" },
                            "pinch to zoom · double-tap to reset",
                        ).joinToString(" · "),
                    )
                }
                if (state?.isRail == false) {
                    item { MctRow(primary = "↻ Refresh vehicles", onTap = { viewModel.refresh() }) }
                }
                item { MctRow(primary = "— stops —") }
                items(state?.stopIdxs ?: emptyList()) { stop ->
                    MctRow(
                        primary = stopLabel(index, stop),
                        onTap = { navigateTo({ sa -> DeparturesScreen(sa, stop) }) },
                    )
                }
            }
        }
    }

    /** Headsigns are feed data rendered verbatim — and Metro's already begin
     *  "TO …", so no preposition of ours goes in front of them. Four routes
     *  have geometry for one direction only; there the fallback must still
     *  name the direction the user is actually on (review finding). */
    private fun directionLine(state: RouteViewModel.ViewerState?, dir: Int): String {
        val parts = listOfNotNull(state?.bearing, state?.headsign?.takeIf { it.isNotEmpty() })
        val head = if (parts.isEmpty()) "direction ${dir + 1} of 2" else parts.joinToString(" · ")
        return "$head — tap to switch"
    }
}

private fun DrawScope.drawShape(shape: IntArray, color: Color, width: Float, px: (Int, Int) -> Offset) {
    var prev: Offset? = null
    var i = 0
    while (i < shape.size) {
        val at = px(shape[i], shape[i + 1])
        prev?.let { drawLine(color, it, at, strokeWidth = width) }
        prev = at
        i += 2
    }
}

/** A one-mile reference grid — structure without cartography. Suppressed
 *  when the lines would crowd (zoomed out far enough that a mile is tiny). */
private fun DrawScope.drawGraticule(metersPerPixel: Double, v: Viewport, color: Color) {
    val milePx = (1609.344 / metersPerPixel).toFloat() * v.zoom
    if (milePx < 40f) return
    var gx = v.x(0f) % milePx
    while (gx < size.width) {
        if (gx >= 0f) drawLine(color, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
        gx += milePx
    }
    var gy = v.y(0f) % milePx
    while (gy < size.height) {
        if (gy >= 0f) drawLine(color, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        gy += milePx
    }
}

private fun DrawScope.drawNorthArrow(color: Color) {
    val x = size.width - 30f
    drawLine(color, Offset(x, 44f), Offset(x, 16f), strokeWidth = 3f)
    drawLine(color, Offset(x - 8f, 26f), Offset(x, 16f), strokeWidth = 3f)
    drawLine(color, Offset(x + 8f, 26f), Offset(x, 16f), strokeWidth = 3f)
}

private fun DrawScope.drawScaleBar(widthPx: Float, color: Color) {
    val y = size.height - 20f
    drawLine(color, Offset(20f, y), Offset(20f + widthPx, y), strokeWidth = 3f)
    drawLine(color, Offset(20f, y - 8f), Offset(20f, y + 8f), strokeWidth = 3f)
    drawLine(color, Offset(20f + widthPx, y - 8f), Offset(20f + widthPx, y + 8f), strokeWidth = 3f)
}
