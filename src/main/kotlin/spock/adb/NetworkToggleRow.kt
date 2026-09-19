package spock.adb

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.Network
import spock.adb.command.NetworkState
import spock.adb.device.ConnectedDevice
import spock.adb.notification.CommonNotifier
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel

/**
 * One row of the Network section: what the device's Wi-Fi or mobile data is set to, and the
 * button that changes it.
 *
 * The section used to hold two buttons labelled "Wi-Fi" and "Mobile Data", which said neither
 * what the device was doing nor what pressing them would do — and since they toggle, pressing
 * one to find out is how you turn off the connection you were using. The state is read from the
 * device and the button is labelled with the change it makes.
 *
 * **The button's availability depends on one thing: whether a device is selected.** It first
 * took its enabled state from the read that fills the label in, which meant every path where
 * that read did not land — no controller yet, the row not yet attached, a read retired by a
 * newer one that then returned early — left a button that looked ordinary and did nothing when
 * pressed. What the device is set to and whether the button works are separate questions, so
 * they are answered separately.
 */
class NetworkToggleRow(
    private val network: Network,
    title: String,
    /** Reports a click that could not run, so a press is never silently swallowed. */
    private val project: Project? = null,
) : JPanel(BorderLayout(JBUI.scale(GAP), 0)) {

    private val state = JBLabel(UNKNOWN_TEXT)
    private val button = JButton(TOGGLE_TEXT)

    private var controller: AdbController? = null
    private var selectedDevice: () -> ConnectedDevice? = { null }

    /** True while a toggle is on its way to the device, which is the only time the button waits. */
    private var toggling = false

    /** Started and answered on the EDT: a read begun for one device must not label another. */
    private val reads = LatestRequest()

    init {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, button.preferredSize.height + JBUI.scale(GAP))
        border = JBUI.Borders.emptyTop(2)
        // Both rows line up: without a fixed width the state sits wherever the title ends.
        state.preferredSize = Dimension(JBUI.scale(STATE_WIDTH), state.preferredSize.height)
        button.preferredSize = Dimension(JBUI.scale(BUTTON_WIDTH), button.preferredSize.height)

        add(JBLabel(title), BorderLayout.WEST)
        add(state, BorderLayout.CENTER)
        add(button, BorderLayout.EAST)
        showState(null)
    }

    /**
     * @param device supplies the currently selected device, or null when none is selected.
     */
    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        this.controller = controller
        this.selectedDevice = device
        button.addActionListener { toggle() }
        // The row can be attached after the device list has already been published, in which
        // case nothing else will ask it to read the device.
        refresh()
    }

    private fun toggle() {
        val controller = controller
        val target = selectedDevice()
        if (controller == null || target == null) {
            // Saying nothing is what a dead button does; this at least names the reason.
            report("No device is selected, so ${network.label} cannot be switched.")
            return
        }
        toggling = true
        updateButton()
        // Read back rather than flip the label: `svc` exits 0 whether or not it took.
        controller.toggleNetwork(target.device, network) {
            toggling = false
            refresh()
        }
    }

    /**
     * Reads the device's setting into the row. Reads nothing before [attach] or while hidden.
     *
     * Only the label waits for the answer. The button does not: a read that never lands must
     * not be able to disable it.
     */
    fun refresh() {
        val controller = controller
        // Taken before the early returns, so a refresh that reads nothing still retires a read
        // in flight rather than letting its answer land on a row about another device.
        val request = reads.begin()
        val target = selectedDevice()
        updateButton()
        if (controller == null || target == null || !isVisible) return showState(null)

        state.text = READING_TEXT
        controller.networkState(target.device, network) { read ->
            if (!reads.isLatest(request)) return@networkState
            showState(read.getOrNull())
        }
    }

    private fun showState(read: NetworkState?) {
        state.text = when (read) {
            NetworkState.ENABLED -> ON_TEXT
            NetworkState.DISABLED -> OFF_TEXT
            null -> UNKNOWN_TEXT
        }
        state.foreground = when (read) {
            NetworkState.ENABLED -> ON_COLOUR
            else -> UIUtil.getContextHelpForeground()
        }
        button.text = when (read) {
            NetworkState.ENABLED -> "Turn off"
            NetworkState.DISABLED -> "Turn on"
            null -> TOGGLE_TEXT
        }
        button.toolTipText = when (read) {
            NetworkState.ENABLED -> "Switch this connection off on the device"
            NetworkState.DISABLED -> "Switch this connection on on the device"
            // The button still works when the read failed: it toggles whatever the device holds.
            null -> "Switch this connection on or off; the device could not be asked what it is set to"
        }
        updateButton()
    }

    /** Pressable whenever there is a device to press it against, and not while one is in flight. */
    private fun updateButton() {
        button.isEnabled = selectedDevice() != null && !toggling
    }

    private fun report(message: String) {
        val project = project ?: return
        CommonNotifier.showNotifier(project = project, content = message, type = NotificationType.WARNING)
    }

    private companion object {
        const val GAP = 4
        const val STATE_WIDTH = 60
        const val BUTTON_WIDTH = 90

        const val ON_TEXT = "On"
        const val OFF_TEXT = "Off"
        const val UNKNOWN_TEXT = "—"
        const val READING_TEXT = "…"
        const val TOGGLE_TEXT = "Toggle"

        val ON_COLOUR = JBColor(0x1A7F37, 0x57A64A)
    }
}
