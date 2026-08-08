package moundcity.transit.ui

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Screens with a manual ↻ share one refresh path: fetch behind the 30 s
 *  floor, then rebuild whatever the screen shows. */
abstract class ReloadingViewModel : LightViewModel<Unit>() {

    abstract fun reload()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.refresh(Instant.now().epochSecond)
            reload()
        }
    }
}
