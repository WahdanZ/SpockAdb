package spock.adb.diagnostics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.command.DeviceConditionTracker
import spock.adb.command.GetApplicationPermission
import spock.adb.command.StandbyBucket
import spock.adb.command.deviceConditions
import spock.adb.command.pendingAlarms
import spock.adb.command.scheduledJobs
import spock.adb.device.ops.AppNotInstalledException
import spock.adb.device.ops.InspectionOperations
import spock.adb.device.ops.UiTreeOperations
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.premission.ListItem
import spock.adb.uitree.AccessibilityAudit
import spock.adb.uitree.UiTree

/** The sections this build knows, in the order a developer reads a bug report. */
object DiagnosticSections {

    /**
     * Listed explicitly rather than discovered, so the order is the output order and a new
     * section is one line here. Earlier sections survive a size cut longest; see
     * [DiagnosticCollector].
     */
    val ALL: List<DiagnosticSection> = listOf(
        ScreenSection,
        AppSection,
        LogsSection,
        UiSection,
        BackgroundWorkSection,
        DeviceConditionsSection,
        PermissionsSection,
    )

    /**
     * Section names from the 4.x bundle, so a client written against it still gets the part it
     * asked for — now summarised — rather than an error.
     */
    val ALIASES: Map<String, String> = mapOf(
        "activity" to ScreenSection.id,
        "logcat" to LogsSection.id,
    )

    fun byId(id: String): DiagnosticSection? {
        val wanted = ALIASES[id] ?: id
        return ALL.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
    }
}

/** Which screen is showing, and whether it belongs to the app at all. */
object ScreenSection : DiagnosticSection {
    override val id = "screen"
    override val detail = DetailRef("android_get_activity_stack")

    override fun collect(probe: DiagnosticProbe): SectionReport {
        val resumed = parseResumed(DiagnosticShell.run(probe.device, RESUMED_COMMAND))
        val data = JsonObject()

        if (resumed == null) {
            data.addProperty("activity", null as String?)
            val problem = LikelyProblem(
                "screen",
                Severity.INFO,
                "No activity is resumed: the screen may be off, locked, or showing the launcher.",
                section = id,
            )
            return SectionReport(data, listOf(problem))
        }

        data.addProperty("activity", resumed.className.substringAfterLast('.'))
        data.addProperty("component", DiagnosticShell.clip("${resumed.packageName}/${resumed.className}"))
        val app = probe.packageName ?: return SectionReport(data)

        val inForeground = resumed.packageName == app
        data.addProperty("appInForeground", inForeground)
        if (!inForeground) {
            val problem = LikelyProblem(
                "screen",
                Severity.WARNING,
                "$app is not in the foreground; ${resumed.packageName} is. What is on screen is not the app.",
                section = id,
            )
            return SectionReport(data, listOf(problem))
        }

        addActivityStack(data, probe, app)
        addFragments(data, probe, app)
        return SectionReport(data)
    }

    /**
     * The app's own activities, top first. Best effort, like the fragments: "which screen led
     * here" is context, and a dump that will not parse must not cost the activity above.
     */
    private fun addActivityStack(data: JsonObject, probe: DiagnosticProbe, app: String) {
        val activities = runCatching { InspectionOperations(probe.device).activityStack() }
            .getOrDefault(emptyList())
            .filter { it.appPackage == app }
            .flatMap { it.activitiesList }
        if (activities.isEmpty()) return
        val names = JsonArray()
        activities.take(MAX_ACTIVITIES).forEach { names.add(it.substringAfterLast('.')) }
        data.add("activityStack", names)
        if (activities.size > MAX_ACTIVITIES) data.addProperty("moreActivities", activities.size - MAX_ACTIVITIES)
    }

