package spock.adb.logcat

/**
 * Keeps the app's PIDs current by reading the log itself.
 *
 * `pidof` answers once, when Live starts. An Android process restarts constantly during
 * development — Apply Changes, a force-stop, a crash, the system reclaiming memory — and after
 * any of those the App scope was still filtering on a PID that no longer exists, so the app's
 * own new logs were the ones being hidden. Silently: the panel looked like an app that had
 * stopped logging.
 *
 * The device already announces both events, and ActivityManager puts the PID in the line, so
 * this needs no extra `adb` call and costs one regex per line that mentions the package.
 */
object AppProcessTracker {

    /** `Start proc 3189:com.example.app/u0a188 for activity …` — modern ActivityManager. */
    private val START_WITH_PID = Regex("""Start proc (\d+):([A-Za-z0-9_.]+)[/:]""")

    /** `Start proc com.example.app for activity … pid=3189` — older devices. */
    private val START_PID_SUFFIX = Regex("""Start proc ([A-Za-z0-9_.]+).*?\bpid=(\d+)""")

    /** `Process com.example.app (pid 3189) has died` and its variants. */
    private val DIED = Regex("""Process ([A-Za-z0-9_.]+) \(pid (\d+)\)""")

    /** The PID this line says the app just started as, or null. */
    fun startedPid(entry: LogcatEntry, packageName: String): Int? {
        if (packageName.isBlank()) return null

        START_WITH_PID.find(entry.message)?.let { match ->
            if (match.groupValues[PACKAGE_SECOND] == packageName) return match.groupValues[PID_FIRST].toIntOrNull()
        }
        START_PID_SUFFIX.find(entry.message)?.let { match ->
            if (match.groupValues[PACKAGE_FIRST] == packageName) return match.groupValues[PID_SECOND].toIntOrNull()
        }
        return null
    }

    /** The PID this line says died, or null. */
    fun diedPid(entry: LogcatEntry, packageName: String): Int? {
        if (packageName.isBlank()) return null
        val match = DIED.find(entry.message) ?: return null
        if (match.groupValues[PACKAGE_FIRST] != packageName) return null
        return match.groupValues[PID_SECOND].toIntOrNull()
    }

    /**
     * The app's processes after taking [entry] into account, or null when it changes nothing.
     *
     * Null rather than the unchanged value so the caller can skip a round trip to the EDT for
     * the overwhelming majority of lines, which say nothing about process lifecycle.
     */
    fun apply(current: AppProcesses, entry: LogcatEntry): AppProcesses? {
        val packageName = current.packageName
        startedPid(entry, packageName)?.let { pid ->
            return if (current.contains(pid)) null else current.plus(pid)
        }
        diedPid(entry, packageName)?.let { pid ->
            return if (current.contains(pid)) current.minus(pid) else null
        }
        return null
    }

    private const val PID_FIRST = 1
    private const val PACKAGE_SECOND = 2
    private const val PACKAGE_FIRST = 1
    private const val PID_SECOND = 2
}
