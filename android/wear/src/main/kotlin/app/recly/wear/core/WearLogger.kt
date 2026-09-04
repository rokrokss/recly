package app.recly.wear.core

import android.util.Log
import recly.core.platform.Logger

/**
 * As the phone's `AndroidLogger`: one tag, the core's event name first, so `logcat -s recly` reads
 * the same on both devices (docs/20). Stateless, so the watch keeps a single instance.
 */
object WearLogger : Logger {
    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
        val message = if (fields.isEmpty()) event else "$event ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        when (level) {
            Logger.Level.DEBUG -> Log.d(TAG, message, error)
            Logger.Level.INFO -> Log.i(TAG, message, error)
            Logger.Level.WARN -> Log.w(TAG, message, error)
            Logger.Level.ERROR -> Log.e(TAG, message, error)
        }
    }

    private const val TAG = "recly"
}
