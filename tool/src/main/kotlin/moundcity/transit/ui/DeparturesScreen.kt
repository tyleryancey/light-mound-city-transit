package moundcity.transit.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moundcity.transit.core.query.AlertMatch
import moundcity.transit.core.query.BoardRow
import moundcity.transit.core.query.DataAge
import moundcity.transit.core.query.DepartureBoard
import moundcity.transit.core.query.RouteLabels
import moundcity.transit.core.query.RowFormat
import moundcity.transit.core.query.RowStatus
import moundcity.transit.core.query.StopRoutes
import moundcity.transit.data.Prefs

class DeparturesViewModel(private val stop: Int) : ReloadingViewModel() {

    data class Row(val marker: String, val text: String, val headsign: String, val struck: Boolean, val board: BoardRow)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    val alertBanner = MutableStateFlow<String?>(null)
    val expired = MutableStateFlow(false)
    val saved = MutableStateFlow(false)
    val saveNotice = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    override fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val index = AppGraph.index
            val now = Instant.now()
            expired.value = DataAge.isExpired(index, now, CHICAGO)
            if (expired.value) { rows.value = emptyList(); return@launch }
            val live = AppGraph.liveSnapshot(now.epochSecond)
            val board = DepartureBoard.at(
                index, now, CHICAGO, stop, limit = 8,
                rt = live?.trips, vehicles = live?.vehicles,
            )
            val headerTs = live?.trips?.headerTimestamp ?: 0
            rows.value = board.map { b ->
                val label = RouteLabels.displayShortName(index, b.routeIdx)
                val long = index.routeLongName(b.routeIdx)
                val status = RowFormat.statusText(b.status, now.epochSecond, headerTs)
                Row(
                    marker = when {
                        b.status is RowStatus.Canceled -> "✕"
                        b.status is RowStatus.Live -> "●"
                        else -> "○"
                    },
                    text = "${RowFormat.timeText(b.minute)}   $label  $long — $status",
                    headsign = index.headsign(b.headsignIdx),
                    struck = b.status is RowStatus.Canceled,
                    board = b,
                )
            }
            val snapshot = AppGraph.snapshot
            alertBanner.value = snapshot?.let {
                AlertMatch.bannerLabel(AlertMatch.forRoutes(it.alerts, index, StopRoutes.routesServing(index, stop)).size)
            }
            saved.value = AppGraph.prefs?.savedStops()?.contains(index.stopCode(stop)) == true
        }
    }

    fun toggleSaved() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = AppGraph.prefs ?: return@launch
            val code = AppGraph.index.stopCode(stop)
            if (prefs.savedStops().contains(code)) {
                prefs.removeSavedStop(code)
                saveNotice.value = null
            } else if (prefs.addSavedStop(code)) {
                saveNotice.value = null
            } else {
                // The cap refusing silently was review finding 8.
                saveNotice.value = "Saved stops are full (${Prefs.MAX_SAVED_STOPS})"
            }
            saved.value = prefs.savedStops().contains(code)
        }
    }
}

/** Doc 02 §3.2: the main screen — 8 rows, weight-and-glyph, manual refresh only. */
class DeparturesScreen(sealedActivity: SealedLightActivity, private val stop: Int) :
    LightScreen<Unit, DeparturesViewModel>(sealedActivity) {

    override val viewModelClass: Class<DeparturesViewModel> get() = DeparturesViewModel::class.java
    override fun createViewModel(): DeparturesViewModel = DeparturesViewModel(stop)

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()
        val banner by viewModel.alertBanner.collectAsState()
        val expired by viewModel.expired.collectAsState()
        val saved by viewModel.saved.collectAsState()
        val saveNotice by viewModel.saveNotice.collectAsState()
        val index = AppGraph.index
        MctPage(
            title = stopLabel(index, stop),
            onBack = { goBack() },
        ) {
            LazyColumn {
                item {
                    MctRow(
                        primary = if (saved) "★ Saved — tap to remove" else "☆ Save this stop",
                        secondary = saveNotice,
                        onTap = { viewModel.toggleSaved() },
                    )
                }
                // Doc 02 §3.6: the per-stop wheelchair flag, straight from the feed.
                RowFormat.wheelchairText(index.wheelchair(stop))?.let { line ->
                    item { MctRow(primary = line) }
                }
                if (banner != null) {
                    item {
                        // The banner counts this stop's routes — the list it opens filters the same way (finding 2).
                        MctRow(primary = banner!!, onTap = {
                            navigateTo({ sa -> AlertsScreen(sa, StopRoutes.routesServing(index, stop).toIntArray()) })
                        })
                    }
                }
                if (expired) {
                    // D9: expiry REPLACES the list — a greyed-out time is still a time
                    item { ExpiredNotice() }
                } else {
                    items(rows) { r ->
                        MctRow(
                            primary = "${r.marker} ${r.text}",
                            secondary = "      ${r.headsign}",
                            struck = r.struck,
                            onTap = { navigateTo({ sa -> TripDetailScreen(sa, r.board.tripIdx, r.board.seq, r.board.minute) }) },
                        )
                    }
                    item { MctRow(primary = "↻ Refresh", onTap = { viewModel.refresh() }) }
                }
            }
        }
    }
}