    /** Best effort: a screen without fragments, or a dump that will not parse, is not a problem. */
    private fun addFragments(data: JsonObject, probe: DiagnosticProbe, app: String) {
        val rows = runCatching { InspectionOperations(probe.device).fragments(app) }
            .getOrDefault(emptyList())
            .flatMap { it.flatten() }
        if (rows.isEmpty()) return
        val names = JsonArray()
        rows.take(MAX_FRAGMENTS).forEach { names.add("  ".repeat(it.depth) + it.fragment.substringAfterLast('.')) }
        data.add("fragments", names)
        if (rows.size > MAX_FRAGMENTS) data.addProperty("moreFragments", rows.size - MAX_FRAGMENTS)
    }

    data class Resumed(val packageName: String, val className: String)

    /** `ActivityRecord{… u0 com.app/.ui.Checkout t12}` → `com.app`, `com.app.ui.Checkout`. */
    fun parseResumed(dump: String): Resumed? {
        val match = COMPONENT.find(dump) ?: return null
        val (pkg, cls) = match.destructured
        return Resumed(pkg, if (cls.startsWith('.')) pkg + cls else cls)
    }

    private val COMPONENT = Regex("""\s([A-Za-z][\w.]*)/([\w.$]+)""")
    private const val MAX_FRAGMENTS = 8
    private const val MAX_ACTIVITIES = 8
    private const val RESUMED_COMMAND =
        "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
}

/** Whether the app is alive at all, which changes what every other section means. */
object AppSection : DiagnosticSection {
    override val id = "app"
    override val detail = DetailRef("android_get_processes")

    override fun collect(probe: DiagnosticProbe): SectionReport {
        val app = probe.packageName
            ?: return SectionReport(
                JsonObject().apply {
                    addProperty("note", "No app is known: pass packageName, or open the app's project in the IDE.")
                },
            )
        val pids = probe.pids
        val data = JsonObject().apply {
            addProperty("packageName", app)
            addProperty("running", pids.isNotEmpty())
            add("pids", JsonArray().apply { pids.forEach(::add) })
        }
        val problems = if (pids.isEmpty()) {
            listOf(
                LikelyProblem(
                    "process",
                    Severity.WARNING,
                    "$app is not running. If it was, it crashed or was killed — check the crash below, if any.",
                    section = id,
                ),
            )
        } else {
            emptyList()
        }
        return SectionReport(data, problems)
    }
}

/**
 * Warnings and errors, turned into problems. Reads the whole log rather than filtering by pid on
 * the device: a process that has crashed has no pid left to filter by, and ANRs are printed by
 * the system on the app's behalf. [LogProblemExtractor] does the attribution instead.
 */
object LogsSection : DiagnosticSection {
    override val id = "logs"
    override val detail = DetailRef("android_get_logcat", JsonObject().apply { addProperty("minLevel", "W") })

    override fun collect(probe: DiagnosticProbe): SectionReport {
        val log = DiagnosticShell.run(probe.device, "logcat -d -v threadtime -t ${probe.logWindowLines} *:W")
        val result = LogProblemExtractor.extract(log, probe.packageName, probe.pids)
        val data = JsonObject().apply {
            addProperty("windowLines", probe.logWindowLines)
            addProperty("appLines", result.appLines)
            addProperty("errors", result.errorLines)
            addProperty("warnings", result.warningLines)
            addProperty("distinctProblems", result.problems.size)
        }
        return SectionReport(data, result.problems)
    }
}

/** How the screen is built and what it says, without the tree. */
object UiSection : DiagnosticSection {
    override val id = "ui"
    override val detail = DetailRef("android_get_ui_tree")

    override fun collect(probe: DiagnosticProbe): SectionReport = summarise(UiTreeOperations(probe.device).read())

