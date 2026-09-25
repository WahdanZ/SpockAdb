package spock.adb.diagnostics

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.LatestRequest
import spock.adb.SpockAdbService
import spock.adb.command.GetApplicationIDCommand
import spock.adb.device.ConnectedDevice
import spock.adb.device.ops.ScreenshotOperations
import spock.adb.openIn
import spock.adb.psiClassByNameFromProjct
import spock.adb.ui.CollapsibleSection
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Image
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * The Diagnose tab: one press reads everything about the screen in front of the developer — the
 * same report `android_diagnose_current_screen` gives an agent — and shows the problems first.
 *
 * Before this, "why is this screen wrong" was a tour: Current Activity on the Device tab, Logcat,
 * the UI Inspector's audit, Background Work, the permissions dialog. Each answered for a
 * different moment. Here the reads run together, off the EDT, and the answer is a short summary
 * with the raw report folded underneath it; the toolbar goes from the summary to the tab or
 * source file that has the detail.
 *
 * It diagnoses the device and app chosen in the tool window's header, like every other tab.
 */
class DiagnosePanel(
    private val project: Project,
    /** Brings the UI Inspector forward and captures. Set by the tool window. */
    private val inspectUi: () -> Unit,
    /** Brings Logcat forward on the app's errors. Set by the tool window. */
    private val viewRelatedLogs: () -> Unit,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val statusLabel = JBLabel(PROMPT).apply { foreground = UIUtil.getContextHelpForeground() }

    private val summary = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(GAP)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
    }

    private val screenshot = JBLabel().apply {
        border = JBUI.Borders.empty(0, GAP, GAP, GAP)
        isVisible = false
    }

    private val rawReport = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
    }

    private var device: ConnectedDevice? = null
    private var diagnosis: ScreenDiagnosis? = null
    private var busy = false
    private var disposed = false

    /**
     * Diagnose was asked for before a device was known — the action run from the Tools menu
     * opens the tool window, whose device list arrives a moment later. Run on arrival rather
     * than answering "no device" for a device that is plugged in.
     */
    private var pending = false
    private val reads = LatestRequest()

    init {
        setToolbar(header())
        setContent(body())
    }

    fun setDevice(connected: ConnectedDevice?) {
        if (connected?.serialNumber == device?.serialNumber) {
            device = connected
            return
        }
        device = connected
        clear()
        if (pending && connected != null) diagnose()
    }

    /** The app chosen in the header. A diagnosis of the previous one no longer answers anything. */
    fun setApp() = clear()

    override fun dispose() {
        disposed = true
    }

    // ---------------------------------------------------------------- layout

    private fun header(): JComponent {
        val actions = DefaultActionGroup().apply {
            add(
                action("Diagnose", "Read everything about the current screen", AllIcons.Actions.Refresh) {
                    !busy && device != null
                }.then { diagnose() },
            )
            addSeparator()
            add(
                action("Copy for AI", "Copy the report, ready to paste into an AI assistant", AllIcons.Actions.Copy) {
                    diagnosis != null
                }.then { copyForAi() },
            )
            add(
                action("Open Activity", "Open the source of the activity that was diagnosed", AllIcons.Nodes.Class) {
                    diagnosis?.activityClass != null
                }.then { openActivity() },
            )
            add(
                action("Open Fragment", "Open the source of a fragment on screen now", AllIcons.Nodes.Class) {
                    device != null && diagnosis?.hasFragments == true
                }.then { openFragment() },
            )
            add(
                action("Inspect UI", "Capture this screen in the UI Inspector", AllIcons.General.InspectionsEye) {
                    device != null
                }.then { inspectUi() },
            )
            add(
                action("View Related Logs", RELATED_LOGS_DESCRIPTION, AllIcons.Debugger.Console) {
                    device != null
                }.then { viewRelatedLogs() },
            )
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
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
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(summary.leftAligned())
            add(screenshot.leftAligned())
            add(
                CollapsibleSection(
                    "Raw report (JSON)",
                    JBScrollPane(rawReport).apply { preferredSize = JBUI.size(RAW_WIDTH, RAW_HEIGHT) },
                    stateKey = "diagnose.raw",
                    expandedByDefault = false,
                ).leftAligned(),
            )
        }
        return JBScrollPane(JPanel(BorderLayout()).apply { add(column, BorderLayout.NORTH) }).apply {
            border = JBUI.Borders.empty()
        }
    }

    private fun <T : JComponent> T.leftAligned(): T = apply { alignmentX = LEFT_ALIGNMENT }

    // ---------------------------------------------------------------- diagnosing

    /** Reads the screen for the selected device and app. Public for the Diagnose action. */
    fun diagnose() {
        val target = device ?: run {
            pending = true
            return status("Waiting for a device. Connect one, or choose one at the top of the tool window.")
        }
        pending = false
        val request = reads.begin()
        busy = true
        status("Diagnosing the current screen on ${target.info.displayName}…")

        // A dozen shell round trips, a UI dump and a screenshot; never on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { collect(target) }
            ApplicationManager.getApplication().invokeLater({
                if (!reads.isLatest(request)) return@invokeLater
                busy = false
                result
                    .onSuccess { (report, png) -> show(ScreenDiagnosis(report), png) }
                    .onFailure { status("Diagnosis failed: ${it.message ?: it::class.java.simpleName}") }
            }) { disposed || project.isDisposed }
        }
    }

    /**
     * The screenshot first: it is the one read that has to match what the developer was looking
     * at when they pressed Diagnose, and every other read takes a moment.
     */
    private fun collect(target: ConnectedDevice): Pair<JsonObject, ByteArray?> {
        val shot = runCatching { ScreenshotOperations(target.device).capture() }
        val probe = DiagnosticProbe(
            device = target.device,
            serialNumber = target.serialNumber,
            packageName = resolveApp(),
        )
        val preamble = JsonObject().apply {
            add(
                "device",
                JsonObject().apply {
                    addProperty("serial", target.serialNumber)
                    addProperty("description", target.info.describe())
                },
            )
        }
        val report = DiagnosticCollector().collect(DiagnosticSections.ALL, probe, preamble)
        report.addProperty(
            "screenshot",
            if (shot.isSuccess) "captured" else shot.exceptionOrNull()?.message ?: "could not be captured",
        )
        return report to shot.getOrNull()
    }

    /** The header's app, else the project's, as every action resolves it. */
    private fun resolveApp(): String? =
        SpockAdbService.getInstance(project).controller.selectedApp?.takeIf { it.isNotBlank() }
            ?: runCatching {
                ReadAction.compute<String?, RuntimeException> { GetApplicationIDCommand.resolve(project) }
            }.getOrNull()

    private fun show(result: ScreenDiagnosis, png: ByteArray?) {
        diagnosis = result
        summary.text = SummaryHtml.render(result)
        summary.caretPosition = 0
        rawReport.text = DiagnosticCollector.render(result.report)
        rawReport.caretPosition = 0
        showScreenshot(png)

        val errors = result.problems.count { it.severity == "error" }
        status(
            when {
                result.problems.isEmpty() -> "No likely problems found."
                errors > 0 -> "$errors error(s) among ${result.problems.size} likely problem(s)."
                else -> "${result.problems.size} likely problem(s), none of them errors."
            },
        )
    }

    private fun showScreenshot(png: ByteArray?) {
        val image = png?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        if (image == null || image.width <= 0) {
            screenshot.icon = null
            screenshot.isVisible = false
            return
        }
        val width = JBUI.scale(SCREENSHOT_WIDTH)
        val height = image.height * width / image.width
        screenshot.icon = ImageIcon(image.getScaledInstance(width, height, Image.SCALE_SMOOTH))
        screenshot.isVisible = true
    }

    private fun clear() {
        reads.begin()
        busy = false
        diagnosis = null
        summary.text = ""
        rawReport.text = ""
        showScreenshot(null)
        status(PROMPT)
    }

    // ---------------------------------------------------------------- quick actions

    private fun copyForAi() {
        val result = diagnosis ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(result.forAi()))
        status("Copied the report. Logs and URLs in it are already redacted.")
    }

    /**
     * Opens the activity that was diagnosed, not whatever is resumed now: the summary is about
     * that moment, and this button sits next to it.
     */
    private fun openActivity() {
        val className = diagnosis?.activityClass ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val psiClass = runCatching {
                ReadAction.compute<PsiClass?, RuntimeException> { className.psiClassByNameFromProjct(project) }
            }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                psiClass?.openIn(project) ?: status("$className is not in this project.")
            }) { project.isDisposed }
        }
    }

    /** The existing Current Fragment flow, which offers a choice when several are on screen. */
    private fun openFragment() {
        val target = device ?: return
        SpockAdbService.getInstance(project).controller.currentFragment(target.device)
    }

    private fun status(text: String) {
        statusLabel.text = text
    }

    /** A toolbar button: [enabled] is asked on every update, [then] supplies what it does. */
    private fun action(text: String, description: String, icon: javax.swing.Icon, enabled: () -> Boolean) =
        ToolbarAction(text, description, icon, enabled)

    private class ToolbarAction(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        private val enabled: () -> Boolean,
    ) : AnAction(text, description, icon) {
        private var run: () -> Unit = {}

        fun then(block: () -> Unit) = apply { run = block }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    /** The summary as HTML: problems first, then one line per section, then what failed. */
    internal object SummaryHtml {
        fun render(result: ScreenDiagnosis): String = buildString {
            append("<html><body>")
            append("<b>Likely problems</b>")
            if (result.problems.isEmpty()) {
                append("<br>None found.")
            } else {
                append("<ul style='margin-left:12px'>")
                result.problems.forEach { problem ->
                    append("<li><b>").append(problem.severity.uppercase()).append("</b> ")
                    append(escape(problem.summary))
                    if (problem.count > 1) append(" ×").append(problem.count)
                    append("</li>")
                }
                append("</ul>")
                if (result.moreProblems > 0) append("…and ${result.moreProblems} more in the raw report.<br>")
            }
            append("<br><table cellpadding='1'>")
            result.facts.forEach { (label, value) ->
                append("<tr><td valign='top'><b>").append(escape(label)).append("</b></td><td>")
                append(escape(value)).append("</td></tr>")
            }
            append("</table>")
            if (result.sectionErrors.isNotEmpty()) {
                append("<br><b>Could not read</b><ul style='margin-left:12px'>")
                result.sectionErrors.forEach { (section, why) ->
                    append("<li>").append(escape(section)).append(": ").append(escape(why)).append("</li>")
                }
                append("</ul>")
            }
            append("</body></html>")
        }

        private fun escape(text: String) = StringUtil.escapeXmlEntities(text)
    }

    private companion object {
        const val GAP = 6
        const val SCREENSHOT_WIDTH = 220
        const val RAW_WIDTH = 400
        const val RAW_HEIGHT = 300
        const val RELATED_LOGS_DESCRIPTION =
            "Show the app's errors, and the system's reports about it, in Logcat"
        const val PROMPT = "Press Diagnose to read the current screen of the selected device and app."
    }
}
