package app.recly.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.recly.android.core.CoreModule

/**
 * docs/09 trend 6 "honest system indicators": the device id is on the recording dashboard and in the About
 * block. It lives in the secure store behind a suspending build, and no ViewModel publishes it —
 * reading it here keeps the state the screens are given exactly as it was.
 */
@Composable
fun rememberDeviceId(): String {
    val context = LocalContext.current.applicationContext
    val id by produceState(initialValue = "", context) {
        value = CoreModule.get(context).core.deps.device.deviceId
    }
    return id
}
