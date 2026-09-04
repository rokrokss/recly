package app.recly.wear.data

import android.content.Context

/**
 * ADR-016 on the watch: the phone keeps one local pointer at the workflow *it* runs, and this watch
 * keeps its own. Tapping a name in the picker sets it, and the recording carries it to the phone as
 * the chosen id — so the two are never merged and neither is ever written into `workflows.json`.
 *
 * Unset is the honest starting state, not "the first workflow": the phone's own default is what
 * runs then, which is what a user who has never opened the picker would expect.
 */
interface WatchDefault {
    fun read(): String?

    fun write(id: String?)
}

/**
 * One key in one preferences file — the watch has no other local store, and the queue's own reason
 * for a file rather than a database (`FileTransferQueue`) applies twice over to a single string.
 */
class PrefsWatchDefault(context: Context) : WatchDefault {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY) else putString(KEY, id) }.apply()
    }

    private companion object {
        const val FILE = "wear"
        const val KEY = "default_workflow_id"
    }
}
