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
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moundcity.transit.core.query.AlertMatch
import moundcity.transit.core.query.RouteLabels
import moundcity.transit.core.query.StopRoutes

class AlertsViewModel(private val routeFilter: IntArray?) : LightViewModel<Unit>() {

    val alerts = MutableStateFlow<List<AlertMatch.Matched>>(emptyList())
    val showAll = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val filterLabel = MutableStateFlow(
        if (routeFilter != null) "Showing this stop — tap for all" else "Showing your stops — tap for all",
    )

    /** Bumped on the main thread per reload; a stale reload must not write last. */
    @Volatile private var generation = 0

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    fun toggle() {
        showAll.value = !showAll.value
        reload()
    }

    fun reload() {
        val gen = ++generation
        viewModelScope.launch(Dispatchers.IO) {
            // Filter and label are one core decision (AlertMatch.filterState),
            // taken from one showAll read before the blocking fetch — the label
            // must render while the fetch is still in flight.
            val index = AppGraph.index
            val savedRoutes = if (routeFilter != null) null else {
                StopRoutes.routesServingSaved(index, AppGraph.prefs?.savedStops().orEmpty())
            }
            val state = AlertMatch.filterState(routeFilter?.toSet(), showAll.value, savedRoutes)
            if (gen != generation) return@launch
            filterLabel.value = state.label
            // D10's one implicit fetch: once per open, only when nothing is cached.
            if (AppGraph.snapshot == null) AppGraph.refresh(Instant.now().epochSecond)
            val snapshot = AppGraph.snapshot
            if (gen != generation) return@launch
            if (snapshot == null) {
                status.value = "Alerts unavailable — no connection."
                alerts.value = emptyList()
                return@launch
            }
            status.value = null
            alerts.value = AlertMatch.forRoutes(snapshot.alerts, index, state.filter)
        }
    }
}

/** Doc 02 §3.5: filtered to saved-stop routes by default — or to one stop's
 *  routes when opened from a departures banner; full text on detail. */
class AlertsScreen(sealedActivity: SealedLightActivity, private val routeFilter: IntArray? = null) :
    LightScreen<Unit, AlertsViewModel>(sealedActivity) {

    override val viewModelClass: Class<AlertsViewModel> get() = AlertsViewModel::class.java
    override fun createViewModel(): AlertsViewModel = AlertsViewModel(routeFilter)

    @Composable
    override fun Content() {
        val alerts by viewModel.alerts.collectAsState()
        val status by viewModel.status.collectAsState()
        val filterLabel by viewModel.filterLabel.collectAsState()
        MctPage(title = "Alerts", onBack = { goBack() }) {
            LazyColumn {
                item {
                    MctRow(
                        primary = filterLabel,
                        onTap = { viewModel.toggle() },
                    )
                }
                if (status != null) item { MctRow(primary = status!!) }
                items(alerts) { a ->
                    MctRow(
                        primary = a.header,
                        secondary = a.routeLabels.joinToString(" · ").ifEmpty { null },
                        onTap = {
                            navigateTo({ sa ->
                                AlertDetailScreen(
                                    sa, a.header, a.description,
                                    AlertMatch.effectiveFrom(a.alert, CHICAGO),
                                    a.routeIdxs.distinct().toIntArray(),
                                )
                            })
                        },
                    )
                }
            }
        }
    }
}

class AlertDetailViewModel : LightViewModel<Unit>()

/** Full description, untruncated — a rider at a moved stop needs the whole thing. */
class AlertDetailScreen(
    sealedActivity: SealedLightActivity,
    private val header: String,
    private val description: String,
    private val effective: String,
    private val routeIdxs: IntArray = IntArray(0),
) : LightScreen<Unit, AlertDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<AlertDetailViewModel> get() = AlertDetailViewModel::class.java
    override fun createViewModel(): AlertDetailViewModel = AlertDetailViewModel()

    @Composable
    override fun Content() {
        MctPage(title = "Alert", onBack = { goBack() }) {
            LazyColumn {
                item { MctRow(primary = header, secondary = effective) }
                item { MctRow(primary = description.ifEmpty { "No further detail published." }) }
                items(routeIdxs.toList()) { r ->
                    MctRow(
                        primary = "View route ${RouteLabels.displayShortName(AppGraph.index, r)} →",
                        onTap = { navigateTo({ sa -> RouteScreen(sa, r) }) },
                    )
                }
            }
        }
    }
}
