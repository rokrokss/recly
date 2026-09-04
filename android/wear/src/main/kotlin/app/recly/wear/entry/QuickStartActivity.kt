package app.recly.wear.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.recly.wear.ui.MainActivity

/**
 * docs/11 W5: the second launcher entry, "Recly Record". Samsung's "Double press home key" setting
 * can only name an app, and launches it with a bare `MAIN`/`LAUNCHER` intent — no extras, so it
 * cannot ask [MainActivity] to auto-start the way the tile does. This entry exists to be the app
 * that setting names: it forwards to [MainActivity] with [MainActivity.EXTRA_AUTO_START] and is
 * gone before it draws. `Theme.NoDisplay` requires `finish()` before `onResume`, which this does.
 */
class QuickStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                // The existing instance, if there is one, gets `onNewIntent` (`singleTop` alone
                // would not: this activity, not MainActivity, is the top of the task).
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
