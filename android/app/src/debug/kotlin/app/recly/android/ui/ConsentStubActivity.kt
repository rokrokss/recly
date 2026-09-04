package app.recly.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands in for the Play Services consent screen (`ConsentRecreationTest`). It does not finish on
 * its own: the test holds it open, recreates `MainActivity` underneath it, and only then releases
 * it — the result has to arrive at an activity instance that did not launch it.
 *
 * Debug source set, not androidTest: instrumentation runs in the app's process, so an activity
 * declared in the test package would start in a different process and these latches would never
 * line up. It is absent from release builds.
 */
class ConsentStubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launches.incrementAndGet()
        started.countDown()
        Thread {
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_GRANTED, true))
                finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_GRANTED = "granted"
        private const val WAIT_SECONDS = 30L

        /** How many times the launcher actually started this activity. */
        val launches = AtomicInteger(0)

        @Volatile var started = CountDownLatch(1)
            private set

        @Volatile private var release = CountDownLatch(1)

        fun reset() {
            launches.set(0)
            started = CountDownLatch(1)
            release = CountDownLatch(1)
        }

        /** Lets the stub deliver its result. */
        fun release() = release.countDown()
    }
}
