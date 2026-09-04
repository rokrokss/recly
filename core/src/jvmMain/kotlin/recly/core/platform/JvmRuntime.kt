package recly.core.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import recly.core.DriverFactory
import recly.core.db.RecDatabase

/**
 * The JVM half of what `AndroidRuntime` and `AppleRuntime` do for their platforms. The difference
 * is the schema version: the Android and native drivers are handed [RecDatabase.Schema] and keep
 * `user_version` themselves, calling `create` on a new file and `migrate` on an old one. The JDBC
 * driver is handed nothing and does neither, so this is where both live for the desktop.
 */
object JvmRuntime {
    /** What a shell hands to `ReclyCore(deps, driverFactory)`. */
    fun driverFactory(path: String): DriverFactory = object : DriverFactory {
        override fun create(): SqlDriver = openDriver(path)
    }

    fun openDatabase(path: String): RecDatabase = RecDatabase(openDriver(path))

    /**
     * Opens the file at [path] and brings its schema up to date: the whole schema on a database
     * that has none, the migrations in between on one that is behind, nothing at all on one that is
     * current (docs/10 "스키마 마이그레이션").
     */
    fun openDriver(path: String): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:$path").also(::upgrade)

    internal fun upgrade(driver: SqlDriver) {
        val schema = RecDatabase.Schema
        val current = currentVersion(driver)
        when {
            current == schema.version -> return
            current == EMPTY -> schema.create(driver)
            // A database from before the desktop kept a version is a version 1 database: every
            // schema change up to then was made by adding to `Rec.sq` with nothing to record it.
            current > schema.version -> return
            else -> schema.migrate(driver, current, schema.version)
        }
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    }

    /**
     * What the file says it is. `user_version` is zero both for a database nobody has written and
     * for one this app wrote before it kept the number, so the tables settle it: no tables means
     * new, tables mean [LEGACY].
     */
    private fun currentVersion(driver: SqlDriver): Long {
        val stamped = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            parameters = 0,
        ).value
        if (stamped > 0L) return stamped
        val tables = driver.executeQuery(
            identifier = null,
            sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            parameters = 0,
        ).value
        return if (tables == 0L) EMPTY else LEGACY
    }

    /** No schema at all: a file that has just been created, or one that was never written to. */
    private const val EMPTY = 0L

    /** Written before the desktop stamped a version — the schema as it stood at version 1. */
    private const val LEGACY = 1L
}
