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
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moundcity.transit.core.query.DataAge
import moundcity.transit.core.query.RouteLabels
import moundcity.transit.core.query.BrowseCatalog
import moundcity.transit.core.rt.RtVehicle

class RouteViewModel(private val routeIdx: Int) : LightViewModel<Unit>() {

    data class ViewerState(
        val shape: IntArray?,
        val stopIdxs: List<Int>,
        val vehicles: List<RtVehicle>,
        val isRail: Boolean,
        val liveLine: String?,
    )

    val direction = MutableStateFlow(0)
    val state = MutableStateFlow<ViewerState?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    fun toggleDirection() {
        direction.value = 1 - direction.value
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val index = AppGraph.index
            val dir = direction.value
            val now = Instant.now()
            val isRail = index.routeId(routeIdx).let { it.startsWith("19731") || it.startsWith("19870") }
            val live = AppGraph.liveSnapshot(now.epochSecond)
            val fixes = if (isRail || live == null) emptyList() else {
                live.vehicles.fixes.filter { fix ->
                    val t = index.tripIndexOf(fix.tripId.toIntOrNull() ?: return@filter false)
                    t != null && index.tripRoute(t) == routeIdx && index.tripDirection(t) == dir
                }
            }
            state.value = ViewerState(
                shape = index.routeShape(routeIdx, dir),
                stopIdxs = BrowseCatalog.routeStops(index, routeIdx, dir),
                vehicles = fixes,
                isRail = isRail,
                liveLine = live?.let { DataAge.liveLine(now.epochSecond, it.vehicles.headerTimestamp) },
            )
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.refresh(Instant.now().epochSecond)
            reload()
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
        MctPage(
            title = "${RouteLabels.displayShortName(index, routeIdx)}  ${index.routeLongName(routeIdx)}",
            onBack = { goBack() },
        ) {
            LazyColumn {
                item {
                    MctRow(
                        primary = "direction ${dir + 1} of 2 — tap to switch",
                        secondary = state?.let { s ->
                            if (s.isRail) "scheduled — no live train positions"
                            else s.liveLine?.let { "${s.vehicles.size} vehicle${if (s.vehicles.size == 1) "" else "s"} · $it" } ?: "no live data — refresh below"
                        },
                        onTap = { viewModel.toggleDirection() },
                    )
                }
                item {
                    val s = state
                    if (s?.shape != null) {
                        val stroke = LightThemeTokens.colors.content
                        Canvas(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                            val proj = moundcity.transit.core.query.ShapeProjection.fit(
                                s.shape, size.width, size.height, pad = 24f,
                            )
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
                        primary = "${index.stopCode(stop)}  ${index.stopName(stop)}",
                        onTap = { navigateTo({ sa -> DeparturesScreen(sa, stop) }) },
                    )
                }
            }
        }
    }
}
