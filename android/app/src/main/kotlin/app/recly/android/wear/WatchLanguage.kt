package app.recly.android.wear

import android.content.Context
import app.recly.android.core.AndroidLogger
import app.recly.datalayer.WearLanguage
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import recly.core.platform.Logger

/**
 * The phone app's language, handed to the watch app ([WearLanguage]): it shows itself in the language this app is
 * in, as the Apple Watch app follows the iPhone app's. A data item, so it waits for a watch that is out of reach
 * and reaches one installed later; writing the language it already holds changes nothing on the watch.
 */
object WatchLanguage {

    /** [tag]: the language this app is in now. Urgent: a language that arrives half an hour later is a bug. */
    fun publish(context: Context, tag: String) {
        val request = PutDataRequest.create(WearLanguage.PATH).setData(WearLanguage.encode(tag)).setUrgent()
        // A phone without the Wear OS app has no Data Layer to write to, and that is not worth more than a line.
        Wearable.getDataClient(context).putDataItem(request)
            .addOnFailureListener { AndroidLogger().log(Logger.Level.DEBUG, "shell.wear.language.failed", error = it) }
    }
}
