package recly.core.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import recly.core.DriverFactory
import recly.core.db.RecDatabase

object AndroidRuntime {
    /**
     * What a shell hands to `ReclyCore(deps, driverFactory)`. Handed the schema, the Android driver
     * keeps `user_version` itself: `create` on a new file, `migrate` on one left by an older build
     * (docs/10 "스키마 마이그레이션").
     */
    fun driverFactory(context: Context, name: String): DriverFactory = object : DriverFactory {
        override fun create(): SqlDriver = AndroidSqliteDriver(RecDatabase.Schema, context, name)
    }

    fun openDatabase(context: Context, name: String): RecDatabase =
        RecDatabase(driverFactory(context, name).create())

    fun httpClient(): HttpClient = HttpClient(OkHttp)
}
