package moundcity.transit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.ZoneId
import moundcity.transit.core.query.DataAge

val CHICAGO: ZoneId = ZoneId.of("America/Chicago")

/** The stop line every list renders: code, two spaces, name. */
fun stopLabel(index: moundcity.transit.core.gtfs.ScheduleIndex, stop: Int): String =
    "${index.stopCode(stop)}  ${index.stopName(stop)}"

/** Shared page scaffold: title, optional back, content, the always-on data-age footer. */
@Composable
fun MctPage(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                if (onBack != null) {
                    LightText(
                        text = "‹  ",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.lightClickable { onBack() },
                    )
                }
                LightText(text = title, variant = LightTextVariant.Heading)
            }
            Column(modifier = Modifier.weight(1f), content = content)
            DataAgeFooter()
        }
    }
}

/** D9: expiry REPLACES a screen's content — one wording, every screen. */
@Composable
fun ExpiredNotice() {
    MctRow(
        primary = "This schedule has expired.",
        secondary = "Refresh the app's data or reinstall to get current times.",
    )
}

/** Doc 02 §3.1: a data-age line at the bottom of every screen, never hidden. */
@Composable
fun DataAgeFooter() {
    val dataGen by AppGraph.dataGeneration.collectAsState()
    // Deliberately event-driven, not a ticker: the line advances on refresh
    // outcomes and index swaps, and otherwise holds still — no timer wakes
    // the panel just to age a caption.
    val now = remember(dataGen) { Instant.now() }
    val schedule = DataAge.scheduleLine(AppGraph.index, now.atZone(CHICAGO).toLocalDate())
    val live = AppGraph.liveSnapshot(now.epochSecond)
        ?.let { " · " + DataAge.liveLine(now.epochSecond, it.trips.headerTimestamp) }
        ?: if (AppGraph.lastError) " · live unavailable — showing schedule" else ""
    Spacer(modifier = Modifier.height(8.dp))
    LightText(text = schedule + live, variant = LightTextVariant.Detail, lighten = true)
}

/** A tappable list row: primary line + optional secondary, optional strike. */
@Composable
fun MctRow(
    primary: String,
    secondary: String? = null,
    struck: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    Column(modifier = if (onTap != null) base.lightClickable { onTap() } else base) {
        LightText(
            text = primary,
            variant = LightTextVariant.Copy,
            lighten = struck,
        )
        if (secondary != null) {
            LightText(text = secondary, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}
