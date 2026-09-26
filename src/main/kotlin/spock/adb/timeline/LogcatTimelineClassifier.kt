package spock.adb.timeline

import spock.adb.logcat.AppProcessTracker
import spock.adb.logcat.AppProcesses
import spock.adb.logcat.LogLevel
import spock.adb.logcat.LogcatEntry

/**
 * Decides which device log lines are timeline events, and what they say.
 *
 * The recorder asks logcat for warnings and errors from every process plus a handful of
 * lifecycle tags; this keeps the ones about [packageName]:
 *
 * - its process starting, dying and being killed, from ActivityManager;
 * - its activities' lifecycle, from the `wm_on_*_called` event tags (`am_on_*_called` before
 *   Android 9), which the app's own process writes;
 * - a crash or an ANR, from the `am_crash` and `am_anr` event tags;
 * - a warning or error the app's own process logged.
 *
 * One `Log.e(tag, msg, throwable)` arrives as a line per stack frame. Lines that continue the
 * previous one — same process, thread, tag, level and stamp — are folded into one event, so a
 * crash is one row with its trace in the detail rather than forty rows. That is why events come
 * out of [accept] and [flush] rather than one per line: the last one is held until something
 * else arrives.
 *
 * Not thread-safe; the recorder serialises calls.
 */
