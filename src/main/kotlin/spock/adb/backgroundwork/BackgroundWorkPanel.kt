package spock.adb.backgroundwork

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import spock.adb.LatestRequest
import spock.adb.command.BackgroundWorkShell
import spock.adb.command.GetPendingAlarmsCommand
import spock.adb.command.GetScheduledJobsCommand
import spock.adb.command.RunJobNowCommand
import spock.adb.command.RunJobRequest
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.BackgroundWorkText
import spock.adb.parser.AlarmDump
import spock.adb.parser.DumpDurations
import spock.adb.parser.JobSchedulerDump
import spock.adb.parser.JobSchedulerDumpParser
import spock.adb.parser.PendingAlarm
import spock.adb.parser.ScheduledJob
import java.awt.BorderLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * The selected app's background work: its JobScheduler jobs — WorkManager's among them — and its
 * pending alarms, read from `dumpsys` and laid out so the question "why has this not run" has an
 * answer on screen rather than in several hundred lines of dump.
 *
 * A tab of its own rather than more fields on the Device tab, which is at the size Detekt flags.
 */
class BackgroundWorkPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val statusLabel = JBLabel(NO_DEVICE)

    private val jobsModel = readOnlyModel(JOB_COLUMNS)
    private val jobsTable = JBTable(jobsModel)
    private val alarmsModel = readOnlyModel(ALARM_COLUMNS)
    private val alarmsTable = JBTable(alarmsModel)

    private val detailArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        // Wrapped: the tool window is usually docked narrow, and the Run Now result and the
        // constraint lists are sentences that otherwise run off its right edge.
        lineWrap = true
        wrapStyleWord = true
    }

    private val conditions = DeviceConditionsRow(project, { disposed }) { status(it) }

    private var device: ConnectedDevice? = null
    private var packageName: String? = null
    private var jobs: List<ScheduledJob> = emptyList()

    /** The device clock when [jobs] was read, which their run times are offsets from. */
    private var jobsClock: Long? = null

    /**
     * What Run Now reported, shown once the read that follows it lands.
     *
     * Run Now reads the jobs back so the table shows the job running and its schedule moved, and
     * that read used to replace the result on the status line within a second. For a WorkManager
     * job the result is the part that matters — WorkManager may still have skipped the Worker —
     * so it is carried across the read and put in the details pane.
     */
    private var runNotice: String? = null
    private var alarms: List<PendingAlarm> = emptyList()
    private var busy = false
    private var disposed = false
    private val reads = LatestRequest()

    init {
        listOf(jobsTable, alarmsTable).forEach { table ->
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.setShowGrid(false)
            table.emptyText.text = "Nothing read yet"
        }
        jobsTable.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            if (jobsTable.selectedRow >= 0) alarmsTable.clearSelection()
            showDetails()
        }
        alarmsTable.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            if (alarmsTable.selectedRow >= 0) jobsTable.clearSelection()
            showDetails()
        }

        setToolbar(header())
        setContent(body())
    }

    fun setDevice(connected: ConnectedDevice?) {
        if (connected?.serialNumber == device?.serialNumber) {
            device = connected
            return
        }
        device = connected
        conditions.setDevice(connected)
        clear()
        if (isShowing) refresh()
    }

    /** The app chosen in the tool window's header. */
    fun setApp(app: String?) {
        val wanted = app?.trim()?.ifEmpty { null }
        if (wanted == packageName) return
        packageName = wanted
        conditions.setApp(wanted)
        clear()
        if (isShowing) refresh()
    }

    /** Called when the tab is brought forward: what it shows is only as current as its last read. */
    fun onShown() = refresh()

    override fun dispose() {
        disposed = true
        conditions.dispose()
    }

    // ---------------------------------------------------------------- layout

    private fun header(): JComponent {
        val actions = DefaultActionGroup().apply {
            add(
                object : AnAction(
                    "Refresh",
                    "Read the app's jobs and alarms from the device",
                    AllIcons.Actions.Refresh,
                ) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = !busy && device != null && packageName != null
                    }
                    override fun actionPerformed(e: AnActionEvent) = refresh()
                },
            )
            add(
                object : AnAction("Run Now", RUN_NOW_DESCRIPTION, AllIcons.Actions.Execute) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        val job = selectedJob()
                        val reason = job?.let { runUnavailableReason(it) }
                        e.presentation.isEnabled = !busy && job != null && reason == null
                        e.presentation.description = when {
                            job == null -> "Select a job to run it now"
                            reason != null -> reason
                            else -> RUN_NOW_DESCRIPTION
                        }
                    }
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedJob()?.let(::runNow)
                    }
                },
            )
            add(
                object : AnAction("Copy Details", "Copy what the details pane shows", AllIcons.Actions.Copy) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = detailArea.text.isNotEmpty()
                    }
                    override fun actionPerformed(e: AnActionEvent) {
                        CopyPasteManager.getInstance().setContents(StringSelection(detailArea.text))
                    }
                },
            )
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(conditions, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, GAP, 2, GAP)
                    add(statusLabel, BorderLayout.CENTER)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun body(): JComponent {
        val lists = OnePixelSplitter(true, LISTS_PROPORTION).apply {
            firstComponent = titled("Scheduled jobs", jobsTable)
            secondComponent = titled("Pending alarms", alarmsTable)
        }
        return OnePixelSplitter(true, DETAILS_PROPORTION).apply {
            firstComponent = lists
            secondComponent = JBScrollPane(detailArea)
        }
    }

    private fun titled(title: String, table: JBTable): JComponent = JPanel(BorderLayout()).apply {
        add(
            JBLabel(title).apply {
                border = JBUI.Borders.empty(2, GAP)
                font = font.deriveFont(Font.BOLD)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    // ---------------------------------------------------------------- reading

    private fun refresh() {
        val target = device ?: return status(NO_DEVICE)
        conditions.refresh()
        val app = packageName ?: return status(CHOOSE_APP)
        val request = reads.begin()
        busy = true
        status("Reading jobs and alarms for $app…")

        // Two dumpsys round trips, one of them the whole alarm table; never on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val jobsResult = runCatching { GetScheduledJobsCommand().execute(app, project, target.device) }
            val alarmsResult = runCatching { GetPendingAlarmsCommand().execute(app, project, target.device) }
            ApplicationManager.getApplication().invokeLater({
                if (!reads.isLatest(request)) return@invokeLater
                busy = false
                show(app, jobsResult, alarmsResult)
            }) { disposed || project.isDisposed }
        }
    }

    private fun show(app: String, jobsResult: Result<JobSchedulerDump>, alarmsResult: Result<AlarmDump>) {
        val jobsDump = jobsResult.getOrNull()
        val alarmsDump = alarmsResult.getOrNull()
        jobs = jobsDump?.jobs.orEmpty()
        jobsClock = jobsDump?.deviceTimeMillis
        alarms = alarmsDump?.alarms.orEmpty()

        jobsModel.rowCount = 0
        val now = jobsDump?.deviceTimeMillis ?: System.currentTimeMillis()
        jobs.forEach { jobsModel.addRow(jobRow(it, now)) }
        alarmsModel.rowCount = 0
        alarms.forEach { alarmsModel.addRow(alarmRow(it)) }
        jobsTable.emptyText.text = emptyText(jobsResult, jobsDump?.problem, "jobs", "$app has no scheduled jobs")
        alarmsTable.emptyText.text =
            emptyText(alarmsResult, alarmsDump?.problem, "alarms", "$app has no pending alarms")

        val problems = listOfNotNull(
            jobsResult.exceptionOrNull()?.let { "Jobs: ${it.message}" },
            jobsDump?.problem?.let { "Jobs: $it" },
            alarmsResult.exceptionOrNull()?.let { "Alarms: ${it.message}" },
            alarmsDump?.problem?.let { "Alarms: $it" },
        )
        val notice = runNotice.also { runNotice = null }
        if (problems.isEmpty()) {
            val counts = "${jobs.size} job(s) and ${alarms.size} alarm(s) for $app"
            if (notice == null) {
                detailArea.text = ""
                status(counts)
            } else {
                status("${notice.substringBefore(". ")}. $counts")
                detailArea.text = "Run Now\n\n$notice"
                detailArea.caretPosition = 0
            }
        } else {
            // A dump that did not parse is still worth reading: show it rather than nothing.
            status(problems.joinToString("  "))
            detailArea.text = listOfNotNull(jobsDump?.raw, alarmsDump?.raw).joinToString("\n\n")
                .ifEmpty { problems.joinToString("\n") }
            detailArea.caretPosition = 0
        }
    }

    private fun emptyText(result: Result<*>, problem: String?, what: String, none: String): String = when {
        result.isFailure -> "Could not read $what"
        problem != null -> "Could not parse the $what dump"
        else -> none
    }

    private fun clear() {
        reads.begin()
        busy = false
        jobs = emptyList()
        alarms = emptyList()
        jobsModel.rowCount = 0
        alarmsModel.rowCount = 0
        detailArea.text = ""
        status(
            when {
                device == null -> NO_DEVICE
                packageName == null -> CHOOSE_APP
                else -> "Press Refresh to read jobs and alarms for $packageName"
            },
        )
    }

    // ---------------------------------------------------------------- run now

    private fun runUnavailableReason(job: ScheduledJob): String? =
        BackgroundWorkShell.runJobUnavailableReason(device?.info?.apiLevel, job.namespace)

    private fun runNow(job: ScheduledJob) {
        val target = device ?: return
        val app = packageName ?: return
        busy = true
        status("Running job ${job.jobId}…")
        val request = RunJobRequest(app, job.jobId, job.namespace)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { RunJobNowCommand().execute(request, project, target.device) }
            ApplicationManager.getApplication().invokeLater({
                busy = false
                result
                    .onSuccess {
                        status(it)
                        runNotice = it
                        // Reading back shows whether it is now running, and where its schedule moved.
                        refresh()
                    }
                    .onFailure { status("Could not run job ${job.jobId}: ${it.message}") }
            }) { disposed || project.isDisposed }
        }
    }

    // ---------------------------------------------------------------- details

    private fun selectedJob(): ScheduledJob? = jobs.getOrNull(jobsTable.selectedRow.toModelRow(jobsTable))

    private fun selectedAlarm(): PendingAlarm? = alarms.getOrNull(alarmsTable.selectedRow.toModelRow(alarmsTable))

    private fun Int.toModelRow(table: JBTable): Int = if (this < 0) -1 else table.convertRowIndexToModel(this)

    private fun showDetails() {
        val job = selectedJob()
        val alarm = selectedAlarm()
        detailArea.text = when {
            job != null -> BackgroundWorkText.job(job, jobsClock ?: System.currentTimeMillis()) +
                AS_PRINTED + job.raw.trimIndent()
            alarm != null -> BackgroundWorkText.alarm(alarm) + AS_PRINTED + alarm.raw.trimIndent()
            else -> detailArea.text
        }
        detailArea.caretPosition = 0
    }

    private fun status(text: String) {
        statusLabel.text = text
    }

    internal companion object {
        const val NO_DEVICE = "No device selected."
        private const val AS_PRINTED = "\n\n--- as dumpsys printed it ---\n"
        const val CHOOSE_APP = "Choose an app in the header to see its background work."
        const val RUN_NOW_DESCRIPTION =
            "Run the selected job now, ignoring its constraints (cmd jobscheduler run -f). " +
                "WorkManager still skips periodic or backed-off work that is not due."

        val JOB_COLUMNS = arrayOf("Job", "Service", "Kind", "State", "Waiting on", "Next run", "Failures")
        val ALARM_COLUMNS = arrayOf("Type", "Tag", "Next trigger", "Repeats", "Exact")

        private const val GAP = 6
        private const val LISTS_PROPORTION = 0.6f
        private const val DETAILS_PROPORTION = 0.65f

        /**
         * One row of the jobs table.
         *
         * The id alone: WorkManager's namespace is 32 characters and pushed the id out of a docked
         * tool window's column. The namespace is in the details pane, and Run Now finds it anyway.
         */
        fun jobRow(job: ScheduledJob, now: Long = System.currentTimeMillis()): Array<Any> = arrayOf(
            job.jobId.toString(),
            if (job.isWorkManager) {
                "WorkManager" + (job.workSpecId?.let { " ($it)" } ?: "")
            } else {
                job.service.substringAfter('/')
            },
            if (job.isPeriodic) "periodic" else "one-off",
            BackgroundWorkText.state(job),
            job.blockers.joinToString(", ") { JobSchedulerDumpParser.describeConstraint(it) },
            compactNextRun(job, now),
            if (job.failures > 0) job.failures.toString() else "",
        )

        /** One row of the alarms table. */
        fun alarmRow(alarm: PendingAlarm): Array<Any> = arrayOf(
            alarm.type,
            shortTag(alarm),
            compactTrigger(alarm),
            BackgroundWorkText.repeats(alarm).orEmpty(),
            if (alarm.isExact) "exact" else "",
        )

        /**
         * `in 9m 45s (21:35)`: the relative time first, because a table cell is cut from the
         * right, and the clock time without the date when it is today.
         */
        fun compactNextRun(job: ScheduledJob, now: Long): String {
            val offset = job.earliestRunOffsetMillis ?: return ""
            return if (offset >= 0) {
                "in ${DumpDurations.describe(offset)} (${shortClock(now + offset, now)})"
            } else {
                "overdue ${DumpDurations.describe(offset)}"
            }
        }

        /** The alarm's trigger in the same shape as [compactNextRun]. */
        fun compactTrigger(alarm: PendingAlarm): String {
            val at = alarm.triggerAtMillis ?: return "never"
            val dueIn = alarm.dueInMillis ?: return shortClock(at, at)
            val span = DumpDurations.describe(dueIn)
            val relative = if (dueIn >= 0) "in $span" else "overdue $span"
            return "$relative (${shortClock(at, at - dueIn)})"
        }

        /** `21:35` today, `Sep 22 03:27` on another day. */
        fun shortClock(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val time = Instant.ofEpochMilli(at).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val pattern = if (time.toLocalDate() == today) "HH:mm" else "MMM d HH:mm"
            return time.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
        }

        /**
         * `ALARM_REPEATING` for `*walarm*:spock.adb.sample.ALARM_REPEATING`: without the wakeup
         * marker, which the Type column already shows, and without the app's own package, which
         * every row shares. The full tag is in the details pane.
         */
        fun shortTag(alarm: PendingAlarm): String {
            val tag = alarm.tag ?: return ""
            val action = if (tag.startsWith("*")) tag.substringAfter(':') else tag
            return action.removePrefix("${alarm.packageName}.")
        }

        private fun readOnlyModel(columns: Array<String>) = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
    }
}
