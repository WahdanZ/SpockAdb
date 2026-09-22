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

/**
 * A charger the framework reports separately, and `dumpsys battery set` can switch on its own.
 *
 * Separate rather than one "plugged in" flag because they are not interchangeable: an app that
 * only syncs on AC behaves differently from one that accepts USB, and testing "discharging while
 * still connected over USB" means turning AC off and leaving USB alone.
 */
enum class ChargerSource(val argument: String, val label: String) {
    AC("ac", "AC"),
    USB("usb", "USB"),
    WIRELESS("wireless", "Wireless"),
    ;

    companion object {
        fun fromDumpsysName(name: String): ChargerSource? =
            entries.firstOrNull { it.argument.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * The battery levels the Background Work tab offers in one click.
 *
 * Levels rather than a free number because these are the thresholds Android itself reacts at:
 * 15% is where the low-battery warning and automatic Battery Saver land on a stock device, so 5%
 * and 20% sit either side of it, and 100% is the "nothing is constrained" baseline.
 */
// The percentages are the point of the presets, not tunable constants.
@Suppress("MagicNumber")
enum class BatteryLevelPreset(val level: Int, val label: String, val note: String) {
    CRITICAL(5, "5%", "critical"),
    LOW(20, "20%", "low"),
    HALF(50, "50%", "half"),
    FULL(100, "100%", "full"),
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
    /** What each charger the device names is reporting; empty when the dump gave none. */
    val chargers: Map<ChargerSource, Boolean> = emptyMap(),
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
// One function per shell command and per thing parsed out of its output. Splitting it to satisfy
// the threshold would scatter commands that are read and reset together.
@Suppress("TooManyFunctions")
internal object DeviceConditionShell {

    /** App Standby buckets arrived in Android 9. */
    const val MIN_BUCKET_API = 28

    /** The restricted bucket arrived in Android 11. */
    const val MIN_RESTRICTED_API = 30

    /** `dumpsys battery unplug` arrived in Android 6, with Doze. */
    const val MIN_DOZE_API = 23

    /** `dumpsys battery set level` is as old as the battery override itself. */
    const val MIN_BATTERY_API = 23

    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 100

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
    private val chargerRegex =
        Regex("""^\s*(AC|USB|Wireless) powered:\s*(true|false)""", RegexOption.MULTILINE)

    private val poweredRegex = Regex("""^\s*(?:AC|USB|Wireless|Dock) powered:\s*(true|false)""", RegexOption.MULTILINE)

    /** @throws IllegalArgumentException when [level] is not a percentage. */
    fun setLevelCommand(level: Int): String {
        require(level in MIN_LEVEL..MAX_LEVEL) { "A battery level is $MIN_LEVEL-$MAX_LEVEL, not $level." }
        return "dumpsys battery set level $level"
    }

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

    /** Each charger the dump names, so the toggles show what the device actually reports. */
    fun parseChargers(output: String): Map<ChargerSource, Boolean> =
        chargerRegex.findAll(output).mapNotNull { match ->
            ChargerSource.fromDumpsysName(match.groupValues[1])?.let { it to (match.groupValues[2] == "true") }
        }.toMap()

    fun setChargerCommand(source: ChargerSource, connected: Boolean): String =
        "dumpsys battery set ${source.argument} ${if (connected) 1 else 0}"

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

    fun batteryUnavailableReason(apiLevel: Int?): String? = when {
        apiLevel == null || apiLevel >= MIN_BATTERY_API -> null
        else -> "Overriding the battery arrived in Android 6.0 (API 23). This device is API $apiLevel."
    }

    /**
     * What to say when the battery did not end up at [wanted].
     *
     * `dumpsys battery set level` prints nothing either way, so only reading it back tells whether
     * it took. Some vendor ROMs keep their own battery service and ignore the override.
     */
    fun levelRefusal(wanted: Int, actual: Int?): String? = when (actual) {
        wanted -> null
        null -> "Set the battery to $wanted%, but the device did not report a level afterwards."
        else ->
            "Asked for $wanted%, but the device still reports $actual%. Some vendor ROMs keep their own " +
                "battery service and ignore dumpsys battery set level."
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
    val battery = shell(DeviceConditionShell.BATTERY_COMMAND)
    val (overridden, powered, level) = DeviceConditionShell.parseBattery(battery)
    val bucket = packageName
        ?.takeIf { (apiLevel() ?: 0) >= DeviceConditionShell.MIN_BUCKET_API }
        ?.let { DeviceConditionShell.parseBucket(shell(DeviceConditionShell.getBucketCommand(it))) }
    return DeviceConditions(
        deepIdle = DeviceConditionShell.parseDeepIdle(shell(DeviceConditionShell.DEEP_IDLE_COMMAND)),
        bucket = bucket,
        batteryOverridden = overridden,
        powered = powered,
        batteryLevel = level,
        chargers = DeviceConditionShell.parseChargers(battery),
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

/**
 * Reports the battery at [level] and discharging, then reads it back.
 *
 * The battery is unplugged first because a level on its own changes little: Android's low-battery
 * warning, automatic Battery Saver and the job scheduler's charging constraints all key off a
 * device that is discharging, so a phone reporting 5% while plugged in behaves like a full one.
 * That makes one click enough to reproduce what a user at that level sees.
 *
 * Recorded before the device is asked, so a failure half way still leaves the change on the list
 * Reset works from.
 *
 * @throws IllegalStateException when the device kept a different level.
 */
internal fun IDevice.setBatteryLevel(level: Int): String {
    DeviceConditionShell.batteryUnavailableReason(apiLevel())?.let { error(it) }
    val command = DeviceConditionShell.setLevelCommand(level)
    DeviceConditionTracker.record(this, DeviceCondition.Battery)
    shell(DeviceConditionShell.UNPLUG_COMMAND)
    shell(command)
    val (_, _, actual) = DeviceConditionShell.parseBattery(shell(DeviceConditionShell.BATTERY_COMMAND))
    DeviceConditionShell.levelRefusal(level, actual)?.let { error(it) }
    return "The battery reports $level% and discharging."
}

/**
 * Connects or disconnects one charger, leaving the others and the level alone.
 *
 * The point of doing them separately is the case a single unplug cannot express: an app on a desk
 * charger that still has USB for adb, or one that only syncs on AC. The state is read back,
 * because `dumpsys battery set` prints nothing either way.
 *
 * @throws IllegalStateException when the device reports the charger the other way afterwards.
 */
internal fun IDevice.setCharger(source: ChargerSource, connected: Boolean): String {
    DeviceConditionShell.batteryUnavailableReason(apiLevel())?.let { error(it) }
    DeviceConditionTracker.record(this, DeviceCondition.Battery)
    shell(DeviceConditionShell.setChargerCommand(source, connected))
    val actual = DeviceConditionShell.parseChargers(shell(DeviceConditionShell.BATTERY_COMMAND))[source]
    check(actual == null || actual == connected) {
        "Asked to report ${source.label} ${if (connected) "connected" else "disconnected"}, " +
            "but the device still says the opposite."
    }
    return "${source.label} is reported ${if (connected) "connected" else "disconnected"}."
}

/**
 * Ends the battery override alone, leaving Doze and any bucket as they are.
 *
 * The conditions are read back rather than assumed: plugging the charger back in ends Doze on a
 * real device, so the banner would otherwise keep claiming a forced Doze that is already over.
 */
internal fun IDevice.resetBattery(): String {
    shell(DeviceConditionShell.BATTERY_RESET_COMMAND)
    val conditions = deviceConditions(packageName = null)
    check(!conditions.batteryOverridden) { "Asked to reset the battery, but the device still reports an override." }
    DeviceConditionTracker.forget(serialNumber, DeviceCondition.Battery)
    DeviceConditionTracker.reconcile(serialNumber, conditions)
    return "The battery reads the real hardware again: " +
        (conditions.batteryLevel?.let { "$it%" } ?: "level unknown") +
        if (conditions.powered == true) ", charging." else ", not charging."
}

internal fun IDevice.unplugBattery(): String {
    DeviceConditionShell.batteryUnavailableReason(apiLevel())?.let { error(it) }
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
