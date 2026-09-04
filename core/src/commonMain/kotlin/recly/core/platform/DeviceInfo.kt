package recly.core.platform

import recly.core.model.Platform

/** [deviceId] is a UUID v4 minted at install time and kept in [SecureStore]; a reinstall gets a new one. */
data class DeviceInfo(
    val deviceId: String,
    val platform: Platform,
    val name: String,
)
