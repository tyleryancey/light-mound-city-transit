package moundcity.transit.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moundcity.transit.core.query.BrowseCatalog

class BrowseViewModel : LightViewModel<Unit>() {

    val stations = MutableStateFlow<List<BrowseCatalog.Station>>(emptyList())
    val centers = MutableStateFlow<List<BrowseCatalog.Center>>(emptyList())
    val groups = MutableStateFlow<BrowseCatalog.RouteGroups?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val index = AppGraph.index
            stations.value = BrowseCatalog.railStations(index)
            centers.value = BrowseCatalog.transitCenters(index)
            groups.value = BrowseCatalog.routesGrouped(index)
        }
    }
}

/** Doc 02 §3.4: three finite lists, no search-the-world box. Every leaf is a
 *  stop. The catalog scans run once per show, off the main thread (finding 6). */
class BrowseScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, BrowseViewModel>(sealedActivity) {

    override val viewModelClass: Class<BrowseViewModel> get() = BrowseViewModel::class.java
    override fun createViewModel(): BrowseViewModel = BrowseViewModel()

    @Composable
    override fun Content() {
        val index = AppGraph.index
        val stations by viewModel.stations.collectAsState()
        val centers by viewModel.centers.collectAsState()
        val groups by viewModel.groups.collectAsState()
        MctPage(title = "Browse", onBack = { goBack() }) {
            LazyColumn {
                item { MctRow(primary = "— rail stations (${stations.size}) —") }
                items(stations) { st ->
                    MctRow(primary = st.name, onTap = { navigateTo({ sa -> DeparturesScreen(sa, st.stopIdx) }) })
                }
                item { MctRow(primary = "— transit centers (${centers.size}) —") }
                items(centers) { c ->
                    MctRow(
                        primary = c.name,
                        secondary = if (c.stopIdxs.size > 1) "${c.stopIdxs.size} platforms" else null,
                        onTap = {
                            if (c.stopIdxs.size == 1) navigateTo({ sa -> DeparturesScreen(sa, c.stopIdxs[0]) })
                            else navigateTo({ sa -> CenterScreen(sa, c.name, c.stopIdxs.toIntArray()) })
                        },
                    )
                }
                val g = groups
                if (g != null) {
                    item { MctRow(primary = "— Missouri routes (${g.missouri.size}) —") }
                    items(g.missouri) { r ->
                        MctRow(primary = r.label, onTap = { navigateTo({ sa -> RouteScreen(sa, r.routeIdx) }) })
                    }
                    item { MctRow(primary = "— Illinois routes (${g.illinois.size}) —") }
                    items(g.illinois) { r ->
                        MctRow(primary = r.label, onTap = { navigateTo({ sa -> RouteScreen(sa, r.routeIdx) }) })
                    }
                    item { MctRow(primary = "— rail (${g.rail.size}) —") }
                    items(g.rail) { line ->
                        MctRow(
                            primary = "${line.label}  ${index.routeLongName(line.routeIdxs.first())}",
                            onTap = { navigateTo({ sa -> RouteScreen(sa, line.routeIdxs.first()) }) },
                        )
                    }
                }
            }
        }
    }
}

class CenterViewModel : LightViewModel<Unit>()

/** One level of platform choice for multi-stop centers, then departures. */
class CenterScreen(
    sealedActivity: SealedLightActivity,
    private val name: String,
    private val stopIdxs: IntArray,
) : LightScreen<Unit, CenterViewModel>(sealedActivity) {

    override val viewModelClass: Class<CenterViewModel> get() = CenterViewModel::class.java
    override fun createViewModel(): CenterViewModel = CenterViewModel()

    @Composable
    override fun Content() {
        val index = AppGraph.index
        MctPage(title = name, onBack = { goBack() }) {
            LazyColumn {
                items(stopIdxs.toList()) { stop ->
                    MctRow(
                        primary = "${index.stopCode(stop)}  ${index.stopName(stop)}",
                        onTap = { navigateTo({ sa -> DeparturesScreen(sa, stop) }) },
                    )
                }
            }
        }
    }
}
