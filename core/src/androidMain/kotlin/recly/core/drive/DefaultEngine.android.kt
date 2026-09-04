package recly.core.drive

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun defaultEngine(): HttpClientEngineFactory<*> = OkHttp
