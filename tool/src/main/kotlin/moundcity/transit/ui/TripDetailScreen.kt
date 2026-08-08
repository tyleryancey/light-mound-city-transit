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
import moundcity.transit.core.query.Approach
import moundcity.transit.core.query.RouteLabels
import moundcity.transit.core.query.RowFormat
import moundcity.transit.core.query.TripDetailState

class TripDetailViewModel(private val tripIdx: Int, private val fromSeq: Int) : LightViewModel<Unit>() {

    val headerLines = MutableStateFlow<List<String>>(emptyList())
    val stops = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList()) // text, isTerminus

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val index = AppGraph.index
            val now = Instant.now()
            // tripStops is a full departures-section scan — one call, then slice.
            val allStops = index.tripStops(tripIdx, fromSeq = 0)
            val remaining = allStops.filter { it.seq >= fromSeq }
            val lines = mutableListOf(
                "${RouteLabels.displayShortName(index, index.tripRoute(tripIdx))}  ${index.routeLongName(index.tripRoute(tripIdx))}",
                index.headsign(index.tripHeadsign(tripIdx)),
            )
            val live = AppGraph.liveSnapshot(now.epochSecond)
            val fix = live?.vehicles?.fixByTripId()?.get(index.tripId(tripIdx))
            if (fix != null && remaining.isNotEmpty()) {
                val estimate = Approach.estimate(
                    allStops, remaining.first().seq,
                    fix.latMicro, fix.lonMicro, index::stopLatMicro, index::stopLonMicro,
                )
                if (estimate != null) lines.add(estimate.text())
                lines.add(TripDetailState.vehicleLine(fix.vehicleId, now.epochSecond, fix.timestamp))
            } else {
                lines.add("scheduled — no live position")
            }
            headerLines.value = lines
            stops.value = remaining.mapIndexed { i, s ->
                "${RowFormat.timeText(s.minute)}  ${index.stopName(s.stopIdx)}" to (i == remaining.lastIndex)
            }
        }
    }
}

/** Doc 02 §3.3: where is it, and what happens after me. Bounded by the trip. */
class TripDetailScreen(
    sealedActivity: SealedLightActivity,
    private val tripIdx: Int,
    private val fromSeq: Int,
    private val minute: Int,
) : LightScreen<Unit, TripDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<TripDetailViewModel> get() = TripDetailViewModel::class.java
    override fun createViewModel(): TripDetailViewModel = TripDetailViewModel(tripIdx, fromSeq)

    @Composable
    override fun Content() {
        val header by viewModel.headerLines.collectAsState()
        val stops by viewModel.stops.collectAsState()
        MctPage(title = RowFormat.timeText(minute), onBack = { goBack() }) {
            LazyColumn {
                items(header) { line -> MctRow(primary = line) }
                item { MctRow(primary = "— remaining stops —") }
                items(stops) { (text, terminus) ->
                    MctRow(primary = if (terminus) "$text · terminus" else text)
                }
            }
        }
    }
}
