package moundcity.transit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class HomeViewModel : LightViewModel<Unit>()

/**
 * Phase 2 shell: the wiring, not the product. The real Home screen (saved
 * stops, entry, browse, alerts, reference — build plan 3.1) replaces this
 * content in Phase 3.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp),
            ) {
                LightText(
                    text = "Mound City Transit",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                LightText(
                    text = "St. Louis departures. Screens arrive in Phase 3.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}
