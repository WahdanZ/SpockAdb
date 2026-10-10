package spock.adb.flutter

import com.android.ddmlib.IDevice
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

/**
 * Flutter detection, remembered per install.
 *
 * Listing every APK of a package with `unzip -l` is the slow part of reading app info, and its
 * answer only changes when the app is installed again — which changes `versionCode` or
 * `lastUpdateTime` in `dumpsys package`. Keyed on those, a refresh reuses the answer.
 */
class FlutterBuildCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Key(val serial: String, val packageName: String, val versionCode: String, val lastUpdateTime: String)

    /** What a read of an app's build came to: an answer, or adb failing before there was one. */
    sealed interface Detection {
        /** The install was read: [build], or null for an app that is not Flutter. */
        data class Listed(val build: FlutterBuild?) : Detection

        /** `dumpsys package` or the APK listing failed: nothing is known about the app. */
        data object AdbFailed : Detection
    }

    /** Null [build] is an answer too: the install was listed and is not a Flutter app. */
    private data class Detected(val build: FlutterBuild?)

    private val detected = object : LinkedHashMap<Key, Detected>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Detected>?) = size > capacity
    }

    /**
     * How [packageName] on device [serial] was built, running [list] — the output of
     * [FlutterBuild.listingCommand] — only when this install has not been listed before.
     *
     * Best effort: a listing that fails or times out reads as not Flutter and is not remembered,
     * so the next read tries again. Without a version and an update time in [dumpsys] there is no
     * telling one install from the next, so nothing is remembered then either.
     */
    fun detect(serial: String, packageName: String, dumpsys: String, list: () -> String): FlutterBuild? =
        (read(serial, packageName, dumpsys, list) as? Detection.Listed)?.build

    /** [detect], telling a listing that failed from an app that is not Flutter. */
    fun read(serial: String, packageName: String, dumpsys: String, list: () -> String): Detection {
        val key = keyOf(serial, packageName, dumpsys)
        key?.let { synchronized(detected) { detected[it] } }?.let { return Detection.Listed(it.build) }
        val listing = runCatching(list).getOrNull() ?: return Detection.AdbFailed
        val build = FlutterBuild.of(listing, FlutterBuild.isDebuggable(dumpsys))
        key?.let { synchronized(detected) { detected[it] = Detected(build) } }
        return Detection.Listed(build)
    }

    /**
     * [detect] on [device]: reads `dumpsys package` and, for an install not listed before, the
     * APK listing. Null when [packageName] is not a Flutter app, or adb fails. Blocking.
     */
    fun detectOn(device: IDevice, serial: String, packageName: String): FlutterBuild? =
        (readOn(device, serial, packageName) as? Detection.Listed)?.build

    /**
     * [detectOn], telling adb failing from an app that is not Flutter, for a caller that asks
     * again when adb fails and stops when the app is not Flutter. A package that is not installed
     * is not Flutter: its `dumpsys package` answers, with no `versionCode`, and its listing is
     * empty. Blocking.
     */
    fun readOn(device: IDevice, serial: String, packageName: String): Detection {
        val asked = "$serial $packageName"
        synchronized(recent) {
            recent[asked]?.takeIf { clock() - it.first < RECENT_MS }?.let { return Detection.Listed(it.second) }
        }
        val dumpsys = runCatching { shell(device, "dumpsys package ${ShellQuote.quote(packageName)}") }
            .getOrNull() ?: return Detection.AdbFailed
        val read = read(serial, packageName, dumpsys) { shell(device, FlutterBuild.listingCommand(packageName)) }
        if (read is Detection.Listed) {
            synchronized(recent) {
                recent[asked] = clock() to read.build
                if (recent.size > capacity) recent.remove(recent.keys.first())
            }
        }
        return read
    }

    /**
     * The last [detectOn] answer per device and package, with when: the Flutter section and the
     * app section of one report both ask, a moment apart, and `dumpsys package` is not free.
     */
    private val recent = LinkedHashMap<String, Pair<Long, FlutterBuild?>>()

    private fun shell(device: IDevice, command: String): String {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(command, receiver, SHELL_SECONDS, TimeUnit.SECONDS)
        return receiver.toString()
    }

    private fun keyOf(serial: String, packageName: String, dumpsys: String): Key? {
        val versionCode = VERSION_CODE.find(dumpsys)?.groupValues?.get(1) ?: return null
        val updated = LAST_UPDATE_TIME.find(dumpsys)?.groupValues?.get(1)?.trim() ?: return null
        return Key(serial, packageName, versionCode, updated)
    }

    companion object {
        private const val DEFAULT_CAPACITY = 64
        private const val SHELL_SECONDS = 20L

        /** Long enough for one report, short enough that a reinstall is seen at the next. */
        private const val RECENT_MS = 10_000L
        private const val LOAD_FACTOR = 0.75f

        private val VERSION_CODE = Regex("""\bversionCode=(\S+)""")

        /** `lastUpdateTime=2026-10-01 05:31:02`: a date and a time, so the rest of the line. */
        private val LAST_UPDATE_TIME = Regex("""\blastUpdateTime=([^\r\n]+)""")

        /** One for the IDE: the tool window and the agent tools ask about the same installs. */
        val shared = FlutterBuildCache()
    }
}
