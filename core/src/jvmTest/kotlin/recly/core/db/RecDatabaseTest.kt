package recly.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecDatabaseTest {
    @Test
    fun kvRoundTrips() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RecDatabase.Schema.create(driver)
        val db = RecDatabase(driver)

        assertNull(db.recQueries.kvGet("deviceId").executeAsOneOrNull())
        db.recQueries.kvSet("deviceId", "7c1e4b2a")
        assertEquals("7c1e4b2a", db.recQueries.kvGet("deviceId").executeAsOne())
        db.recQueries.kvSet("deviceId", "0d3f4a7e")
        assertEquals("0d3f4a7e", db.recQueries.kvGet("deviceId").executeAsOne())

        driver.close()
    }
}
