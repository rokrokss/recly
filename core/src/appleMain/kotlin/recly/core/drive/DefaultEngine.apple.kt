package recly.core.drive

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun defaultEngine(): HttpClientEngineFactory<*> = Darwin
