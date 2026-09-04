@file:OptIn(ExperimentalTime::class)

package app.recly.windows.jobs

import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recly.core.ReclyCore
import recly.core.job.Job
import recly.core.job.RunSummary
import recly.core.platform.Logger

/**
 * Everything [JobRunner] needs from the core, and so the whole of what a test has to stand in for
 * (the phone's twin is `JobFacade`, the Mac's is `JobQueue`). A runner that reached for
 * [ReclyCore] directly could only be tested against a database.
 */
interface JobQueue {
    fun now(): Instant

    suspend fun runDueJobs(): RunSummary

    /** The queue as it stands after a pass — read to arm the successor. */
    suspend fun jobs(): List<Job>
}

/** The real one. Every core call is hopped onto `CoreDeps.io` (docs/10 "동시성 · 스레딩"). */
class CoreJobQueue(private val core: ReclyCore) : JobQueue {
    override fun now(): Instant = core.deps.clock.now()

    override suspend fun runDueJobs(): RunSummary = withContext(core.deps.io) { core.runDueJobs() }

    override suspend fun jobs(): List<Job> = withContext(core.deps.io) { core.jobs.list() }
}

/**
 * docs/14 "실행기": the app process is the only thing that runs the queue on Windows, and it calls
 * `runDueJobs()` on four triggers — a job just enqueued, the five-minute timer, the network coming
 * back, and the successor armed from the queue itself.
 *
 * Two invariants, the same two the phone's `WorkflowWorker` and the Mac's `JobRunner` have:
 *
 * 1. **Every pass leaves a successor behind it.** The queue is only ever woken by an event or a
 *    timer, so a pass that ends without arming the next one is a queue that stalls. The successor
 *    is recomputed from the job table after the pass ([NextRun]), with [generation] catching
 *    whatever landed while the pass was running. A pass never cancels the successor: a queue that
 *    reads empty may simply have been read too early.
 * 2. **A pass that found the core busy reshapes nothing.** It saw no queue of its own, so all it
 *    guarantees is that *someone* comes back — shortly after the pass in flight should be done.
 */
class JobRunner(
    private val queue: JobQueue,
    private val scope: CoroutineScope,
    private val logger: Logger,
    /**
     * How the successor is armed, when it is not this runner's own delayed coroutine. A test hands
     * in its own so that nothing actually fires and the delays can be asserted on.
     */
    private val arm: ((Duration) -> Unit)? = null,
    /** The queue as the last completed pass left it — the tray reads `NEEDS_AUTH` off this. */
    private val onPass: (List<Job>) -> Unit = {},
) {
    /**
     * Ticks once per "something became runnable". It is why a pass can tell "the queue is genuinely
     * empty" from "the queue was empty when I looked". Monotonic and never reset: a pass only ever
     * compares its own before-and-after.
     */
    private val generation = AtomicLong()

    private val lock = Any()
    private var successor: kotlinx.coroutines.Job? = null

    /** The last network state seen, so only the *return* of it runs a pass. */
    private var online = true

    /** Triggers (b) and (c), plus a pass now: a job left parked by the last run is due. */
    fun start() {
        scope.launch {
            while (isActive) {
                pass()
                delay(INTERVAL)
            }
        }
        scope.launch {
            online = hasNetwork()
            while (isActive) {
                delay(NETWORK_POLL)
                val now = hasNetwork()
                val returned = now && !online
                online = now
                if (returned) {
                    logger.log(Logger.Level.INFO, "job.network.back")
                    pass()
                }
            }
        }
    }

    /**
     * Trigger (a): a stop that queued a job, a retry, a sign-in that unparked one. The signal goes
     * first, so a pass already in flight can tell that its own view of the queue is stale even if
     * the job landed after it read the table.
     */
    fun jobsDue() {
        signalDue()
        scope.launch { pass() }
    }

    /** Ticks the counter without running a pass — the ordering above is the contract. */
    fun signalDue() {
        generation.incrementAndGet()
    }

    /** One pass, and whatever it decides to arm behind it. */
    suspend fun pass() {
        // Snapshotted before the pass and compared after the queue is read, never in between: a job
        // enqueued while this pass runs lands in neither, and the counter is the only evidence left
        // that it happened.
        val generation = this.generation.get()
        try {
            val summary = queue.runDueJobs()
            if (summary.alreadyRunning) {
                val delay = if (signalled(generation)) Duration.ZERO else FOLLOW_UP
                armNext(delay)
                logger.log(Logger.Level.INFO, "job.pass.busy", mapOf("followUpSec" to delay.inWholeSeconds))
                return
            }
            val jobs = queue.jobs()
            // The compare has to come after the read. A signal that lands while the queue is being
            // read is exactly the race, and comparing first would look right and miss it.
            val delay = if (signalled(generation)) Duration.ZERO else NextRun.delay(jobs, queue.now())
            // Nothing to arm is not the same as cancelling what is there: a stale successor costs
            // one pass that finds nothing and arms nothing in turn, which terminates.
            delay?.let { armNext(it) }
            onPass(jobs)
            logger.log(
                Logger.Level.INFO,
                "job.pass",
                mapOf(
                    "ran" to summary.jobIds.size,
                    "queued" to jobs.size,
                    "nextSec" to (delay?.inWholeSeconds?.toString() ?: "-"),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The pass itself could not happen. The five-minute timer is the retry; arming a
            // successor off a queue we failed to read would be arming it off nothing.
            logger.log(Logger.Level.ERROR, "job.pass.failed", error = e)
        }
    }

    /** True when something became runnable after this pass started, so the queue read is stale. */
    private fun signalled(generation: Long): Boolean = this.generation.get() != generation

    /**
     * Exactly one *pending* successor, always replaced. There is deliberately no way to cancel it: a
     * pass that finds an empty queue arms nothing and leaves whatever is standing alone.
     *
     * The successor lets go of its own reference the moment it stops being a timer and becomes a
     * pass. Without that, a pass that finds the core busy would arm through this and cancel the
     * coroutine it is itself running in — killing the step in flight and leaving its row RUNNING
     * for the next pass to recover. The phone's scheduler has the same rule: `rec-jobs` is KEEP,
     * never REPLACE, because cancelling work in flight spends a retry (docs/11 A5).
     */
    private fun armNext(after: Duration) {
        arm?.let {
            it(after)
            return
        }
        synchronized(lock) {
            successor?.cancel()
            successor = scope.launch {
                delay(after)
                val running = coroutineContext.job
                synchronized(lock) { if (successor === running) successor = null }
                pass()
            }
        }
    }

    /**
     * docs/14 "실행기" (c). There is no `NWPathMonitor` on the JVM, so it is polled: an interface
     * that is up, not loopback and has an address is as much as `java.net` can say about whether
     * this machine is on a network. A false positive costs one pass that fails and retries.
     */
    private suspend fun hasNetwork(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            NetworkInterface.networkInterfaces().anyMatch { candidate ->
                candidate.isUp && !candidate.isLoopback && candidate.inetAddresses().findAny().isPresent
            }
        }.getOrDefault(true)
    }

    companion object {
        /** docs/14 "실행기": (b), the standing five-minute timer. */
        val INTERVAL: Duration = 5.minutes

        /**
         * How long after a pass that found the core already busy to come back. Long enough that the
         * pass in flight has usually finished and armed its own successor (which replaces this
         * one), short enough that a pass killed mid-flight is picked up again.
         */
        val FOLLOW_UP: Duration = 60.seconds

        internal val NETWORK_POLL: Duration = 30.seconds
    }
}
