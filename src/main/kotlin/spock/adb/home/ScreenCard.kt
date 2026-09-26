package spock.adb.home

import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.AdbController
import spock.adb.LatestRequest
import spock.adb.ScreenInfo
import spock.adb.device.ConnectedDevice
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * What is on screen now: the activity and fragment, as links to their source.
 *
 * These were buttons — Current activity, Current fragment — that each answered by opening a
 * file, so "which screen am I on?" cost a click and an editor tab every time. The answer is
 * cheap to read, so it is shown, and the click is kept for what it is actually for: going to
 * the code.
 */
internal class ScreenCard : JPanel() {

    private val activityLink = ActionLink(UNKNOWN) { open { controller, device -> controller.currentActivity(device) } }
    private val fragmentLink = ActionLink(UNKNOWN) { open { controller, device -> controller.currentFragment(device) } }
    private val activityLabel = rowLabel("Activity")
    private val fragmentLabel = rowLabel("Fragment")

    private val appStackLink = ActionLink("App back stack") {
        open { controller, device -> controller.currentApplicationBackStack(device) }
    }
    private val allStackLink = ActionLink("All activities") {
        open { controller, device -> controller.currentBackStack(device) }
    }

    /** Diagnoses, then copies the report: one click for what the AI needs about this screen. */
    val copyForAiButton = JButton("Copy screen for AI", AllIcons.Actions.Copy).apply {
        toolTipText = "Diagnose the current screen, then copy the report — logs and URLs redacted — " +
            "ready to paste into an AI assistant"
    }
    val diagnoseButton = JButton("Diagnose").apply {
        toolTipText = "Read the screen, app, logs, UI, permissions and background work in one place"
    }

    private var controller: AdbController? = null
    private var device: () -> ConnectedDevice? = { null }

    /** Started and answered on the EDT, so a slow read cannot label a later screen. */
    private val reads = LatestRequest()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        activityLink.putClientProperty(HTML_DISABLE, true)
        fragmentLink.putClientProperty(HTML_DISABLE, true)
        add(
            JPanel(GridBagLayout()).apply {
                alignmentX = LEFT_ALIGNMENT
                add(activityLabel, labelAt(0))
                add(activityLink, valueAt(0))
                add(fragmentLabel, labelAt(1))
                add(fragmentLink, valueAt(1))
            },
        )
        add(flow(appStackLink, allStackLink))
        add(flow(copyForAiButton, diagnoseButton))
        show(null)
    }

    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        this.controller = controller
        this.device = device
    }

    /** Reads what is on screen. Reads nothing while hidden, or before [attach]. */
    fun refresh() {
        val request = reads.begin()
        val controller = controller
        val target = device()
        if (controller == null || target == null || !isVisible) return show(null)
        activityLink.text = READING
        controller.screen(target.device) { result ->
            if (reads.isLatest(request)) show(result.getOrNull())
        }
    }

    /** Shows or hides a row, for the actions switched off in the settings dialog. */
    fun setShown(activity: Boolean, fragment: Boolean, stacks: Boolean) {
        activityLabel.isVisible = activity
        activityLink.isVisible = activity
        fragmentLabel.isVisible = fragment
        fragmentLink.isVisible = fragment
        appStackLink.isVisible = stacks
        allStackLink.isVisible = stacks
    }

    private fun show(screen: ScreenInfo?) {
        val activity = screen?.activity
        activityLink.text = activity?.let(::simpleName) ?: UNKNOWN
        activityLink.toolTipText = activity?.let { "Open $it" }
        activityLink.isEnabled = activity != null
        fragmentLink.text = ScreenText.fragments(screen?.fragments.orEmpty())
        fragmentLink.toolTipText = screen?.fragments?.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Open ")
        fragmentLink.isEnabled = !screen?.fragments.isNullOrEmpty()
    }

    private fun open(run: (AdbController, com.android.ddmlib.IDevice) -> Unit) {
        val controller = controller ?: return
        device()?.let { run(controller, it.device) }
    }

    private fun flow(vararg components: java.awt.Component) =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP * 2), JBUI.scale(GAP))).apply {
            alignmentX = LEFT_ALIGNMENT
            components.forEach { add(it) }
        }

    private fun rowLabel(text: String) = JBLabel(text).apply { setFontColor(UIUtil.FontColor.BRIGHTER) }

    private fun labelAt(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0, 2, GAP * 2)
    }

    private fun valueAt(row: Int) = GridBagConstraints().apply {
        gridx = 1
        gridy = row
        weightx = 1.0
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0)
    }

    private companion object {
        const val GAP = 4
        const val UNKNOWN = "—"
        const val READING = "…"
        const val HTML_DISABLE = "html.disable"

        fun simpleName(className: String) = className.substringAfterLast('.')
    }
}

/** What the screen card says, apart from Swing so it can be tested. */
internal object ScreenText {

    /** `CartFragment`, or `CartFragment +2` when the app shows several at once. */
    fun fragments(names: List<String>): String = when (names.size) {
        0 -> "—"
        1 -> names.single().substringAfterLast('.')
        else -> "${names.first().substringAfterLast('.')} +${names.size - 1}"
    }
}
