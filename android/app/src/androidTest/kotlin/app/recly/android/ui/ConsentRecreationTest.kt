package app.recly.android.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unit test covers the state machine; this covers the wiring the unit test cannot reach — a
 * real `PendingIntent`, the real `ActivityResultRegistry`, and a real activity destroyed and
 * rebuilt while the consent screen sits on top of it.
 *
 * The ordering that matters: the stub is held open, `MainActivity` is recreated underneath it, and
 * only then is the result released. So the result is delivered to an activity instance that never
 * launched it — which is exactly what a rotation mid-consent does, and exactly what the old
 * activity-owned continuation could not survive.
 */
@RunWith(AndroidJUnit4::class)
class ConsentRecreationTest {

    @Before
    fun resetStub() = ConsentStubActivity.reset()

    @Test
    fun theConsentResultSurvivesRealActivityRecreation() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, ConsentStubActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var first: Activity
            lateinit var awaiting: Deferred<ConsentResult>
            scenario.onActivity { activity ->
                first = activity
                val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
                awaiting = viewModel.viewModelScope.async { viewModel.consent.await(pendingIntent) }
            }

            assertTrue(
                ConsentStubActivity.started.await(20, TimeUnit.SECONDS),
                "the ActivityResultLauncher never started the consent activity",
            )
            assertEquals(1, ConsentStubActivity.launches.get(), "consent must be shown exactly once")

            // Rotation, in effect: MainActivity is destroyed and rebuilt while the stub is on top.
            scenario.onActivity { it.recreate() }
            val second = awaitNewInstance(scenario, first)
            assertNotSame(first, second, "the activity was not actually recreated")
            assertEquals(
                1,
                ConsentStubActivity.launches.get(),
                "the recreated activity must not show a second consent screen",
            )

            // Only now does the result come back — to an instance that never launched it.
            ConsentStubActivity.release()

            val result = runBlocking { withTimeout(20_000) { awaiting.await() } }
            assertTrue(result.ok, "the suspended authorization did not survive recreation")
            assertNotNull(result.data, "the result Intent was lost")
            assertTrue(
                result.data!!.getBooleanExtra(ConsentStubActivity.EXTRA_GRANTED, false),
                "the result Intent did not carry the stub's extra",
            )
            assertEquals(1, ConsentStubActivity.launches.get(), "consent was shown more than once")
        }
    }

    private fun awaitNewInstance(
        scenario: ActivityScenario<MainActivity>,
        previous: Activity,
        timeoutMs: Long = 20_000,
    ): Activity {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var current: Activity? = null
            scenario.onActivity { current = it }
            current?.takeIf { it !== previous }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("MainActivity was never recreated")
    }
}
