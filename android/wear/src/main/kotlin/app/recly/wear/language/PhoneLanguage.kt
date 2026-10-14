package app.recly.wear.language

import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import android.os.LocaleList
import app.recly.datalayer.WearLanguage
import app.recly.recording.RecorderService
import app.recly.wear.RecWearApp
import app.recly.wear.core.WearLogger
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import recly.core.platform.Logger

/**
 * The watch app in the phone app's language ([WearLanguage]), as the Apple Watch app follows the iPhone app's.
 * Applied as this app's own per-app locale, which the system keeps across restarts; with nothing received the
 * app follows the watch's system language, as before.
 */
object PhoneLanguage {

    /** What the phone published, applied — or nothing, for a payload that is not a language tag. */
    fun apply(context: Context, payload: ByteArray) {
        val tag = WearLanguage.decode(payload) ?: return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val wanted = LocaleList.forLanguageTags(tag)
        if (manager.applicationLocales == wanted) return
        manager.applicationLocales = wanted
        WearLogger.log(Logger.Level.INFO, "shell.wear.language", mapOf("tag" to tag))
        // The activity is recreated by the system; the tile, the complication and the recording's notification are
        // painted once and are asked for again.
        (context.applicationContext as? RecWearApp)?.refreshEntryPoints()
        RecorderService.refreshNotification(context)
    }

    /**
     * The item the phone already wrote, read once at start: one written before this app was installed, or while
     * its listener could not run, never arrives as a change. Blocking — call it off the main thread.
     */
    fun adoptStored(context: Context) {
        val items = Tasks.await(
            Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*${WearLanguage.PATH}"), DataClient.FILTER_LITERAL),
        )
        try {
            items.firstOrNull()?.data?.let { apply(context, it) }
        } finally {
            items.release()
        }
    }
}

/** The phone changed its language (manifest: `DATA_CHANGED` on [WearLanguage.PATH] only). */
class PhoneLanguageService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearLanguage.PATH }
            .lastOrNull()?.dataItem?.data?.let { PhoneLanguage.apply(applicationContext, it) }
    }
}
