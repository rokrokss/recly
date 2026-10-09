package recly.core.platform

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmRuntimeTest {

    @Test
    fun `two threads that each read then write in a transaction both commit`() {
        // The JDBC driver gives every thread its own connection to the file. Two deferred transactions that
        // both read before they write hold the shared lock together, and SQLite answers the second write
        // with SQLITE_BUSY at once rather than wait — the lock error a settings capture died of under load.
        val file = Files.createTempDirectory("rec-jvm-runtime").resolve("rec.db")
        val db = JvmRuntime.openDatabase(file.toString())
        val bothRead = CountDownLatch(2)
        val failures = mutableListOf<Throwable>()

        val writers = listOf("a", "b").map { key ->
            thread(name = "writer-$key") {
                try {
                    db.transaction {
                        db.recQueries.syncGet(key).executeAsOneOrNull()
                        bothRead.countDown()
                        // Wait for the other reader, but not forever: a transaction that takes the write lock
                        // at its start keeps the other one from reading until it commits.
                        bothRead.await(1, TimeUnit.SECONDS)
                        db.recQueries.syncSet(key, "value-$key")
                    }
                } catch (failure: Throwable) {
                    synchronized(failures) { failures += failure }
                }
            }
        }
        writers.forEach { it.join(10_000) }

        assertTrue(failures.isEmpty(), "both transactions commit: $failures")
        assertEquals("value-a", db.recQueries.syncGet("a").executeAsOneOrNull())
        assertEquals("value-b", db.recQueries.syncGet("b").executeAsOneOrNull())
    }
}