class LogcatTimelineClassifier(
    private val packageName: String,
    initialPids: Set<Int>,
    private val deviceSerial: String? = null,
) {

    var processes: AppProcesses = AppProcesses(AppProcesses.State.UNKNOWN, packageName = packageName)
        .withPids(initialPids)
        private set

    private var pending: Pending? = null

    /** Events [entry] completes, in order; [hostMs] is when it happened on the host's clock. */
    fun accept(entry: LogcatEntry, hostMs: Long): List<TimelineEvent> {
        val held = pending
        if (held != null && held.continuedBy(entry)) {
            held.lines += entry.message
            return emptyList()
        }
        val out = flush().toMutableList()
        classify(entry, hostMs)?.let(out::add)
        return out
    }

    /** The event held back in case more of its lines arrived. */
    fun flush(): List<TimelineEvent> {
        val held = pending ?: return emptyList()
        pending = null
        return listOf(held.toEvent())
    }

    /** Whether an event is being held back. */
    val hasPending: Boolean get() = pending != null

    private fun classify(entry: LogcatEntry, hostMs: Long): TimelineEvent? =
        process(entry, hostMs)
            ?: lifecycle(entry, hostMs)
            ?: failure(entry, hostMs)
            ?: log(entry, hostMs)

    /** The app's process starting or dying, which also keeps [processes] current. */
    private fun process(entry: LogcatEntry, hostMs: Long): TimelineEvent? {
        val started = AppProcessTracker.startedPid(entry, packageName)?.takeIf { !processes.contains(it) }
        if (started != null) {
            processes = processes.plus(started)
            return event(entry, hostMs, APP_INFO, "Process started (pid $started)")
        }
        val died = (AppProcessTracker.diedPid(entry, packageName) ?: killedPid(entry))
            ?.takeIf { processes.contains(it) }
        if (died != null) {
            processes = processes.minus(died)
            return event(entry, hostMs, APP_WARNING, "Process died (pid $died)")
        }
        return null
    }

    private fun lifecycle(entry: LogcatEntry, hostMs: Long): TimelineEvent? {
        val step = LIFECYCLE_TAG.matchEntire(entry.tag)?.groupValues?.get(STEP) ?: return null
        val fields = fields(entry.message)
        val activity = fields.firstOrNull { CLASS_NAME.matches(it) } ?: return null
        // The app's own process writes these, so the PID says whose activity it is. A class name
        // need not start with the application id, so it cannot.
        val ours = processes.contains(entry.pid) ||
            (!processes.isResolved && activity.startsWith("$packageName."))
        if (!ours) return null
        val verb = STEP_VERBS[step] ?: step
        return event(entry, hostMs, ACTIVITY_INFO, "${activity.substringAfterLast('.')} $verb", detail = activity)
    }

    private fun failure(entry: LogcatEntry, hostMs: Long): TimelineEvent? {
        val fields = fields(entry.message)
        return when (entry.tag) {
            // [pid, user, process, flags, exception class, message, file, line, …]
            "am_crash" -> fields.takeIf { isOurProcess(it.getOrNull(CRASH_PROCESS)) }?.let {
                val exception = it.getOrNull(CRASH_EXCEPTION).orEmpty().substringAfterLast('.')
                val message = it.getOrNull(CRASH_MESSAGE).orEmpty()
                event(entry, hostMs, APP_ERROR, "Crashed: $exception${if (message.isBlank()) "" else ": $message"}")
            }
            // [user, pid, process, flags, reason]
            "am_anr" -> fields.takeIf { isOurProcess(it.getOrNull(ANR_PROCESS)) }?.let {
                event(entry, hostMs, APP_ERROR, "ANR: ${it.getOrNull(ANR_REASON).orEmpty()}".trimEnd(' ', ':'))
            }
            else -> null
        }
    }

    private fun log(entry: LogcatEntry, hostMs: Long): TimelineEvent? {
        if (!entry.level.isAtLeast(LogLevel.WARN)) return null
        if (!processes.contains(entry.pid)) return null
        val severity = if (entry.level == LogLevel.WARN) TimelineSeverity.WARNING else TimelineSeverity.ERROR
        pending = Pending(entry, hostMs, severity, deviceSerial)
        return null
    }

    /**
     * `Killing 16878:com.example.app/u0a182 (adj 900): stop com.example.app …` — a force-stop or a
     * kill for memory, which ActivityManager reports this way rather than as "has died".
     */
    private fun killedPid(entry: LogcatEntry): Int? {
        val match = KILLING.find(entry.message) ?: return null
        return match.groupValues[KILLED_PID].toIntOrNull()?.takeIf { match.groupValues[KILLED_PROCESS] == packageName }
    }

    private fun isOurProcess(process: String?): Boolean =
        process != null && (process == packageName || process.startsWith("$packageName:"))

    /** What an event is: its category and how severe. */
    private data class Kind(val category: TimelineCategory, val severity: TimelineSeverity)

    private fun event(
        entry: LogcatEntry,
        hostMs: Long,
        kind: Kind,
        title: String,
        detail: String = entry.raw,
    ) = TimelineEvent(
        timeMs = hostMs,
        category = kind.category,
        severity = kind.severity,
        title = title,
        detail = detail,
        deviceSerial = deviceSerial,
        deviceTime = entry.timestamp.ifBlank { null },
    )

    /** A log event still collecting the lines of the same call. */
    private class Pending(
        val first: LogcatEntry,
        val hostMs: Long,
        val severity: TimelineSeverity,
        val deviceSerial: String?,
    ) {
        val lines = mutableListOf(first.message)

        fun continuedBy(next: LogcatEntry): Boolean =
            next.pid == first.pid && next.tid == first.tid && next.tag == first.tag &&
                next.level == first.level && next.timestamp == first.timestamp

        fun toEvent() = TimelineEvent(
            timeMs = hostMs,
            category = TimelineCategory.LOG,
            severity = severity,
            title = "${first.tag}: ${first.message}".take(TITLE_LIMIT),
            detail = "${first.tag} (pid ${first.pid}, tid ${first.tid})\n" + lines.joinToString("\n"),
            deviceSerial = deviceSerial,
            deviceTime = first.timestamp.ifBlank { null },
        )
    }

    private companion object {
        val APP_INFO = Kind(TimelineCategory.APP_LIFECYCLE, TimelineSeverity.INFO)
        val APP_WARNING = Kind(TimelineCategory.APP_LIFECYCLE, TimelineSeverity.WARNING)
        val APP_ERROR = Kind(TimelineCategory.APP_LIFECYCLE, TimelineSeverity.ERROR)
        val ACTIVITY_INFO = Kind(TimelineCategory.ACTIVITY, TimelineSeverity.INFO)

        const val TITLE_LIMIT = 200
        const val STEP = 2
        const val CRASH_PROCESS = 2
        const val CRASH_EXCEPTION = 4
        const val CRASH_MESSAGE = 5
        const val ANR_PROCESS = 2
        const val ANR_REASON = 4

        const val KILLED_PID = 1
        const val KILLED_PROCESS = 2
        val KILLING = Regex("""^Killing (\d+):([A-Za-z0-9_.]+)/""")
        val LIFECYCLE_TAG = Regex("""(wm|am)_on_(create|start|restart|resume|paused|stop|destroy)_called""")
        val CLASS_NAME = Regex("""[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)+""")
        val STEP_VERBS = mapOf(
            "create" to "created",
            "start" to "started",
            "restart" to "restarted",
            "resume" to "resumed",
            "paused" to "paused",
            "stop" to "stopped",
            "destroy" to "destroyed",
        )

        /** `[a,b,c]` — an event-log line's fields. Commas inside a field are not escaped, so this is best effort. */
        fun fields(message: String): List<String> {
            val trimmed = message.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
            return trimmed.substring(1, trimmed.length - 1).split(',').map { it.trim() }
        }
    }
}
