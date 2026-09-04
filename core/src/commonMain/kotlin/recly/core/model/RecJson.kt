package recly.core.model

import kotlinx.serialization.json.Json

internal val recJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
