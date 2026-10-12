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
    override val title = "Scheduled jobs"
    override val description =
        "An app's JobScheduler and WorkManager jobs with unmet constraints, backoff and runs: why " +
            "background work is not running."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return context.withPackage(arguments) { packageName ->
            if (!device.isAppInstall(packageName)) {
                return@withPackage ToolResult.error("Package '$packageName' is not installed on this device.")
            }

            val dump = device.scheduledJobs(packageName)
            dump.problem?.let { return@withPackage ToolResult.error(it) }
            if (dump.jobs.isEmpty()) return@withPackage ToolResult.text("$packageName has no scheduled jobs.")

            ToolResult.text(
                "${dump.jobs.size} scheduled job(s) for $packageName:\n\n" +
                    dump.jobs.joinToString("\n\n") {
                        BackgroundWorkText.job(it, dump.deviceTimeMillis ?: System.currentTimeMillis())
                    },
            )
        }
    }
}

/** `android_get_pending_alarms` — the app's AlarmManager alarms with real clock times. */
class GetPendingAlarmsTool : AdbTool {
    override val name = "android_get_pending_alarms"
    override val title = "Pending alarms"
    override val description =
        "List an app's pending AlarmManager alarms: type, next trigger time, repeat, exactness, target."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return context.withPackage(arguments) { packageName ->
            if (!device.isAppInstall(packageName)) {
                return@withPackage ToolResult.error("Package '$packageName' is not installed on this device.")
            }

            val dump = device.pendingAlarms(packageName)
            dump.problem?.let { return@withPackage ToolResult.error(it) }
            if (dump.alarms.isEmpty()) return@withPackage ToolResult.text("$packageName has no pending alarms.")

            ToolResult.text(
                "${dump.alarms.size} pending alarm(s) for $packageName:\n\n" +
                    dump.alarms.joinToString("\n\n") { BackgroundWorkText.alarm(it) },
            )
        }
    }
}

/** `android_run_job_now` — force a job to run, so its code path can be exercised on demand. */
class RunJobNowTool : AdbTool {
    override val name = "android_run_job_now"
    override val title = "Run job now"
    override val description =
        "Run a scheduled job now, ignoring its constraints. WorkManager may still skip work not due, so " +
            "confirm the Worker ran in android_get_logcat."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer("jobId", "Job id from android_get_scheduled_jobs.", JOB_IDS, required = true)
        string("namespace", "The job's namespace, if one was reported. Found when omitted.")
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val jobId = arguments.requiredInt("jobId", JOB_IDS)
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return context.withPackage(arguments) { packageName ->
            if (!device.isAppInstall(packageName)) {
                return@withPackage ToolResult.error("Package '$packageName' is not installed on this device.")
            }

            val request = RunJobRequest(packageName, jobId, arguments.optionalString("namespace"))
            runCatching { device.runJobNow(request) }.fold(
                onSuccess = { ToolResult.text(it) },
                onFailure = { ToolResult.error("Could not run job $jobId: ${it.message}") },
            )
        }
    }

    private companion object {
        /** Any int: a JobInfo id is whatever the app chose, negative ones included. */
        val JOB_IDS = Int.MIN_VALUE..Int.MAX_VALUE
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