    fun summarise(tree: UiTree): SectionReport {
        val nodes = tree.nodes().filter { it.bounds.isVisible }.toList()
        val findings = AccessibilityAudit.audit(tree)
        val data = JsonObject().apply {
            addProperty("framework", tree.framework.description)
            addProperty("composeTestTags", tree.testTagSupport.name.lowercase())
            addProperty("visibleNodes", nodes.size)
            addProperty("interactive", nodes.count { it.isInteractive })
            nodes.firstOrNull { it.focused }
                ?.let { addProperty("focused", DiagnosticShell.clip(it.label.ifBlank { it.className })) }
            add(
                "text",
                JsonArray().apply {
                    // Passwords are never repeated, even masked: the length alone is a leak.
                    nodes.asSequence()
                        .filterNot { it.password }
                        .map { it.label.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(MAX_TEXTS)
                        .forEach { add(DiagnosticShell.clip(it, MAX_TEXT_CHARS)) }
                },
            )
            add(
                "accessibility",
                JsonObject().apply {
                    addProperty("errors", findings.count { it.severity == AccessibilityAudit.Severity.ERROR })
                    addProperty("warnings", findings.count { it.severity == AccessibilityAudit.Severity.WARNING })
                },
            )
        }

        // An accessibility fault is real but rarely why a screen is broken, so it ranks one
        // step below the crashes and failed requests it would otherwise sit beside.
        val problems = findings
            .groupBy { it.severity to it.issue.substringBefore(" (") }
            .map { (key, group) ->
                val (severity, issue) = key
                LikelyProblem(
                    type = "accessibility",
                    severity = if (severity == AccessibilityAudit.Severity.ERROR) Severity.WARNING else Severity.INFO,
                    summary = issue,
                    count = group.size,
                    section = id,
                )
            }
        return SectionReport(data, problems)
    }

    private const val MAX_TEXTS = 12
    private const val MAX_TEXT_CHARS = 60
}

/** Jobs and alarms, counted; the failing and the stuck named. */
object BackgroundWorkSection : DiagnosticSection {
    override val id = "backgroundWork"
    override val detail = DetailRef("android_get_scheduled_jobs")

    override fun collect(probe: DiagnosticProbe): SectionReport {
        val app = probe.packageName ?: error("No app is known, so there is no background work to read.")
        val jobs = probe.device.scheduledJobs(app)
        val alarms = runCatching { probe.device.pendingAlarms(app) }.getOrNull()

        val data = JsonObject().apply {
            jobs.problem?.let { addProperty("jobsProblem", DiagnosticShell.clip(it)) }
            addProperty("jobs", jobs.jobs.size)
            addProperty("workManagerJobs", jobs.jobs.count { it.isWorkManager })
            addProperty("running", jobs.jobs.count { it.running })
            addProperty("waiting", jobs.jobs.count { !it.running && it.blockers.isNotEmpty() })
            addProperty("failing", jobs.jobs.count { it.failures > 0 })
            addProperty("alarms", alarms?.alarms?.size)
        }

        val failing = jobs.jobs.filter { it.failures > 0 }.sortedByDescending { it.failures }.map { job ->
            LikelyProblem(
                "backgroundWork",
                Severity.WARNING,
                "Job ${job.jobId} (${job.service.substringAfterLast('.')}) has failed ${job.failures} time(s)" +
                    (job.backoff?.let { " and is backing off" } ?: ""),
                section = id,
            )
        }
        val waiting = jobs.jobs.filter { !it.running && it.blockers.isNotEmpty() }.map { job ->
            LikelyProblem(
                "backgroundWork",
                Severity.INFO,
                "Job ${job.jobId} is waiting on: ${job.blockers.joinToString(", ") { it.lowercase() }}",
                section = id,
            )
        }
        return SectionReport(data, (failing + waiting).take(MAX_JOB_PROBLEMS))
    }

    private const val MAX_JOB_PROBLEMS = 4
}

/** Doze, the standby bucket and the battery: the conditions that silently defer work. */
object DeviceConditionsSection : DiagnosticSection {
    override val id = "deviceConditions"
    override val detail = DetailRef("android_get_device_conditions")

