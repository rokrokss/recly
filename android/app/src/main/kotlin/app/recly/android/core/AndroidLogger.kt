package app.recly.android.core

import android.util.Log
import recly.core.platform.Logger

/**
 * `android.util.Log` under one tag, with the core's event name first so `logcat -s recly` reads like
 * the shared event stream docs/20 expects. The file ring buffer ("로그 내보내기", A10) is a later lane.
 */
class AndroidLogger : Logger {
    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
        val message = if (fields.isEmpty()) event else "$event ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        when (level) {
            Logger.Level.DEBUG -> Log.d(TAG, message, error)
            Logger.Level.INFO -> Log.i(TAG, message, error)
            Logger.Level.WARN -> Log.w(TAG, message, error)
            Logger.Level.ERROR -> Log.e(TAG, message, error)
        }
    }

    private companion object {
        const val TAG = "recly"
    }
}
