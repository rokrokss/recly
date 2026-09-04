@file:OptIn(ExperimentalCoroutinesApi::class) // runCurrent()

package app.recly.android.ui

import android.app.Activity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The ViewModel's consent state machine. `PendingIntent` cannot be built off-device, so the
 * controller is generic in the handle and these tests stand a String in for it — the state machine
 * under test is exactly the one `MainViewModel` runs.
 */
class ConsentControllerTest {

    @Test
    fun theConsentResultSurvivesActivityRecreation() = runTest {
        val controller = ConsentController<String>()

        val authorization = async { controller.await("consent") }
        runCurrent()

        assertEquals("consent", controller.request.value, "the request is published for the activity")
        assertTrue(controller.consumeLaunch(), "the attached activity shows the consent screen")

        // Rotation: the activity dies and a new one collects the same request.
        assertFalse(controller.consumeLaunch(), "a recreated activity must not show a second screen")
        assertEquals("consent", controller.request.value, "the request outlives the activity")

        // The result lands on the new activity's launcher and is forwarded here.
        controller.onConsentResultDelivered()

        val result = authorization.await()
        assertTrue(result.ok, "the suspended authorization resumes rather than hanging")
        assertNull(controller.request.value, "a finished request is cleared")
    }

    @Test
    fun aDuplicateResultIsIgnored() = runTest {
        val controller = ConsentController<String>()

        val first = async { controller.await("first") }
        runCurrent()
        controller.onConsentResultDelivered()
        assertTrue(first.await().ok)

        // A stale launcher delivering a second time must not leak into the next request.
        controller.onResult(Activity.RESULT_CANCELED, null)

        val second = async { controller.await("second") }
        runCurrent()
        assertEquals("second", controller.request.value)
        assertTrue(second.isActive, "the stale result must not resume the new request")

        controller.onConsentResultDelivered()
        assertTrue(second.await().ok)
    }

    @Test
    fun aCancelledConsentResumesAsNotOk() = runTest {
        val controller = ConsentController<String>()

        val authorization = async { controller.await("consent") }
        runCurrent()
        controller.onResult(Activity.RESULT_CANCELED, null)

        assertFalse(authorization.await().ok)
        assertNull(controller.request.value)
    }

    @Test
    fun nothingToLaunchWithoutARequest() = runTest {
        assertFalse(ConsentController<String>().consumeLaunch())
    }

    /** `data` is always null here: an Intent cannot be built off-device either. */
    private fun ConsentController<String>.onConsentResultDelivered() =
        onResult(Activity.RESULT_OK, null)
}
