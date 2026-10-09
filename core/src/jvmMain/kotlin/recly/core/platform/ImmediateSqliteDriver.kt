package recly.core.platform

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.sql.Connection
import java.util.Properties

/**
 * SQLDelight's [JdbcSqliteDriver] with one difference: a transaction begins with `BEGIN IMMEDIATE`, so it
 * holds the write lock from its start.
 *
 * The driver gives every thread its own connection to the file and begins transactions with a plain
 * (deferred) `BEGIN`. Two such transactions that both read before they write hold the shared lock together,
 * and SQLite answers one of the writes with SQLITE_BUSY at once — no busy timeout applies to that
 * deadlock. An immediate transaction instead waits, up to the connection's busy timeout, for the other
 * to commit. The driver is final, so this one hands everything else to it, the transaction it tracks
 * per thread included: that is what keeps it from closing a connection in the middle of a transaction.
 */
internal class ImmediateSqliteDriver(url: String, properties: Properties) : JdbcDriver() {

    private val sqlite = JdbcSqliteDriver(url, properties)

    override fun getConnection(): Connection = sqlite.getConnection()

    override fun closeConnection(connection: Connection) = sqlite.closeConnection(connection)

    override var transaction: ConnectionManager.Transaction?
        get() = sqlite.transaction
        set(value) {
            sqlite.transaction = value
        }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN IMMEDIATE TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() = with(sqlite) { endTransaction() }

    override fun Connection.rollbackTransaction() = with(sqlite) { rollbackTransaction() }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        sqlite.addListener(*queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        sqlite.removeListener(*queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) = sqlite.notifyListeners(*queryKeys)

    override fun close() = sqlite.close()
}
