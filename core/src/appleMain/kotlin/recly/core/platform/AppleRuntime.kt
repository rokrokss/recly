package recly.core.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import recly.core.DriverFactory
import recly.core.ReclyCore
import recly.core.db.RecDatabase

object AppleRuntime {
    /**
     * What a shell hands to `ReclyCore(deps, driverFactory)`. Handed the schema, the native driver
     * keeps `user_version` itself: `create` on a new file, `migrate` on one left by an older build
     * (docs/10 "스키마 마이그레이션").
     */
    fun driverFactory(name: String): DriverFactory = object : DriverFactory {
        override fun create(): SqlDriver = NativeSqliteDriver(RecDatabase.Schema, name)
    }

    /**
     * The same, with the SQLite file under [basePath] instead of sqliter's default directory
     * (`~/Library/Application Support/databases/` on macOS). A shell passes `CoreDeps.dataDir`, so
     * the database sits next to the parts and `meta.json` it belongs with.
     *
     * `onConfiguration` is the driver's own hook for this — going through it keeps SQLDelight's
     * `create`/`upgrade` wiring rather than restating it around a hand-built `DatabaseConfiguration`.
     */
    fun driverFactory(name: String, basePath: String): DriverFactory = object : DriverFactory {
        override fun create(): SqlDriver = NativeSqliteDriver(
            RecDatabase.Schema,
            name,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig = configuration.extendedConfig.copy(basePath = basePath),
                )
            },
        )
    }

    /**
     * `ReclyCore(deps, driverFactory)` for a shell that cannot survive the alternative. Two things
     * are wrong with calling the constructor from Swift directly:
     *
     * - an Obj-C initialiser carries no `NSError**`, so a Kotlin constructor that throws aborts the
     *   process. `@Throws` here is what turns that into an error Swift can catch.
     * - `NativeSqliteDriver` hands back a driver without touching the file. It opens the database —
     *   and runs the schema's `create` — when a statement first borrows a connection, which happens
     *   inside a coroutine on `deps.io`; a `SQLiteExceptionErrorCode` there is an *uncaught* Kotlin
     *   exception on a worker thread and kills the process just the same. So the open is forced
     *   here, on the caller's thread, inside the `@Throws`.
     */
    @Throws(Throwable::class)
    fun openCore(deps: CoreDeps, name: String, basePath: String): ReclyCore {
        val driver = driverFactory(name, basePath).create()
        // Cheapest statement that borrows a connection. `executeQuery`, not `execute`: a PRAGMA
        // read returns a row, and sqliter rejects those on the execute path.
        driver.executeQuery(null, "PRAGMA user_version", { QueryResult.Value(Unit) }, 0)
        return ReclyCore(deps, object : DriverFactory {
            override fun create(): SqlDriver = driver
        })
    }

    fun openDatabase(name: String): RecDatabase = RecDatabase(driverFactory(name).create())

    /**
     * `TokenProvider.accessToken()` called the way the core calls it, for a shell that needs to see
     * what its own provider produces.
     *
     * A Swift `TokenProvider` throwing [AuthRequiredException] only helps if the exception survives
     * the trip back into Kotlin as *itself*: `Executor` parks the job in `NEEDS_AUTH` on
     * `catch (e: AuthRequiredException)` and retries anything else. Nothing in the app calls this —
     * it is the probe that proves the boundary, and `@Throws` is what carries the Kotlin exception
     * back out to Swift (inside the `NSError`, as `userInfo["KotlinException"]`).
     *
     * `runBlocking`: the answer has to arrive on the caller's thread for the throw to be catchable
     * there — a coroutine launched instead would take the process down with an unhandled exception.
     */
    @Throws(Throwable::class)
    fun probeAccessToken(provider: TokenProvider): String = runBlocking { provider.accessToken() }

    fun httpClient(): HttpClient = HttpClient(Darwin)

    /**
     * `CoreDeps.io`. The Android shell writes `Dispatchers.IO` itself, but `Dispatchers` is a
     * Kotlin object that the Obj-C export does not carry, so a Swift shell has no way to name one.
     * `Dispatchers.IO` is internal on Kotlin/Native, and one worker is what `CoreDeps.io` is for
     * anyway — the SQLDelight native driver is not safe to use from several threads at once.
     */
    fun ioDispatcher(): CoroutineDispatcher = io

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
