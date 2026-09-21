package spock.adb.parser

/**
 * One job an app has scheduled with JobScheduler, as `dumpsys jobscheduler` describes it.
 *
 * WorkManager schedules its work through JobScheduler on API 23 and above, so a `Worker` shows up
 * here as a job run by `androidx.work.impl.background.systemjob.SystemJobService`.
 */
data class ScheduledJob(
    val jobId: Int,
    /** The job namespace (API 34+), or null for the default namespace. */
    val namespace: String?,
    /** The uid the job runs as, as the dump prints it: `u0a119`, `1000`. */
    val uid: String,
    /** `package/class` of the `JobService` that runs it. */
    val service: String,
    /** The package the job is attributed to, which differs from the service's for sync jobs. */
    val sourcePackage: String?,
    /** `interval=+15m0s0ms flex=+5m0s0ms`, or null for a one-off job. */
    val periodic: String?,
    val persisted: Boolean,
    val required: List<String>,
    val satisfied: List<String>,
    /**
     * The constraints holding the job back.
     *
     * Read from the dump's own `Unsatisfied constraints:` line where it has one. Before that line
     * existed it is what is required and not satisfied.
     */
    val unsatisfied: List<String>,
    /** The dump's own verdict on whether the job could run now, or null when it gave none. */
    val ready: Boolean?,
    /** True when the job is executing right now. */
    val running: Boolean,
    /** `exponential, initial +30s0ms`, or null when the dump shows no backoff. */
    val backoff: String?,
    /** Times the job has failed and been rescheduled with backoff. 0 when the dump says nothing. */
    val failures: Int,
    /** `earliest=+7h24m45s998ms, latest=none` as the dump prints it, relative to the dump. */
    val runTime: String?,
    /** Relative offset of the earliest run from the moment of the dump, or null for none. */
    val earliestRunOffsetMillis: Long?,
    /** Device clock time, `2026-09-21 03:56:42`. */
    val lastSuccessfulRun: String?,
    val lastFailedRun: String?,
    val standbyBucket: String?,
    /**
     * WorkManager's id for the work, where the dump shows the job's extras.
     *
     * Usually it does not: the extras are printed as `mParcelledData.dataSize=248` until
     * something has unparcelled them, and then there is nothing to read.
     */
    val workSpecId: String?,
    /** The job's block of the dump as printed, for when the parsed fields are not enough. */
    val raw: String,
) {
    /** WorkManager's `SystemJobService` runs this job, so it is a `Worker`. */
    val isWorkManager: Boolean get() = service.endsWith(WORK_MANAGER_SERVICE)

    val isPeriodic: Boolean get() = periodic != null

    /**
     * The unsatisfied constraints that actually stop the job running.
     *
     * An unsatisfied `DEADLINE` is not one of them: a deadline is an override — once it passes the
     * job runs whatever else is unmet — so it being unmet means only that it has not come yet.
     */
    val blockers: List<String> get() = unsatisfied.filterNot { it == "DEADLINE" }

    companion object {
        const val WORK_MANAGER_SERVICE = "androidx.work.impl.background.systemjob.SystemJobService"
    }
}

/** What was read from a `dumpsys jobscheduler` run. */
data class JobSchedulerDump(
    val jobs: List<ScheduledJob>,
    /**
     * Set when the dump did not have the shape the parser expects, with the reason.
     *
     * A dump that parses to no jobs is ambiguous — the app may have none, or the format may have
     * moved — so the two are kept apart rather than both showing as an empty list.
     */
    val problem: String? = null,
    /** The dump as the device printed it, kept when there is a [problem] so it can be shown. */
    val raw: String? = null,
    /**
     * The device's clock when the dump was taken, in epoch milliseconds, or null when unknown.
     *
     * The dump gives run times only as offsets from its own moment (`earliest=+7h24m`), so this
     * is what places them on the device's clock rather than the host's.
     */
    val deviceTimeMillis: Long? = null,
)

/**
 * Reads `dumpsys jobscheduler [package]`.
 *
 * The dump is formatted for people, and it has changed shape across releases: `Unsatisfied
 * constraints:` arrived in Android 8, job namespaces in 14, `Num failures:` gained a system-stop
 * count along the way. Every field is found by its label rather than its position, missing ones
 * are left null, and the raw block is kept so nothing is lost when a field is not recognised.
 */
object JobSchedulerDumpParser {

