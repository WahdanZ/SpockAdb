package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.apiLevel
import spock.adb.parser.AlarmDump
import spock.adb.parser.AlarmDumpParser
import spock.adb.parser.JobSchedulerDump
import spock.adb.parser.JobSchedulerDumpParser
import spock.adb.parser.ScheduledJob
import java.util.concurrent.TimeUnit

/**
 * The shell side of the Background Work tab and its MCP tools, kept free of [IDevice] where it
 * can be so it is testable without a device.
 */
internal object BackgroundWorkShell {

    /** `cmd jobscheduler` arrived in Android 7.0. Before it there is no way to force a job. */
    const val MIN_RUN_JOB_API = 24

    /**
     * What a forced WorkManager job does and does not prove.
     *
     * JobScheduler starts WorkManager's `SystemJobService` regardless, but WorkManager then declines
     * to run a Worker that is not due — periodic work inside its period, and work waiting out a
     * retry backoff — and puts it back under a new job id. Seen on an API 34 emulator with
     * WorkManager 2.10: `cmd jobscheduler` printed `Running job [FORCED]` and the Worker never ran.
     * The dump cannot tell periodic WorkManager work from one-off (WorkManager schedules both as
     * one-off jobs, and the extras that say which are parcelled), so this is said, not guessed.
     */
    const val WORK_MANAGER_CAVEAT =
        "WorkManager runs the Worker only if its work is due: periodic work inside its period, and " +
            "work waiting out a retry backoff, is put back without running (Logcat shows " +
            "\"WM-WorkerWrapper: Delaying execution\"). One-off work runs."

    /** `-n <namespace>` arrived with job namespaces, in Android 14. */
    const val MIN_NAMESPACE_API = 34

    fun jobsCommand(packageName: String): String =
        "dumpsys jobscheduler ${ShellQuote.quote(ShellQuote.requireValidComponent(packageName, "Package name"))}"

    const val ALARMS_COMMAND = "dumpsys alarm"

    /** The device clock, in epoch seconds. Toybox `date` has `%s` on every release that has JobScheduler. */
    const val CLOCK_COMMAND = "date +%s"

    /** Epoch seconds from [CLOCK_COMMAND] as milliseconds, or null when the output is not a number. */
    fun parseClock(output: String): Long? = output.trim().toLongOrNull()?.let { it * MILLIS_PER_SECOND }

    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * The namespace to run job [jobId] in, found from the app's own jobs when the caller gave none.
     *
     * WorkManager 2.10+ schedules its jobs in the `androidx.work.systemjobscheduler` namespace on
     * API 34+, and `cmd jobscheduler run` cannot find a namespaced job without `-n`. A caller that
     * has only the id — an agent that read the list, a developer who typed it — would otherwise be
     * told the job does not exist.
     *
     * @throws IllegalStateException when the id is in more than one namespace, naming them, since
     *   picking one would run a job the caller did not mean.
     */
    fun resolveNamespace(jobId: Int, jobs: List<ScheduledJob>): String? {
        val namespaces = jobs.filter { it.jobId == jobId }.map { it.namespace }.distinct()
        check(namespaces.size <= 1) {
            "Job $jobId exists in more than one namespace (" +
                namespaces.joinToString { it ?: "default" } + "). Pass the namespace to say which."
        }
        return namespaces.singleOrNull()
    }

    fun runJobCommand(request: RunJobRequest): String = buildString {
        append("cmd jobscheduler run -f ")
        request.namespace?.let { append("-n ").append(ShellQuote.quote(it)).append(' ') }
        append(ShellQuote.quote(ShellQuote.requireValidComponent(request.packageName, "Package name")))
        append(' ').append(request.jobId)
    }

    /**
     * Why Run now cannot be offered on a device at [apiLevel], or null when it can.
     *
     * Asked before the command is sent, so the button can be disabled with the reason rather than
     * failing on click.
     */
    fun runJobUnavailableReason(apiLevel: Int?, namespace: String? = null): String? = when {
        apiLevel == null -> null
        apiLevel < MIN_RUN_JOB_API ->
            "Forcing a job needs `cmd jobscheduler`, which arrived in Android 7.0 (API 24). " +
                "This device is API $apiLevel."
        namespace != null && apiLevel < MIN_NAMESPACE_API ->
            "This job is in the \"$namespace\" namespace, which `cmd jobscheduler` can only " +
                "address from Android 14 (API 34)."
        else -> null
    }

