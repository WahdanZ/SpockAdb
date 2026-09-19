package spock.adb

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
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
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField

/**
 * The "HTTP proxy" control in the Network section: a `host:port` field, Set, and Remove proxy,
 * with what the selected device actually holds shown beneath.
 *
 * The field remembers every proxy that has been set, most recent first, and offers them in a
 * dropdown. One remembered value covered the developer who always uses the same Charles; it did
 * nothing for the one switching between a local proxy and a device lab, who retyped the other
 * one every time.
 *
 * Its own component rather than more fields on [SpockAdbViewer], which is already at the
 * size Detekt complains about — and the proxy is self-contained enough that there is
 * nothing to gain from folding it in.
 */
class HttpProxyRow(
    private val project: Project,
    private val gap: Int,
) : JPanel(BorderLayout(JBUI.scale(gap), 0)) {

    private val settings = AppSettingService.getInstance()

    /** Editable: a proxy that has never been set has to be typeable, not only picked. */
    private val input = ComboBox<String>().apply {
        isEditable = true
        // Arrow keys move through the list without applying each proxy they pass on the way.
        putClientProperty("JComboBox.isTableCellEditor", true)
    }

    /** True while the list is being replaced by code, when the editor's text is not a new value. */
    private var populating = false

    private val setButton = JButton("Set")

    // "Clear" said nothing about what it cleared — the field, or the device.
    private val clearButton = JButton("Remove proxy")

    /** What is typed or picked now, which is not necessarily anything in the list. */
    private val typed: String? get() = input.editor?.item?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** The text component inside the editable combo, for the placeholder and the Enter key. */
    private val editor: JTextField? get() = input.editor?.editorComponent as? JTextField

    /**
     * The device's real state, kept apart from [input], which is the proxy the user last set.
     * After Remove proxy the field still holds that value so Set can re-apply it; without this
     * the row looked proxied when the device was not.
     */
    private val status = JBLabel(NOT_READ).apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    private var controller: AdbController? = null
    private var selectedDevice: () -> ConnectedDevice? = { null }

    /** Started and answered on the EDT: [refresh] runs from listeners and the read's callback. */
    private val reads = LatestRequest()

    init {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(
            Int.MAX_VALUE,
            input.preferredSize.height + status.preferredSize.height + JBUI.scale(gap),
        )
        border = JBUI.Borders.emptyTop(2)
        // A remembered proxy must not widen the tool window; the editor elides instead.
        input.minimumSize = Dimension(0, input.preferredSize.height)
        input.prototypeDisplayValue = ""

        // localhost on the device is the device itself, which is the mistake this tooltip is for.
        input.toolTipText = "host:port of a proxy on this computer, by its LAN IP, for example " +
            "192.168.1.10:8888. An emulator reaches this computer at 10.0.2.2."
        // The format is the thing people get wrong, so the empty field shows it rather than
        // leaving it in a tooltip nobody hovers before typing.
        (editor as? JBTextField)?.emptyText?.text = "192.168.1.10:8888"
        input.componentPopupMenu = forgetMenu()
        setButton.toolTipText = "Route the device's traffic through the proxy in this field"
        clearButton.toolTipText = "Remove the proxy from the device, so it connects directly"

        add(JBLabel("HTTP proxy"), BorderLayout.WEST)
        add(input, BorderLayout.CENTER)
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
        reloadHistory(show = settings.lastHttpProxy())

        setButton.addActionListener {
            val target = device() ?: return@addActionListener
            // Validated before anything is saved or sent: a typo should neither replace the
            // remembered proxy nor cost a round trip to the device to be reported.
            val proxy = try {
                HttpProxy.fromInput(typed.orEmpty())
            } catch (e: IllegalArgumentException) {
                CommonNotifier.showNotifier(
                    project = project,
                    content = e.message.orEmpty(),
                    type = NotificationType.ERROR,
                )
                return@addActionListener
            }
            settings.saveHttpProxy(proxy.toString())
            reloadHistory(show = proxy.toString())
            controller.setHttpProxy(proxy, target.device) { refresh() }
        }
        // On the editor rather than the combo: a combo fires the same event when an item is
        // picked from the list, which would write to the device on a click in a dropdown.
        editor?.addActionListener {
            if (!populating && !input.isPopupVisible) setButton.doClick()
        }
        clearButton.addActionListener {
            device()?.let { target -> controller.clearHttpProxy(target.device) { refresh() } }
        }
    }

    /**
     * Reads what the selected device actually holds into the status line, so a proxy left
     * over from an earlier session is visible rather than something to rediscover when
     * requests start failing.
     *
     * Does nothing before [attach] or while the row is hidden. Only the latest refresh may
     * write the status line. Reads run concurrently and finish in any order, so one started
     * before a Set or Clear, or before a reconnect, can land after the read that followed it;
     * matching the device serial did not catch that, because it is the same device. A device
     * change triggers a refresh too, so a read for a device no longer selected is dropped by
     * the same rule.
     */
    fun refresh() {
        val controller = controller ?: return
        // Taken before the early returns, so a refresh that reads nothing — hidden, or no
        // device — still retires a read in flight rather than letting it overwrite NOT_READ.
        val request = reads.begin()
        if (!isVisible) return
        val target = selectedDevice() ?: run {
            status.text = NOT_READ
            return
        }
        controller.currentHttpProxy(target.device) { read ->
            if (reads.isLatest(request)) {
                status.text = HttpProxy.describeDevice(read)
            }
        }
    }

    /**
     * Refills the dropdown, keeping [show] in the editor.
     *
     * Replacing a combo box's model clears its editor, so the value being worked on is put back
     * — otherwise recording a proxy would wipe the field that had just been used to set it.
     */
    private fun reloadHistory(show: String? = typed) {
        populating = true
        try {
            input.model = DefaultComboBoxModel(settings.httpProxyHistory().toTypedArray())
            input.selectedItem = show.orEmpty()
        } finally {
            populating = false
        }
    }

    private fun forgetMenu(): JPopupMenu = JPopupMenu().apply {
        add(
            JMenuItem("Forget remembered proxies").apply {
                toolTipText = "Empties this list. The proxy on the device is not changed."
                addActionListener {
                    settings.clearHttpProxyHistory()
                    reloadHistory()
                }
            },
        )
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
