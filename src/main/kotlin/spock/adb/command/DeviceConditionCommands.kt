package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.apiLevel
import java.util.concurrent.TimeUnit

/**
 * App Standby buckets, by the number `am get-standby-bucket` prints and the name
 * `am set-standby-bucket` takes.
 */
// The codes are Android's own UsageStatsManager.STANDBY_BUCKET_* values, not tunable numbers.
@Suppress("MagicNumber")
enum class StandbyBucket(val code: Int, val argument: String, val label: String) {
    EXEMPTED(5, "exempted", "Exempted"),
    ACTIVE(10, "active", "Active"),
    WORKING_SET(20, "working_set", "Working set"),
    FREQUENT(30, "frequent", "Frequent"),
    RARE(40, "rare", "Rare"),
    RESTRICTED(45, "restricted", "Restricted"),
    NEVER(50, "never", "Never"),
    ;

    companion object {
        /** The buckets a developer can put an app in; the rest are the system's own. */
        val SETTABLE = listOf(ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED)

        fun fromCode(code: Int): StandbyBucket? = entries.firstOrNull { it.code == code }

        fun fromArgument(name: String): StandbyBucket? =
            entries.firstOrNull {
                it.argument.equals(name.trim(), ignoreCase = true) || it.name.equals(name.trim(), ignoreCase = true)
            }
    }
}

/** What the device says about the conditions background work reacts to. */
data class DeviceConditions(
    /** `IDLE`, `ACTIVE`, … from `dumpsys deviceidle get deep`, or null when it gave no state. */
    val deepIdle: String?,
    /** The app's bucket, or null before API 28 or when the device gave no number. */
    val bucket: StandbyBucket?,
    /** True when `dumpsys battery` has been overridden and stopped taking real updates. */
    val batteryOverridden: Boolean,
    /** True when any charger is reported connected. */
    val powered: Boolean?,
    val batteryLevel: Int?,
) {
    val dozing: Boolean get() = deepIdle == "IDLE"
}

/**
 * The shell side of device conditions, kept free of [IDevice] so it is testable without one.
 *
 * Everything here changes device-wide state that outlives the session, which is why every change
 * is read back and every one has a reset: a device left in forced Doze, or an app left in the
 * rare bucket, behaves strangely for whoever uses it next.
 */
internal object DeviceConditionShell {

    /** App Standby buckets arrived in Android 9. */
    const val MIN_BUCKET_API = 28

    /** The restricted bucket arrived in Android 11. */
    const val MIN_RESTRICTED_API = 30

    /** `dumpsys battery unplug` arrived in Android 6, with Doze. */
    const val MIN_DOZE_API = 23

    const val DEEP_IDLE_COMMAND = "dumpsys deviceidle get deep"
    const val BATTERY_COMMAND = "dumpsys battery"
    const val FORCE_IDLE_COMMAND = "dumpsys deviceidle force-idle"
    const val UNFORCE_IDLE_COMMAND = "dumpsys deviceidle unforce"
    const val UNPLUG_COMMAND = "dumpsys battery unplug"
    const val BATTERY_RESET_COMMAND = "dumpsys battery reset"

    /** What `dumpsys battery` prints at its top once it has been overridden. */
    private const val UPDATES_STOPPED = "UPDATES STOPPED"
    private const val FORCED_IDLE = "Now forced in to deep idle mode"

    private val levelRegex = Regex("""^\s*level:\s*(\d+)""", RegexOption.MULTILINE)
    private val poweredRegex = Regex("""^\s*(?:AC|USB|Wireless|Dock) powered:\s*(true|false)""", RegexOption.MULTILINE)

    fun getBucketCommand(packageName: String): String =
        "am get-standby-bucket ${quoted(packageName)}"

    fun setBucketCommand(packageName: String, bucket: StandbyBucket): String =
        "am set-standby-bucket ${quoted(packageName)} ${bucket.argument}"

    private fun quoted(packageName: String) =
        ShellQuote.quote(ShellQuote.requireValidComponent(packageName, "Package name"))