    /**
     * `null` when the device started the job, otherwise what went wrong.
     *
     * `cmd jobscheduler run` prints `Running job [FORCED]` when it starts one (AOSP
     * `JobSchedulerShellCommand.runJob`). Its refusals — `Package not found: …`, `Could not find
     * job …` — are sentences of their own and are passed on as said.
     */
    fun runJobFailure(request: RunJobRequest, output: String): String? {
        val said = output.trim()
        return when {
            said.startsWith("Running job") -> null
            said.isEmpty() ->
                "The device said nothing when asked to run job ${request.jobId}, so it is not " +
                    "known to have started."
            else -> said
        }
    }
}

data class RunJobRequest(
    val packageName: String,
    val jobId: Int,
    /** The job's namespace, for a job scheduled in one (API 34+). */
    val namespace: String? = null,
)

private const val TIMEOUT_SECONDS = 20L

private fun IDevice.shell(command: String): String {
    val receiver = ShellOutputReceiver()
    executeShellCommand(command, receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return receiver.toString()
}

/** Every job the package has with JobScheduler, WorkManager's included. */
class GetScheduledJobsCommand : Command<String, JobSchedulerDump> {
    override fun execute(p: String, project: Project, device: IDevice): JobSchedulerDump =
        device.scheduledJobs(p)
}

/** The package's pending alarms. */
class GetPendingAlarmsCommand : Command<String, AlarmDump> {
    override fun execute(p: String, project: Project, device: IDevice): AlarmDump =
        device.pendingAlarms(p)
}

/**
 * Runs a job now, ignoring its constraints.
 *
 * @throws IllegalStateException when the device cannot force jobs or refused this one, carrying
 *   the reason.
 */
class RunJobNowCommand : Command<RunJobRequest, String> {
    override fun execute(p: RunJobRequest, project: Project, device: IDevice): String = device.runJobNow(p)
}

internal fun IDevice.scheduledJobs(packageName: String): JobSchedulerDump {
    val dump = shell(BackgroundWorkShell.jobsCommand(packageName))
    val clock = BackgroundWorkShell.parseClock(shell(BackgroundWorkShell.CLOCK_COMMAND))
    return JobSchedulerDumpParser.parse(dump, packageName).copy(deviceTimeMillis = clock)
}

internal fun IDevice.pendingAlarms(packageName: String): AlarmDump {
    ShellQuote.requireValidComponent(packageName, "Package name")
    return AlarmDumpParser.parse(shell(BackgroundWorkShell.ALARMS_COMMAND), packageName)
}

/**
 * One implementation for the tab and the MCP tool, so the two cannot disagree on success.
 *
 * The app's jobs are read first: to find the namespace of a job given only by id, and to know
 * whether WorkManager runs it, which changes what "started" means (see [BackgroundWorkShell.WORK_MANAGER_CAVEAT]).
 */
internal fun IDevice.runJobNow(request: RunJobRequest): String {
    val apiLevel = apiLevel()
    BackgroundWorkShell.runJobUnavailableReason(apiLevel, request.namespace)?.let { error(it) }
    val jobs = scheduledJobs(request.packageName).jobs
    val namespaced = (apiLevel ?: 0) >= BackgroundWorkShell.MIN_NAMESPACE_API
    val resolved = if (request.namespace == null && namespaced) {
        request.copy(namespace = BackgroundWorkShell.resolveNamespace(request.jobId, jobs))
    } else {
        request
    }
    val output = shell(BackgroundWorkShell.runJobCommand(resolved))
    BackgroundWorkShell.runJobFailure(resolved, output)?.let { error(it) }

    val job = jobs.firstOrNull { it.jobId == resolved.jobId && it.namespace == resolved.namespace }
    val where = resolved.namespace?.let { " (namespace $it)" }.orEmpty()
    val caveat = if (job?.isWorkManager == true) " ${BackgroundWorkShell.WORK_MANAGER_CAVEAT}" else ""
    return "Started job ${resolved.jobId}$where of ${resolved.packageName}, ignoring its constraints.$caveat"
}
