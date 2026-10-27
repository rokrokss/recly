package app.recly.windows.record

import app.recly.windows.core.Host
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure

/** One reading of the laptop's battery, as the core's `BatteryStop` reads it: a whole percent, −1 when unreadable. */
data class BatteryReading(val percent: Int, val unplugged: Boolean) {
    companion object {
        /** A reading that failed: it stops nothing, and the next one is taken all the same. */
        val UNREADABLE: BatteryReading = BatteryReading(percent = -1, unplugged = false)
    }
}

/**
 * docs/01 "Long recordings — battery stop" on Windows: where the battery is read from while a recording runs.
 * Null is "this machine has no battery" — a desktop — and it is not asked again for that recording.
 */
fun interface PowerSource {
    fun read(): BatteryReading?
}

/** A desktop, and the development host (docs/14 "Development host (macOS) stand-ins"): nothing to read. */
object NoBattery : PowerSource {
    override fun read(): BatteryReading? = null
}

object PowerSources {
    fun create(): PowerSource = if (Host.isWindows) WindowsPowerStatus else NoBattery
}

/**
 * `GetSystemPowerStatus` (kernel32), declared here: `jna-platform` does not wrap it. Loaded on first use,
 * which on any machine but Windows is never.
 *
 * **Not exercised on the development host** — the call needs Windows; [reading], which turns its three bytes
 * into what the rule reads, is held by `BatteryTest` on the JVM.
 */
object WindowsPowerStatus : PowerSource {

    override fun read(): BatteryReading? {
        val status = SystemPowerStatus()
        if (!lib.GetSystemPowerStatus(status)) return BatteryReading.UNREADABLE
        return reading(
            acLine = status.ACLineStatus.toInt() and 0xff,
            flag = status.BatteryFlag.toInt() and 0xff,
            percent = status.BatteryLifePercent.toInt() and 0xff,
        )
    }

    /**
     * `SYSTEM_POWER_STATUS` as the rule reads it. `BatteryFlag` 128 is "no system battery" (a desktop); 255
     * is "unknown", which has that bit set too and is not the same thing. `BatteryLifePercent` 255 is unknown.
     * Unplugged is `ACLineStatus` 0 (offline) only — unknown (255) stops nothing.
     */
    internal fun reading(acLine: Int, flag: Int, percent: Int): BatteryReading? {
        if (flag != UNKNOWN && flag and NO_SYSTEM_BATTERY != 0) return null
        return BatteryReading(
            percent = if (percent == UNKNOWN || percent > 100) -1 else percent,
            unplugged = acLine == AC_OFFLINE,
        )
    }

    /** `SYSTEM_POWER_STATUS`, field for field and in order — the order is the ABI. */
    @Structure.FieldOrder(
        "ACLineStatus",
        "BatteryFlag",
        "BatteryLifePercent",
        "SystemStatusFlag",
        "BatteryLifeTime",
        "BatteryFullLifeTime",
    )
    class SystemPowerStatus : Structure() {
        @JvmField var ACLineStatus: Byte = 0

        @JvmField var BatteryFlag: Byte = 0

        @JvmField var BatteryLifePercent: Byte = 0

        @JvmField var SystemStatusFlag: Byte = 0

        @JvmField var BatteryLifeTime: Int = 0

        @JvmField var BatteryFullLifeTime: Int = 0
    }

    private interface Kernel32Power : Library {
        fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean
    }

    private val lib: Kernel32Power by lazy { Native.load("kernel32", Kernel32Power::class.java) }

    private const val AC_OFFLINE = 0
    private const val NO_SYSTEM_BATTERY = 128
    private const val UNKNOWN = 255
}
