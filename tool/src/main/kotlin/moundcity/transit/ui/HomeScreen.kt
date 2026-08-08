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

class HomeViewModel : LightViewModel<Unit>() {

    val savedRows = MutableStateFlow<List<HomeState.SavedStopRow>>(emptyList())
    val alertCount = MutableStateFlow(0)
    val refreshNotice = MutableStateFlow<String?>(null)
    val sourceRevoked = MutableStateFlow(false)

    fun dismissNotice() {
        refreshNotice.value = null
        viewModelScope.launch(Dispatchers.IO) { AppGraph.prefs?.setRefreshNotice(null) }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = AppGraph.prefs ?: return@launch
            refreshNotice.value = prefs.refreshNotice()
            sourceRevoked.value = prefs.sourceRevoked()
            val saved = prefs.savedStops()
            val now = Instant.now()
            savedRows.value = HomeState.savedStopRows(
                AppGraph.index, saved, now, CHICAGO,
                AppGraph.liveSnapshot(now.epochSecond)?.trips,
            )
            val snapshot = AppGraph.snapshot
            alertCount.value = if (snapshot == null) 0 else
                AlertMatch.forSavedStops(snapshot.alerts, AppGraph.index, saved).size
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
        AppGraph.ensureDailyJob(lightContext)
    }

    @Composable
    override fun Content() {
        // Idempotent fast path — belt over willShow for any compose-first
        // ordering the SDK lifecycle may take.
        AppGraph.ensure(lightContext)
        val saved by viewModel.savedRows.collectAsState()
        val alertCount by viewModel.alertCount.collectAsState()
        val notice by viewModel.refreshNotice.collectAsState()
        val revoked by viewModel.sourceRevoked.collectAsState()
        MctPage(title = "Mound City Transit") {
            LazyColumn {
                if (revoked) {
                    item {
                        MctRow(
                            primary = "The agency's schedule feed is no longer available",
                            secondary = "Using the last good schedule — times will age out",
                        )
                    }
                }
                if (notice != null) {
                    item {
                        MctRow(
                            primary = notice!!,
                            secondary = "tap to dismiss",
                            onTap = { viewModel.dismissNotice() },
                        )
                    }
                }
                items(saved) { row ->
                    MctRow(
                        primary = "${row.code}  ${row.name}",
                        secondary = row.nextText,
                        // A vanished stop's row is visibly inert, not a fake
                        // target — but a live row re-resolves at TAP time: the
                        // daily job can swap the index while Home rests, and a
                        // build-time stopIdx would then point into the wrong
                        // (or a smaller) stop set.
                        onTap = if (row.stopIdx == null) null else {
                            {
                                AppGraph.index.resolveStop(row.code)?.let { stop ->
                                    navigateTo({ sa -> DeparturesScreen(sa, stop) })
                                }
                            }
                        },
                    )
                }
                item { MctRow(primary = "Enter a stop number", onTap = { navigateTo(::StopEntryScreen) }) }
                item { MctRow(primary = "Browse", onTap = { navigateTo(::BrowseScreen) }) }
                item {
                    MctRow(
                        primary = AlertMatch.badgeLabel(alertCount),
                        onTap = { navigateTo({ sa -> AlertsScreen(sa) }) },
                    )
                }
                item { MctRow(primary = "Reference", onTap = { navigateTo(::ReferenceScreen) }) }
            }
        }
    }
}
