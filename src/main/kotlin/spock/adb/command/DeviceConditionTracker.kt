package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One device-wide change Spock made, which outlives the session unless reset. */
sealed interface DeviceCondition {
    /** Deep Doze forced with `dumpsys deviceidle force-idle`. */
    data object Doze : DeviceCondition

    /** The battery overridden with `dumpsys battery unplug`. */
    data object Battery : DeviceCondition

    /** An app moved out of its own standby bucket. */
    data class Bucket(val packageName: String) : DeviceCondition

    fun describe(): String = when (this) {
        Doze -> "forced Doze"
        Battery -> "battery reported unplugged"
        is Bucket -> "$packageName in a forced standby bucket"
    }
}

/**
 * What Spock has changed on each device, whichever way it was changed — the Background Work tab
 * or an agent over MCP — so both can show it and Reset can undo it.
 *
 * Application-wide, because the device is: two open projects and an agent all act on the same
 * phone, and a condition one of them set is live for all of them.
 *
 * The last project to close resets what is still set: see
 * [spock.adb.backgroundwork.DeviceConditionsCloseListener].
 *
 * In memory only. After an IDE restart the device may still be in a forced state, but a reboot
 * of the device clears Doze and the battery override, and Reset returns them to normal whether
 * or not they are on this list.
 */
object DeviceConditionTracker {

    private val log = Logger.getInstance(DeviceConditionTracker::class.java)

    private class Entry(@Volatile var device: IDevice, val conditions: MutableSet<DeviceCondition>)

    private val bySerial = ConcurrentHashMap<String, Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun record(device: IDevice, condition: DeviceCondition) {
        val entry = bySerial.compute(device.serialNumber) { _, existing ->
            (existing ?: Entry(device, ConcurrentHashMap.newKeySet())).also { it.device = device }
        }
        if (entry!!.conditions.add(condition)) notifyListeners()
    }

    fun conditions(serial: String): Set<DeviceCondition> = bySerial[serial]?.conditions?.toSet().orEmpty()

    fun forget(serial: String, vararg conditions: DeviceCondition) {
        val entry = bySerial[serial] ?: return
        val changed = entry.conditions.removeAll(conditions.toSet())
        if (entry.conditions.isEmpty()) bySerial.remove(serial)
        if (changed) notifyListeners()
    }

    fun clear(serial: String) {
        if (bySerial.remove(serial) != null) notifyListeners()
    }

    /**
     * Drops what the device shows is no longer set — after a reboot, or a reset from a terminal —
     * so the banner does not claim a condition the device has already shed.
     *
     * Buckets are left alone: an app's own bucket drifts, so its value cannot say whether Spock's
     * change is still in force.
     */
    fun reconcile(serial: String, observed: DeviceConditions) {
        val entry = bySerial[serial] ?: return
        var changed = false
        if (!observed.dozing) changed = entry.conditions.remove(DeviceCondition.Doze) or changed
        if (!observed.batteryOverridden) changed = entry.conditions.remove(DeviceCondition.Battery) or changed
        if (entry.conditions.isEmpty()) bySerial.remove(serial)
        if (changed) notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** True when a device with a condition Spock set is online, so a reset can reach it. */
    fun hasOnlineChanges(): Boolean = bySerial.values.any { it.device.isOnline && it.conditions.isNotEmpty() }

    /**
     * Resets every device that still has a condition and is online, on the calling thread.
     *
     * A device that is offline cannot be reset. Its conditions stay on the list, and the banner
     * comes back if it reconnects in this session.
     */
    fun resetAllOnline() {
        bySerial.values.toList().forEach { entry ->
            val device = entry.device
            if (!device.isOnline) return@forEach
            runCatching { device.resetDeviceConditions(null) }
                .onSuccess { log.info("Reset device conditions on ${device.serialNumber}: $it") }
                .onFailure { log.warn("Could not reset device conditions on ${device.serialNumber}", it) }
        }
    }

    private fun notifyListeners() = listeners.forEach { it() }

    /** For tests: forget everything. */
    internal fun resetForTests() {
        bySerial.clear()
        listeners.clear()
    }
}
