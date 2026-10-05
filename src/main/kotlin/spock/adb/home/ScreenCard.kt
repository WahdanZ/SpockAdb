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
import spock.adb.flutter.navigation.FlutterNavigationState
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

/**
 * What is on screen now: the activity and fragment, as links to their source.
 *
 * These were buttons — Current activity, Current fragment — that each answered by opening a
 * file, so "which screen am I on?" cost a click and an editor tab every time. The answer is
 * cheap to read, so it is shown, and the click is kept for what it is actually for: going to
 * the code.
 *
 * For a Flutter app with a live session, the route on top of its Navigator too, and its back
 * stack: an Android activity alone says only "MainActivity" for every Flutter screen.
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

    private val flutterRouteLabel = rowLabel("Flutter route")
    private val flutterRouteValue = JBLabel(UNKNOWN)
    private val flutterStackLink = ActionLink("Flutter back stack") { checkFlutterStack() }

    /** Diagnoses, then copies the report: one click for what the AI needs about this screen. */
    val copyForAiButton = JButton("Copy screen for AI", AllIcons.Actions.Copy).apply {
        toolTipText = "Diagnose the current screen, then copy the report — logs and URLs redacted — " +
            "ready to paste into an AI assistant"
    }
    val diagnoseButton = JButton("Diagnose", AllIcons.Actions.Find).apply {
        toolTipText = "Read the screen, app, logs, UI, permissions and background work in one place"
    }

    private var controller: AdbController? = null
    private var device: () -> ConnectedDevice? = { null }

    /** Started and answered on the EDT, so a slow read cannot label a later screen. */
    private val reads = LatestRequest()

    private var flutter: FlutterRouteSource? = null
    private var app: () -> String? = { null }
    private val flutterReads = LatestRequest()

    /** What the settings dialog shows, and whether a Flutter session gave the rows something to say. */
    private var screenRowsOn = true
    private var stacksOn = true
    private var flutterLive = false

    /** A burst of navigation costs one read; a read that met a transition tries again, a few times. */
    private val flutterSoon = Timer(SETTLE_MS) { refreshFlutter() }.apply { isRepeats = false }
    private var settleRetries = 0

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        activityLink.putClientProperty(HTML_DISABLE, true)
        fragmentLink.putClientProperty(HTML_DISABLE, true)
        flutterRouteValue.putClientProperty(HTML_DISABLE, true)
        add(
            JPanel(GridBagLayout()).apply {
                alignmentX = LEFT_ALIGNMENT
                add(activityLabel, labelAt(0))
                add(activityLink, valueAt(0))
                add(fragmentLabel, labelAt(1))
                add(fragmentLink, valueAt(1))
                add(flutterRouteLabel, labelAt(2))
                add(flutterRouteValue, valueAt(2))
            },
        )
        add(flow(appStackLink, allStackLink, flutterStackLink))
        // Diagnose first: it is where a debugging session starts, and the copy is Diagnose too.
        add(flow(diagnoseButton, copyForAiButton))
        show(null)
        showFlutter(null)
    }

    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        this.controller = controller
        this.device = device
    }

    /** Reads the Flutter route from [source], for the app [app] names. */
    fun attachFlutter(source: FlutterRouteSource, app: () -> String?) {
        flutter = source
        this.app = app
        source.onChange = {
            flutterReads.begin()
            settleRetries = 0
            flutterSoon.restart()
        }
    }

    /** Reads what is on screen. Reads nothing while hidden, or before [attach]. */
    fun refresh() {
        val request = reads.begin()
        val controller = controller
        val target = device()
        refreshFlutter()
        if (controller == null || target == null || !isVisible) return show(null)
        activityLink.text = READING
        controller.screen(target.device) { result ->
            if (reads.isLatest(request)) show(result.getOrNull())
        }
    }

    /** Reads the Flutter route, from a session already connected to the app; hides it without one. */
    private fun refreshFlutter() {
        val request = flutterReads.begin()
        val source = flutter
        val session = source?.liveSession(device(), app())
        if (source == null || session == null || !isVisible) return showFlutter(null)
        if (!flutterLive) flutterRouteValue.text = READING
        val isolate = session.snapshot.uiIsolateId
        source.read(session, check = false) { state ->
            if (!flutterReads.isLatest(request) || source.liveSession(device(), app()) !== session ||
                session.snapshot.uiIsolateId != isolate
            ) {
                return@read
            }
            showFlutter(state)
            if (state.transient && settleRetries < MAX_SETTLE_RETRIES) {
                settleRetries++
                flutterSoon.restart()
            }
        }
    }

    /** Counts the app's navigators, then shows the stack — and Home shows the same read. */
    private fun checkFlutterStack() {
        val source = flutter ?: return
        val session = source.liveSession(device(), app()) ?: return showFlutter(null)
        val request = flutterReads.begin()
        val isolate = session.snapshot.uiIsolateId
        flutterStackLink.isEnabled = false
        source.read(session, check = true) { state ->
            flutterStackLink.isEnabled = true
            if (!flutterReads.isLatest(request) || source.liveSession(device(), app()) !== session ||
                session.snapshot.uiIsolateId != isolate
            ) {
                return@read
            }
            showFlutter(state)
            if (flutterStackLink.isShowing) FlutterBackStackPopup.show(state, flutterStackLink)
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
        screenRowsOn = activity || fragment
        stacksOn = stacks
        applyFlutterVisibility()
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

    /** Null: no Flutter session for the selected app, so no Flutter row and no link. */
    private fun showFlutter(state: FlutterNavigationState?) {
        flutterLive = state != null
        flutterRouteValue.text = state?.let(FlutterRouteText::home) ?: UNKNOWN
        flutterRouteValue.toolTipText = state?.let(FlutterRouteText::homeTooltip)
        applyFlutterVisibility()
    }

    private fun applyFlutterVisibility() {
        flutterRouteLabel.isVisible = flutterLive && screenRowsOn
        flutterRouteValue.isVisible = flutterLive && screenRowsOn
        flutterStackLink.isVisible = flutterLive && stacksOn
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

        /** About a page transition: a read that met one sees it settled next time. */
        const val SETTLE_MS = 300
        const val MAX_SETTLE_RETRIES = 3

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
