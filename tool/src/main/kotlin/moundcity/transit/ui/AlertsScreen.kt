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
import moundcity.transit.core.query.StopRoutes

class AlertsViewModel : LightViewModel<Unit>() {

    val alerts = MutableStateFlow<List<AlertMatch.Matched>>(emptyList())
    val showAll = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    fun toggle() {
        showAll.value = !showAll.value
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            if (AppGraph.snapshot == null) AppGraph.refresh(Instant.now().epochSecond)
            val snapshot = AppGraph.snapshot
            if (snapshot == null) {
                status.value = "Alerts unavailable — no connection."
                alerts.value = emptyList()
                return@launch
            }
            status.value = null
            val index = AppGraph.index
            val filter = if (showAll.value) null else {
                AppGraph.prefs?.savedStops().orEmpty()
                    .mapNotNull { index.resolveStop(it) }
                    .flatMap { StopRoutes.routesServing(index, it) }
                    .toSet().ifEmpty { null }
            }
            alerts.value = AlertMatch.forRoutes(snapshot.alerts, index, filter)
        }
    }
}

/** Doc 02 §3.5: filtered to saved-stop routes by default; full text on detail. */
class AlertsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AlertsViewModel>(sealedActivity) {

    override val viewModelClass: Class<AlertsViewModel> get() = AlertsViewModel::class.java
    override fun createViewModel(): AlertsViewModel = AlertsViewModel()

    @Composable
    override fun Content() {
        val alerts by viewModel.alerts.collectAsState()
        val showAll by viewModel.showAll.collectAsState()
        val status by viewModel.status.collectAsState()
        MctPage(title = "Alerts", onBack = { goBack() }) {
            LazyColumn {
                item {
                    MctRow(
                        primary = if (showAll) "Showing all alerts — tap for your stops" else "Showing your stops — tap for all",
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
                                AlertDetailScreen(sa, a.header, a.description, AlertMatch.effectiveFrom(a.alert, CHICAGO))
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
) : LightScreen<Unit, AlertDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<AlertDetailViewModel> get() = AlertDetailViewModel::class.java
    override fun createViewModel(): AlertDetailViewModel = AlertDetailViewModel()

    @Composable
    override fun Content() {
        MctPage(title = "Alert", onBack = { goBack() }) {
            LazyColumn {
                item { MctRow(primary = header, secondary = effective) }
                item { MctRow(primary = description.ifEmpty { "No further detail published." }) }
            }
        }
    }
}