    /**
     * `JOB #u0a119/100: 97a8e5c com.google.android.dialer/.PeriodicJobService`, or with a
     * namespace, `JOB usagestats_prune:1000/0: 227c483 @usagestats_prune@android/...`.
     */
    private val headerRegex =
        Regex("""^\s*JOB (?:#|(?<namespace>\S+?):)(?<uid>\w+)/(?<id>-?\d+): \S+ (?:@[^@\s]*@)?(?<service>\S+)\s*$""")

    private const val REGISTERED = "Registered "
    private const val ACTIVE_JOBS = "Active jobs:"

    private val workSpecIdRegex = Regex("""EXTRA_WORK_SPEC_ID=([0-9a-fA-F-]{36})""")
    private val failuresRegex = Regex("""Num failures:\s*(\d+)""")
    private val backoffRegex = Regex("""Backoff:\s*policy=(\d+)\s+initial=(\S+)""")
    private val earliestRegex = Regex("""earliest=(\S+?)(?:,|$)""")

    /** A running job's `#uid/id`, however the slot line around it is worded. */
    private val activeJobRegex = Regex("""#(\w+)/(-?\d+)\b""")

    fun parse(dump: String, packageName: String? = null): JobSchedulerDump {
        val lines = dump.lines().map { it.trimEnd('\r') }
        val registeredAt = lines.indexOfFirst { it.startsWith(REGISTERED) && it.trimEnd().endsWith("jobs:") }
        if (registeredAt < 0) {
            return JobSchedulerDump(
                jobs = emptyList(),
                problem = if (dump.isBlank()) {
                    "The device returned nothing for dumpsys jobscheduler."
                } else {
                    "The dump has no \"Registered N jobs\" section, so its format is not one " +
                        "this version recognises."
                },
                raw = dump,
            )
        }

        // The registered list runs until the next line at the left margin: the next section.
        val section = lines.drop(registeredAt + 1).takeWhile { it.isEmpty() || it.first().isWhitespace() }
        val running = activeJobs(lines)

        val parsed = blocks(section).mapNotNull { block -> parseJob(block, running) }
        if (parsed.isEmpty() && section.any { it.trimStart().startsWith("JOB ") }) {
            // Jobs are listed, but not one header matched: the format moved, not the app.
            return JobSchedulerDump(
                jobs = emptyList(),
                problem = "The dump lists jobs in a format this version does not recognise.",
                raw = section.joinToString("\n"),
            )
        }
        return JobSchedulerDump(parsed.filter { job -> packageName == null || job.belongsTo(packageName) })
    }

    private fun ScheduledJob.belongsTo(packageName: String): Boolean =
        sourcePackage == packageName || service.substringBefore('/') == packageName

