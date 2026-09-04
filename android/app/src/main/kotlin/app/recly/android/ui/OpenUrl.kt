package app.recly.android.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Every page this app sends the user to outside itself — a Drive folder, Google's storage page,
 * the account's permissions, the consent guidance. `NEW_TASK` because the caller is a composable
 * holding whatever `Context` the composition gave it, which is not always an activity's.
 */
fun Context.openUrl(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * This app's own page in the system settings — where a refused permission is given back. The
 * system dialog stops opening after the second refusal, so this is the only way the microphone
 * comes back on, and the record screen and the capture section both offer it (the iPhone's
 * `openSettings`).
 */
fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
