package recly.core.storage

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A native metadata read may outlive cancellation. Its caller must still leave the queue. */
internal class ICloudSettlementChecks(
    dispatcher: CoroutineDispatcher,
    private val budget: Duration = 20.seconds,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Mutex()
    private val running = mutableMapOf<String, Deferred<Boolean>>()

    suspend fun check(folder: String, inspect: suspend () -> Boolean): Boolean {
        val read = lock.withLock {
            if (running[folder]?.isCompleted == false || running.size >= 8) return false
            // This work is read-only. An unfinished read stays tracked until the native call
            // returns, so a later poll cannot pile up more reads for the same recording.
            val next = scope.async(start = CoroutineStart.LAZY) { inspect() }
            running[folder] = next
            next.invokeOnCompletion {
                scope.launch {
                    lock.withLock {
                        if (running[folder] === next) running.remove(folder)
                    }
                }
            }
            next
        }
        return try {
            withTimeoutOrNull(budget) { read.await() } ?: false
        } finally {
            read.cancel()
        }
    }
}