    /** Splits the registered list into one run of lines per `JOB` header. */
    private fun blocks(section: List<String>): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        section.forEach { line ->
            if (headerRegex.matches(line)) {
                blocks += mutableListOf(line)
            } else {
                blocks.lastOrNull()?.add(line)
            }
        }
        return blocks
    }

    /** `uid/jobId` pairs listed under `Active jobs:`, which is where running jobs are shown. */
    private fun activeJobs(lines: List<String>): Set<Pair<String, Int>> {
        val start = lines.indexOfFirst { it.trim() == ACTIVE_JOBS }
        if (start < 0) return emptySet()
        return lines.drop(start + 1)
            .takeWhile { it.isEmpty() || it.first().isWhitespace() }
            .mapNotNull { line ->
                val match = activeJobRegex.find(line) ?: return@mapNotNull null
                val id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                match.groupValues[1] to id
            }
            .toSet()
    }

    private fun parseJob(block: List<String>, running: Set<Pair<String, Int>>): ScheduledJob? {
        val header = headerRegex.matchEntire(block.first()) ?: return null
        val namespace = header.groups["namespace"]?.value
        val uid = header.groups["uid"]?.value ?: return null
        val jobId = header.groups["id"]?.value?.toIntOrNull() ?: return null
        val body = block.drop(1).map { it.trim() }

        fun field(label: String): String? =
            body.firstOrNull { it.startsWith(label) }?.removePrefix(label)?.trim()

        fun constraints(label: String): List<String>? = field(label)?.let(::constraintNames)

        val required = constraints("Required constraints:").orEmpty()
        val satisfied = constraints("Satisfied constraints:").orEmpty()
        val unsatisfied = constraints("Unsatisfied constraints:") ?: (required - satisfied.toSet())

        val runTime = field("Run time:")

        return ScheduledJob(
            jobId = jobId,
            namespace = namespace,
            uid = uid,
            service = field("Service:") ?: header.groups["service"]?.value ?: return null,
            sourcePackage = field("Source:")?.let { source ->
                Regex("""pkg=(\S+)""").find(source)?.groupValues?.get(1)
            },
            periodic = field("PERIODIC:"),
            persisted = body.any { it == "PERSISTED" },
            required = required,
            satisfied = satisfied,
            unsatisfied = unsatisfied,
            ready = field("Ready:")?.let(::readyVerdict),
            running = (uid to jobId) in running,
            backoff = field("Backoff:")?.let(::describeBackoff),
            failures = body.firstNotNullOfOrNull { failuresRegex.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            runTime = runTime,
            earliestRunOffsetMillis = runTime
                ?.let { earliestRegex.find(it)?.groupValues?.get(1) }
                ?.let(DumpDurations::parse),
            lastSuccessfulRun = field("Last successful run:"),
            lastFailedRun = field("Last failed run:"),
            standbyBucket = field("Standby bucket:"),
            workSpecId = body.firstNotNullOfOrNull { workSpecIdRegex.find(it) }?.groupValues?.get(1),
            raw = block.joinToString("\n"),
        )
    }

    /** `true (job=true user=true …)` → true. */
    private fun readyVerdict(value: String): Boolean? = when {
        value.startsWith("true") -> true
        value.startsWith("false") -> false
        else -> null
    }

    /** `TIMING_DELAY CONNECTIVITY [0xd0000000]` → the names, without the bitmask. */
    private fun constraintNames(value: String): List<String> =
        value.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.startsWith("[") }

    private fun describeBackoff(value: String): String {
        val match = backoffRegex.find("Backoff: $value") ?: return value
        val policy = when (match.groupValues[1]) {
            "0" -> "linear"
            "1" -> "exponential"
            else -> "policy ${match.groupValues[1]}"
        }
        return "$policy, initial ${match.groupValues[2]}"
    }

    /** What each constraint means to someone who did not write JobScheduler. */
    fun describeConstraint(name: String): String =
        CONSTRAINT_DESCRIPTIONS[name] ?: name.lowercase().replace('_', ' ')

    private val CONSTRAINT_DESCRIPTIONS = mapOf(
        "CHARGING" to "charging",
        "BATTERY_NOT_LOW" to "battery not low",
        "STORAGE_NOT_LOW" to "storage not low",
        "IDLE" to "device idle",
        "CONNECTIVITY" to "network",
        "TIMING_DELAY" to "initial delay not yet passed",
        "DEADLINE" to "deadline",
        "CONTENT_TRIGGER" to "content change",
        "DEVICE_NOT_DOZING" to "device not in Doze",
        "BACKGROUND_NOT_RESTRICTED" to "background not restricted",
        "WITHIN_QUOTA" to "app has run-time quota left",
        "TARE_WEALTH" to "app has TARE credit",
        "PREFETCH" to "prefetch window",
        "FLEXIBILITY" to "flexible constraints",
    )
}

/**
 * Durations as `dumpsys` prints them: `+1d0h0m0s0ms`, `-16h35m13s992ms`, `+30s0ms`.
 *
 * Android's `TimeUtils.formatDuration` writes every unit from the largest non-zero one down, with
 * a sign in front, and `--` or `none` where there is no value.
 */
object DumpDurations {

    private val durationRegex = Regex("""^([+-])?(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m(?!s))?(?:(\d+)s)?(?:(\d+)ms)?$""")

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** The size of each unit, in the order the pattern captures them. */
    private val UNIT_MILLIS = listOf(DAY, HOUR, MINUTE, SECOND, 1L)

    /** The duration in milliseconds, or null for `none`, `--` or anything else unrecognised. */
    fun parse(value: String): Long? {
        val match = durationRegex.matchEntire(value.trim()) ?: return null
        val parts = match.groupValues.drop(2)
        if (parts.all { it.isEmpty() }) return null
        val total = parts.map { it.toLongOrNull() ?: 0L }
            .zip(UNIT_MILLIS) { value, unit -> value * unit }
            .sum()
        return if (match.groupValues[1] == "-") -total else total
    }

    /** `2d 3h`, `7h 24m`, `5m 3s`, `12s`: the two largest units, for reading at a glance. */
    fun describe(millis: Long): String {
        val total = kotlin.math.abs(millis)
        val units = listOf(
            total / DAY to "d",
            total % DAY / HOUR to "h",
            total % HOUR / MINUTE to "m",
            total % MINUTE / SECOND to "s",
        )
        val first = units.indexOfFirst { it.first > 0 }
        if (first < 0) return "0s"
        return units.drop(first).take(2)
            .filterIndexed { index, (value, _) -> index == 0 || value > 0 }
            .joinToString(" ") { (value, unit) -> "$value$unit" }
    }
}
