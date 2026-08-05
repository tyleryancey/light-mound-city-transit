package moundcity.transit.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import moundcity.transit.core.query.AlertMatch
import moundcity.transit.core.query.HomeState
import moundcity.transit.core.query.StopRoutes

class HomeViewModel : LightViewModel<Unit>() {

    val savedRows = MutableStateFlow<List<HomeState.SavedStopRow>>(emptyList())
    val alertCount = MutableStateFlow(0)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = AppGraph.prefs ?: return@launch
            val saved = prefs.savedStops()
            val now = Instant.now()
            savedRows.value = HomeState.savedStopRows(
                AppGraph.index, saved, now, CHICAGO,
                AppGraph.liveSnapshot(now.epochSecond)?.trips,
            )
            val snapshot = AppGraph.snapshot
            alertCount.value = if (snapshot == null) 0 else {
                val routes = saved.mapNotNull { AppGraph.index.resolveStop(it) }
                    .flatMap { StopRoutes.routesServing(AppGraph.index, it) }.toSet()
                AlertMatch.forRoutes(snapshot.alerts, AppGraph.index, routes.ifEmpty { null }).size
            }
        }
    }
}

/** Doc 02 §3.1: saved stops, entry, browse, alerts, reference. */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel> get() = HomeViewModel::class.java
    override fun createViewModel(): HomeViewModel = HomeViewModel()

    override fun willShow() {
        AppGraph.ensure(lightContext)
    }

    @Composable
    override fun Content() {
        AppGraph.ensure(lightContext)
        val saved by viewModel.savedRows.collectAsState()
        val alertCount by viewModel.alertCount.collectAsState()
        MctPage(title = "Mound City Transit") {
            LazyColumn {
                items(saved) { row ->
                    MctRow(
                        primary = "${row.code}  ${row.name}",
                        secondary = "next ${row.nextText}",
                        onTap = {
                            val stop = AppGraph.index.resolveStop(row.code) ?: return@MctRow
                            navigateTo({ sa -> DeparturesScreen(sa, stop) })
                        },
                    )
                }
                item { MctRow(primary = "Enter a stop number", onTap = { navigateTo(::StopEntryScreen) }) }
                item { MctRow(primary = "Browse", onTap = { navigateTo(::BrowseScreen) }) }
                item {
                    MctRow(
                        primary = if (alertCount > 0) "Alerts ($alertCount affect your stops)" else "Alerts",
                        onTap = { navigateTo(::AlertsScreen) },
                    )
                }
                item { MctRow(primary = "Reference", onTap = { navigateTo(::ReferenceScreen) }) }
            }
        }
    }
}
