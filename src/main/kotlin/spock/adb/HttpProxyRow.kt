package spock.adb

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.HttpProxy
import spock.adb.device.ConnectedDevice
import spock.adb.notification.CommonNotifier
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The "HTTP proxy" control in the Network section: a `host:port` field, Set, and Clear, with
 * what the selected device actually holds shown beneath.
 *
 * Its own component rather than more fields on [SpockAdbViewer], which is already at the
 * size Detekt complains about — and the proxy is self-contained enough that there is
 * nothing to gain from folding it in.
 */
class HttpProxyRow(
    private val project: Project,
    private val gap: Int,
) : JPanel(BorderLayout(JBUI.scale(gap), 0)) {

    private val field = JBTextField()
    private val setButton = JButton("Set")
    private val clearButton = JButton("Clear")

    /**
     * The device's real state, kept apart from [field], which is the proxy the user last set.
     * After Clear the field still holds that value so Set can re-apply it; without this the
     * row looked proxied when the device was not.
     */
    private val status = JBLabel(NOT_READ).apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    private var controller: AdbController? = null
    private var selectedDevice: () -> ConnectedDevice? = { null }

    init {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(
            Int.MAX_VALUE,
            field.preferredSize.height + status.preferredSize.height + JBUI.scale(gap),
        )
        border = JBUI.Borders.emptyTop(2)

        // localhost on the device is the device itself, which is the mistake this tooltip is for.
        field.toolTipText = "host:port of a proxy on this computer, by its LAN IP, for example " +
            "192.168.1.10:8888. An emulator reaches this computer at 10.0.2.2."
        setButton.toolTipText = "Route the device's traffic through this proxy"
        clearButton.toolTipText = "Restore direct connections"

        add(JBLabel("HTTP proxy"), BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
        add(
            // Clearing the proxy matters as much as setting it: a device left pointing at a
            // proxy that stopped listening fails every request with nothing to explain why.
            JPanel(GridLayout(1, 2, JBUI.scale(gap), 0)).apply {
                add(setButton)
                add(clearButton)
            },
            BorderLayout.EAST,
        )
        add(status, BorderLayout.SOUTH)
    }

    /**
     * @param device supplies the currently selected device, or null when none is selected.
     */
    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        this.controller = controller
        this.selectedDevice = device
        field.text = AppSettingService.getInstance().lastHttpProxy()

        setButton.addActionListener {
            val target = device() ?: return@addActionListener
            // Validated before anything is saved or sent: a typo should neither replace the
            // remembered proxy nor cost a round trip to the device to be reported.
            val proxy = try {
                HttpProxy.fromInput(field.text)
            } catch (e: IllegalArgumentException) {
                CommonNotifier.showNotifier(
                    project = project,
                    content = e.message.orEmpty(),
                    type = NotificationType.ERROR,
                )
                return@addActionListener
            }
            AppSettingService.getInstance().saveHttpProxy(proxy.toString())
            controller.setHttpProxy(proxy, target.device) { refresh() }
        }
        field.addActionListener { setButton.doClick() }
        clearButton.addActionListener {
            device()?.let { target -> controller.clearHttpProxy(target.device) { refresh() } }
        }
    }

    /**
     * Reads what the selected device actually holds into the status line, so a proxy left
     * over from an earlier session is visible rather than something to rediscover when
     * requests start failing.
     *
     * Does nothing before [attach] or while the row is hidden. A read that lands after a
     * different device has been selected is dropped: it describes a device the user is no
     * longer looking at.
     */
    fun refresh() {
        val controller = controller ?: return
        if (!isVisible) return
        val target = selectedDevice() ?: run {
            status.text = NOT_READ
            return
        }
        controller.currentHttpProxy(target.device) { read ->
            if (selectedDevice()?.serialNumber == target.serialNumber) {
                status.text = HttpProxy.describeDevice(read)
            }
        }
    }

    fun setActionVisible(visible: Boolean) {
        val shown = visible && !isVisible
        isVisible = visible
        // refresh() skips a hidden row, so a device selected while it was off was never read.
        if (shown) refresh()
    }

    private companion object {
        /** Before the first read, or with no device selected, the honest state is unknown. */
        val NOT_READ = HttpProxy.describeDevice(Result.failure(IllegalStateException("No device has been read")))
    }
}
