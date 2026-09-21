package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.command.RunJobRequest
import spock.adb.command.pendingAlarms
import spock.adb.command.runJobNow
import spock.adb.command.scheduledJobs
import spock.adb.isAppInstall
import spock.adb.parser.DumpDurations
import spock.adb.parser.JobSchedulerDumpParser
import spock.adb.parser.PendingAlarm
import spock.adb.parser.ScheduledJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `android_get_scheduled_jobs` — JobScheduler and WorkManager jobs, with what blocks each. */
class GetScheduledJobsTool : AdbTool {
    override val name = "android_get_scheduled_jobs"
    override val description =
        "List an app's scheduled JobScheduler jobs, which include WorkManager workers. For each: " +
            "job id, service, periodic or one-off, required constraints and which are unsatisfied " +
            "right now, backoff and failure count, next and last run. Use this to find out why " +
            "background work is not running."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package whose jobs to list. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }

        val dump = device.scheduledJobs(packageName)
        dump.problem?.let { return ToolResult.error(it) }
        if (dump.jobs.isEmpty()) return ToolResult.text("$packageName has no scheduled jobs.")

        return ToolResult.text(
            "${dump.jobs.size} scheduled job(s) for $packageName:\n\n" +
                dump.jobs.joinToString("\n\n") {
                    BackgroundWorkText.job(it, dump.deviceTimeMillis ?: System.currentTimeMillis())
                },
        )
    }
}

/** `android_get_pending_alarms` — the app's AlarmManager alarms with real clock times. */
class GetPendingAlarmsTool : AdbTool {
    override val name = "android_get_pending_alarms"
    override val description =
        "List an app's pending AlarmManager alarms: type, next trigger as a device clock time, " +
            "repeat interval, whether it is exact, and what it delivers to."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package whose alarms to list. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }

        val dump = device.pendingAlarms(packageName)
        dump.problem?.let { return ToolResult.error(it) }
        if (dump.alarms.isEmpty()) return ToolResult.text("$packageName has no pending alarms.")

        return ToolResult.text(
            "${dump.alarms.size} pending alarm(s) for $packageName:\n\n" +
                dump.alarms.joinToString("\n\n") { BackgroundWorkText.alarm(it) },
        )
    }
}

/** `android_run_job_now` — force a job to run, so its code path can be exercised on demand. */
class RunJobNowTool : AdbTool {
    override val name = "android_run_job_now"
    override val description =
        "Run one of an app's scheduled jobs now, ignoring its constraints (cmd jobscheduler run " +
            "-f). Get the job id from android_get_scheduled_jobs. For a WorkManager job, WorkManager " +
            "itself still skips periodic or backed-off work that is not due, so confirm the Worker " +
            "ran (android_get_logcat) rather than assuming it."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer("jobId", "The job id, as android_get_scheduled_jobs reports it.", required = true)
        string(
            "namespace",
            "The job's namespace, if android_get_scheduled_jobs reported one. Found automatically " +
                "when omitted, which covers WorkManager's namespaced jobs on API 34+.",
        )
        string("packageName", "Package that owns the job. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val jobId = arguments.requiredInt("jobId")
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }

        val request = RunJobRequest(packageName, jobId, arguments.optionalString("namespace"))
        return runCatching { device.runJobNow(request) }.fold(
            onSuccess = { ToolResult.text(it) },
            onFailure = { ToolResult.error("Could not run job $jobId: ${it.message}") },
        )
    }
}

/** How jobs and alarms read as text, for agents. */
internal object BackgroundWorkText {

    fun job(job: ScheduledJob, now: Long = System.currentTimeMillis()): String = buildList {
        add("Job ${job.jobId}${job.namespace?.let { " (namespace $it)" }.orEmpty()} — ${job.service}")
        add("  Kind: ${kind(job)}")
        if (job.isWorkManager) add("  WorkManager: ${workSpec(job)}")
        add("  State: ${state(job)}")
        if (job.required.isNotEmpty()) add("  Requires: ${job.required.joinToString(", ") { describe(it) }}")
        if (job.blockers.isNotEmpty()) add("  Waiting on: ${job.blockers.joinToString(", ") { describe(it) }}")
        nextRun(job, now)?.let { add("  Next run: $it") }
        job.backoff?.let { add("  Backoff: $it") }
        if (job.failures > 0) add("  Failures: ${job.failures}")
        job.lastSuccessfulRun?.let { add("  Last successful run: $it") }
        job.lastFailedRun?.let { add("  Last failed run: $it") }
        job.standbyBucket?.let { add("  Standby bucket: $it") }
    }.joinToString("\n")

    fun alarm(alarm: PendingAlarm): String = buildList {
        add("${alarm.type} — ${alarm.tag ?: "(no tag)"}")
        add("  Next trigger: ${trigger(alarm)}")
        add("  Repeats: ${repeats(alarm) ?: "no"}")
        alarm.window?.let { add("  Window: ${if (alarm.isExact) "exact" else it}") }
        alarm.target?.let { add("  Delivers to: $it") }
    }.joinToString("\n")

    private fun kind(job: ScheduledJob): String =
        (if (job.isPeriodic) "periodic, ${job.periodic}" else "one-off") + if (job.persisted) ", persisted" else ""

    private fun workSpec(job: ScheduledJob): String =
        job.workSpecId?.let { "work spec $it" } ?: "work spec id not shown by this dump"

    /** `every 6h`, or null for an alarm that fires once. */
    fun repeats(alarm: PendingAlarm): String? =
        alarm.repeatIntervalMillis.takeIf { alarm.isRepeating }?.let { "every ${DumpDurations.describe(it)}" }

    fun state(job: ScheduledJob): String = when {
        job.running -> "running"
        job.ready == true -> "ready to run"
        job.blockers.isNotEmpty() -> "waiting"
        else -> "scheduled"
    }

    fun describe(constraint: String): String =
        "${JobSchedulerDumpParser.describeConstraint(constraint)} ($constraint)"

    /** `in 7h 24m`, or `overdue by 3h 18m` for a job whose earliest time has passed. */
    fun nextRun(job: ScheduledJob, now: Long = System.currentTimeMillis()): String? {
        val offset = job.earliestRunOffsetMillis ?: return null
        return if (offset >= 0) {
            "in ${DumpDurations.describe(offset)} (${clock(now + offset)})"
        } else {
            "earliest time passed ${DumpDurations.describe(offset)} ago"
        }
    }

    fun trigger(alarm: PendingAlarm): String {
        val at = alarm.triggerAtMillis ?: return "never, or not shown by this dump"
        val phrase = when (val relative = alarm.dueInMillis) {
            null -> return clock(at)
            in 0..Long.MAX_VALUE -> "in ${DumpDurations.describe(relative)}"
            else -> "overdue by ${DumpDurations.describe(relative)}"
        }
        return "${clock(at)} ($phrase)"
    }

    fun clock(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
