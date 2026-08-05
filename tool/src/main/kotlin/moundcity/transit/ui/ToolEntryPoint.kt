package moundcity.transit.ui

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        // Nothing to initialize: the index loads lazily on first screen show,
        // and this tool never registers for push.
    }

    override suspend fun onPushNotification(data: ByteArray) {
        // Never subscribed; nothing to do.
    }
}
