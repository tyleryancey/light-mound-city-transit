package moundcity.transit.ui

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.rememberKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

class StopEntryViewModel : LightViewModel<Unit>() {
    /** null = typing; a message when the number resolves to nothing. */
    val message = MutableStateFlow<String?>(null)
}

/** Doc 02 §3.2 entry: the number on the sign, full-screen editor (task 3.2). */
class StopEntryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, StopEntryViewModel>(sealedActivity) {

    override val viewModelClass: Class<StopEntryViewModel> get() = StopEntryViewModel::class.java
    override fun createViewModel(): StopEntryViewModel = StopEntryViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val message by viewModel.message.collectAsState()
        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = message ?: "Stop number",
                state = textFieldState,
                onSubmit = { text ->
                    val code = text.toString().trim().toIntOrNull()
                    val stop = code?.let { AppGraph.index.resolveStop(it) }
                    if (stop == null) {
                        // Unknown number → a plain message, never a crash (3.2)
                        viewModel.message.value = "No stop with that number"
                    } else {
                        goBack()
                        navigateTo({ sa -> DeparturesScreen(sa, stop) })
                    }
                },
                onBack = { goBack() },
                keyboardOptionsFlow = keyboardOptionsFlow,
                singleLine = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
