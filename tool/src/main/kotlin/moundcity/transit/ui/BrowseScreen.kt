package moundcity.transit.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import moundcity.transit.core.query.BrowseCatalog

class BrowseViewModel : LightViewModel<Unit>()

/** Doc 02 §3.4: three finite lists, no search-the-world box. Every leaf is a stop. */
class BrowseScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, BrowseViewModel>(sealedActivity) {

    override val viewModelClass: Class<BrowseViewModel> get() = BrowseViewModel::class.java
    override fun createViewModel(): BrowseViewModel = BrowseViewModel()

    @Composable
    override fun Content() {
        val index = AppGraph.index
        val stations = BrowseCatalog.railStations(index)
        val centers = BrowseCatalog.transitCenters(index)
        val groups = BrowseCatalog.routesGrouped(index)
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
                item { MctRow(primary = "— Missouri routes (${groups.missouri.size}) —") }
                items(groups.missouri) { r ->
                    MctRow(primary = r.label, onTap = { navigateTo({ sa -> RouteScreen(sa, r.routeIdx) }) })
                }
                item { MctRow(primary = "— Illinois routes (${groups.illinois.size}) —") }
                items(groups.illinois) { r ->
                    MctRow(primary = r.label, onTap = { navigateTo({ sa -> RouteScreen(sa, r.routeIdx) }) })
                }
                item { MctRow(primary = "— rail (${groups.rail.size}) —") }
                items(groups.rail) { line ->
                    MctRow(
                        primary = "${line.label}  ${index.routeLongName(line.routeIdxs.first())}",
                        onTap = { navigateTo({ sa -> RouteScreen(sa, line.routeIdxs.first()) }) },
                    )
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
