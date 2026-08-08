package moundcity.transit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
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
import moundcity.transit.core.query.DataAge
import moundcity.transit.core.query.RouteLabels
import moundcity.transit.core.query.RowFormat
import moundcity.transit.core.query.ShapeProjection
import moundcity.transit.core.query.BrowseCatalog
import moundcity.transit.core.rt.RtVehicle

class RouteViewModel(private val routeIdx: Int) : ReloadingViewModel() {

    data class ViewerState(
        val shape: IntArray?,
        val stopIdxs: List<Int>,
        val vehicles: List<RtVehicle>,
        val isRail: Boolean,
        val liveLine: String?,
    )

    val direction = MutableStateFlow(0)
    val state = MutableStateFlow<ViewerState?>(null)
    val expired = MutableStateFlow(false)

    /** Shape and stop list are pure functions of the static index — computing
     *  them is a full departures-section pass, so a direction toggle or a
     *  vehicle refresh must not pay it again. Keyed by direction. */
    private val geometry = HashMap<Int, Pair<IntArray?, List<Int>>>()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    fun toggleDirection() {
        direction.value = 1 - direction.value
        reload()
    }

    override fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val index = AppGraph.index
            val now = Instant.now()
            expired.value = DataAge.isExpired(index, now, CHICAGO)
            if (expired.value) { state.value = null; return@launch }
            val dir = direction.value
            val isRail = RouteLabels.isRail(index, routeIdx)
            val live = AppGraph.liveSnapshot(now.epochSecond)
            val fixes = if (isRail || live == null) emptyList() else {
                live.vehicles.fixes.filter { fix ->
                    val t = index.tripIndexOf(fix.tripId.toIntOrNull() ?: return@filter false)
                    t != null && index.tripRoute(t) == routeIdx && index.tripDirection(t) == dir
                }
            }
            val (shape, stops) = geometry.getOrPut(dir) {
                index.routeShape(routeIdx, dir) to BrowseCatalog.routeStops(index, routeIdx, dir)
            }
            state.value = ViewerState(
                shape = shape,
                stopIdxs = stops,
                vehicles = fixes,
                isRail = isRail,
                liveLine = live?.let { DataAge.liveLine(now.epochSecond, it.vehicles.headerTimestamp) },
            )
        }
    }
}

/**
 * The schematic route viewer (D12, build plan 3.11): Canvas polyline + hollow
 * stop circles + filled vehicle squares. Fit-to-screen, direction toggle,
 * no pan/zoom, no basemap, never the user's position.
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
                        primary = "direction ${dir + 1} of 2 — tap to switch",
                        secondary = state?.let { s ->
                            if (s.isRail) "scheduled — no live train positions"
                            else s.liveLine?.let { "${RowFormat.counted(s.vehicles.size, "vehicle")} · $it" } ?: "no live data — refresh below"
                        },
                        onTap = { viewModel.toggleDirection() },
                    )
                }
                item {
                    val s = state
                    if (s?.shape != null) {
                        val stroke = LightThemeTokens.colors.content
                        Canvas(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                            val proj = ShapeProjection.fit(s.shape, size.width, size.height, pad = 24f)
                            var i = 0
                            while (i < proj.pointCount - 1) {
                                drawLine(
                                    color = stroke,
                                    start = Offset(proj.x(i), proj.y(i)),
                                    end = Offset(proj.x(i + 1), proj.y(i + 1)),
                                    strokeWidth = 3f,
                                )
                                i++
                            }
                            for (stop in s.stopIdxs) {
                                val (sx, sy) = proj.project(index.stopLatMicro(stop), index.stopLonMicro(stop))
                                drawCircle(color = stroke, radius = 7f, center = Offset(sx, sy), style = Stroke(width = 3f))
                            }
                            for (v in s.vehicles) {
                                val (vx, vy) = proj.project(v.latMicro, v.lonMicro)
                                drawCircle(color = stroke, radius = 9f, center = Offset(vx, vy))
                            }
                        }
                    }
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
}