    /** `IDLE` from `dumpsys deviceidle get deep`; null when the output is not one word. */
    fun parseDeepIdle(output: String): String? =
        output.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    /** `40` → RARE. Some releases print the name instead of the number, so both are read. */
    fun parseBucket(output: String): StandbyBucket? {
        val said = output.trim()
        return said.toIntOrNull()?.let(StandbyBucket::fromCode) ?: StandbyBucket.fromArgument(said)
    }

    fun parseBattery(output: String): Triple<Boolean, Boolean?, Int?> {
        val powered = poweredRegex.findAll(output).map { it.groupValues[1] == "true" }.toList()
        return Triple(
            output.contains(UPDATES_STOPPED),
            powered.takeIf { it.isNotEmpty() }?.any { it },
            levelRegex.find(output)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    /** Why [bucket] cannot be set on a device at [apiLevel], or null when it can. */
    fun bucketUnavailableReason(apiLevel: Int?, bucket: StandbyBucket): String? = when {
        apiLevel == null -> null
        apiLevel < MIN_BUCKET_API ->
            "App Standby buckets arrived in Android 9 (API 28). This device is API $apiLevel."
        bucket == StandbyBucket.RESTRICTED && apiLevel < MIN_RESTRICTED_API ->
            "The restricted bucket arrived in Android 11 (API 30). This device is API $apiLevel."
        else -> null
    }

    fun dozeUnavailableReason(apiLevel: Int?): String? = when {
        apiLevel == null || apiLevel >= MIN_DOZE_API -> null
        else -> "Doze arrived in Android 6.0 (API 23). This device is API $apiLevel."
    }

    /** Null when `force-idle` reports deep idle, otherwise what the device said instead. */
    fun forceIdleFailure(output: String): String? = when {
        output.contains(FORCED_IDLE) -> null
        output.isBlank() -> "The device said nothing when asked to force Doze."
        else -> output.trim()
    }

    /**
     * What to say when the device kept the app in [actual] rather than [wanted].
     *
     * `am set-standby-bucket` prints nothing either way, so only reading the bucket back tells
     * whether it took. It often does not: on Android 12+ an app allowed to schedule exact alarms
     * never drops below the working set, and apps in use or exempted keep a higher bucket.
     * Seen on an API 34 emulator: an app holding USE_EXACT_ALARM stayed in WORKING_SET when set
     * to rare and to restricted, while an app without it moved.
     */
    fun bucketRefusal(packageName: String, wanted: StandbyBucket, actual: StandbyBucket?): String? = when {
        actual == wanted -> null
        actual == null -> "Set $packageName to ${wanted.label}, but the device did not report its bucket afterwards."
        else ->
            "Android kept $packageName in ${actual.label} rather than ${wanted.label}. Apps allowed to " +
                "schedule exact alarms (SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM) never drop below " +
                "Working set on Android 12+, and apps in use or exempted keep a higher bucket."
    }
}

private const val TIMEOUT_SECONDS = 20L

private fun IDevice.shell(command: String): String {
    val receiver = ShellOutputReceiver()
    executeShellCommand(command, receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return receiver.toString()
}

/** Reads Doze, the app's bucket and the battery override in three round trips. */
internal fun IDevice.deviceConditions(packageName: String?): DeviceConditions {
    val (overridden, powered, level) = DeviceConditionShell.parseBattery(shell(DeviceConditionShell.BATTERY_COMMAND))
    val bucket = packageName
        ?.takeIf { (apiLevel() ?: 0) >= DeviceConditionShell.MIN_BUCKET_API }
        ?.let { DeviceConditionShell.parseBucket(shell(DeviceConditionShell.getBucketCommand(it))) }
    return DeviceConditions(
        deepIdle = DeviceConditionShell.parseDeepIdle(shell(DeviceConditionShell.DEEP_IDLE_COMMAND)),
        bucket = bucket,
        batteryOverridden = overridden,
        powered = powered,
        batteryLevel = level,
    )
}

/**
 * Puts the device in deep Doze now: unplugs the battery, which Doze requires, then forces idle.
 *
 * Both changes are recorded before the device is asked, so a failure half way still leaves them
 * on the list Reset works from.
 */
internal fun IDevice.forceDoze(): String {
    DeviceConditionShell.dozeUnavailableReason(apiLevel())?.let { error(it) }
    DeviceConditionTracker.record(this, DeviceCondition.Battery)
    shell(DeviceConditionShell.UNPLUG_COMMAND)
    DeviceConditionTracker.record(this, DeviceCondition.Doze)
    DeviceConditionShell.forceIdleFailure(shell(DeviceConditionShell.FORCE_IDLE_COMMAND))?.let {
        error("Could not force Doze: $it")
    }
    val state = DeviceConditionShell.parseDeepIdle(shell(DeviceConditionShell.DEEP_IDLE_COMMAND))
    check(state == "IDLE") { "Asked for deep Doze, but the device reports $state." }
    return "The device is in deep Doze (forced), with the battery reported unplugged."
}

/**
 * Ends forced Doze, and the battery override that forcing it set, leaving any bucket alone.
 */
internal fun IDevice.leaveDoze(): String {
    shell(DeviceConditionShell.UNFORCE_IDLE_COMMAND)
    shell(DeviceConditionShell.BATTERY_RESET_COMMAND)
    DeviceConditionTracker.forget(serialNumber, DeviceCondition.Doze, DeviceCondition.Battery)
    return "The device left Doze and reads the real battery again."
}

internal fun IDevice.unplugBattery(): String {
    DeviceConditionShell.dozeUnavailableReason(apiLevel())?.let { error(it) }
    DeviceConditionTracker.record(this, DeviceCondition.Battery)
    shell(DeviceConditionShell.UNPLUG_COMMAND)
    val (overridden, powered, _) = DeviceConditionShell.parseBattery(shell(DeviceConditionShell.BATTERY_COMMAND))
    check(overridden && powered != true) { "Asked to unplug the battery, but the device still reports a charger." }
    return "The battery is reported unplugged."
}

/**
 * Sets the app's bucket and reads it back.
 *
 * @throws IllegalStateException when the device kept a different bucket, saying why it may have.
 */
internal fun IDevice.setStandbyBucket(packageName: String, bucket: StandbyBucket): String {
    DeviceConditionShell.bucketUnavailableReason(apiLevel(), bucket)?.let { error(it) }
    if (bucket != StandbyBucket.ACTIVE) DeviceConditionTracker.record(this, DeviceCondition.Bucket(packageName))
    shell(DeviceConditionShell.setBucketCommand(packageName, bucket))
    val actual = DeviceConditionShell.parseBucket(shell(DeviceConditionShell.getBucketCommand(packageName)))
    DeviceConditionShell.bucketRefusal(packageName, bucket, actual)?.let { error(it) }
    return "$packageName is in the ${bucket.label} bucket."
}

/**
 * Undoes what Spock changed on this device, and anything it is asked to reset besides.
 *
 * Doze and the battery are reset whether or not Spock recorded changing them: they have one
 * normal state, and a reset that left a device in forced Doze because the record was lost would
 * be the failure this exists to prevent. Buckets have no normal state to return to, so only the
 * apps Spock moved, plus [packageName], are set back to active.
 */
internal fun IDevice.resetDeviceConditions(packageName: String?): String {
    val moved = DeviceConditionTracker.conditions(serialNumber)
        .filterIsInstance<DeviceCondition.Bucket>()
        .map { it.packageName }
    val packages = (moved + listOfNotNull(packageName)).distinct()

    shell(DeviceConditionShell.UNFORCE_IDLE_COMMAND)
    shell(DeviceConditionShell.BATTERY_RESET_COMMAND)
    val bucketsSupported = (apiLevel() ?: 0) >= DeviceConditionShell.MIN_BUCKET_API
    if (bucketsSupported) {
        packages.forEach { shell(DeviceConditionShell.setBucketCommand(it, StandbyBucket.ACTIVE)) }
    }
    DeviceConditionTracker.clear(serialNumber)

    val buckets = if (bucketsSupported && packages.isNotEmpty()) {
        " ${packages.joinToString()} set back to Active."
    } else {
        ""
    }
    return "Doze and the battery are back to normal.$buckets"
}

/** Reads the conditions for the tab. */
class GetDeviceConditionsCommand : Command<String?, DeviceConditions> {
    override fun execute(p: String?, project: Project, device: IDevice): DeviceConditions = device.deviceConditions(p)
}
