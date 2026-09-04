@file:OptIn(ExperimentalTime::class)

package recly.core.sync

import kotlin.time.ExperimentalTime
import recly.core.testing.FakeClock
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps

internal const val WF_A = "01J9AAAAAAAAAAAAAAAAAAAAAA"
internal const val WF_B = "01J9BBBBBBBBBBBBBBBBBBBBBB"

/** One device's workflow document, the way [recly.core.ReclyCore] assembles it. */
internal class WorkflowHarness(deviceId: String = "device-a") {
    val clock = FakeClock()
    val db = inMemoryDatabase()
    val deps = testDeps(clock = clock, deviceId = deviceId)

    val store = WorkflowStore(db, deps)
    val deviceDefaults = DeviceDefaultStore(db, deps)
    val workflows = WorkflowRepository(store, deviceDefaults, deps)
}