    override fun collect(probe: DiagnosticProbe): SectionReport {
        val conditions = probe.device.deviceConditions(probe.packageName)
        DeviceConditionTracker.reconcile(probe.serialNumber, conditions)
        val changed = DeviceConditionTracker.conditions(probe.serialNumber)

        val data = JsonObject().apply {
            addProperty("deepIdle", conditions.deepIdle)
            addProperty("dozing", conditions.dozing)
            addProperty("standbyBucket", conditions.bucket?.argument)
            addProperty("batteryLevel", conditions.batteryLevel)
            addProperty("charging", conditions.powered)
            addProperty("batteryOverridden", conditions.batteryOverridden)
            add("changedBySpock", JsonArray().apply { changed.forEach { add(it.describe()) } })
        }

        val problems = buildList {
            if (conditions.dozing) {
                add(
                    LikelyProblem(
                        "deviceCondition",
                        Severity.WARNING,
                        "The device is in deep Doze: jobs, alarms and network access are being deferred.",
                        section = id,
                    ),
                )
            }
            conditions.bucket?.takeIf { it.code >= StandbyBucket.RARE.code }?.let {
                add(
                    LikelyProblem(
                        "deviceCondition",
                        Severity.WARNING,
                        "${probe.packageName} is in the ${it.label} standby bucket: its jobs and alarms " +
                            "are heavily rationed.",
                        section = id,
                    ),
                )
            }
            if (changed.isNotEmpty()) {
                add(
                    LikelyProblem(
                        "deviceCondition",
                        Severity.INFO,
                        "Spock changed ${changed.joinToString { it.describe() }} and has not reset it. " +
                            "Call android_reset_device_conditions when done.",
                        section = id,
                    ),
                )
            } else if (conditions.batteryOverridden) {
                add(
                    LikelyProblem(
                        "deviceCondition",
                        Severity.INFO,
                        "The battery is overridden: the device is not reporting its real level or charger.",
                        section = id,
                    ),
                )
            }
        }
        return SectionReport(data, problems)
    }
}

/**
 * Runtime permissions, granted and denied.
 *
 * A denied permission is not a fault — the user may have said no, and the app should cope — so
 * it is reported as information: the line an agent needs before it concludes the camera preview
 * is black because of a bug in the preview.
 */
object PermissionsSection : DiagnosticSection {
    override val id = "permissions"
    override val detail = DetailRef("android_get_package_info")

    override fun collect(probe: DiagnosticProbe): SectionReport {
        val app = probe.packageName ?: error("No app is known, so there are no permissions to read.")
        ShellQuote.requireValidComponent(app, "Package name")
        val dump = DiagnosticShell.run(probe.device, "dumpsys package ${ShellQuote.quote(app)}")
        // Without this, an app that is not installed has no permission block to parse and read
        // as "no runtime permissions" — an answer, where nothing was read at all. Checked in the
        // dump already fetched, so it costs no extra round trip.
        if ("Package [$app]" !in dump) throw AppNotInstalledException(app)
        return summarise(GetApplicationPermission.parse(dump))
    }

    fun summarise(permissions: List<ListItem>): SectionReport {
        val denied = permissions.filterNot { it.isSelected }.map { it.name.substringAfterLast('.') }
        val data = JsonObject().apply {
            addProperty("runtime", permissions.size)
            addProperty("granted", permissions.size - denied.size)
            add("denied", JsonArray().apply { denied.take(MAX_NAMES).forEach(::add) })
            if (denied.size > MAX_NAMES) addProperty("moreDenied", denied.size - MAX_NAMES)
        }
        val problems = if (denied.isEmpty()) {
            emptyList()
        } else {
            listOf(
                LikelyProblem(
                    "permission",
                    Severity.INFO,
                    "${denied.size} runtime permission(s) denied: " +
                        DiagnosticShell.clip(denied.joinToString(", ")),
                    section = id,
                ),
            )
        }
        return SectionReport(data, problems)
    }

    private const val MAX_NAMES = 12
}
