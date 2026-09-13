package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The "HTTP proxy" control in the Network section: a `host:port` field, Set, and Clear.
 *
 * Its own component rather than three more fields on [SpockAdbViewer], which is already at
 * the size Detekt complains about — and the proxy is self-contained enough that there is
 * nothing to gain from folding it in.
 */
class HttpProxyRow(private val gap: Int) : JPanel(BorderLayout(JBUI.scale(gap), 0)) {

    private val field = JBTextField()
    private val setButton = JButton("Set")
    private val clearButton = JButton("Clear")

    init {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, field.preferredSize.height + JBUI.scale(gap))
        border = JBUI.Borders.emptyTop(2)

        field.toolTipText = "host:port of a proxy on this machine, for example 192.168.1.10:8888"
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
    }

    /**
     * @param device supplies the currently selected device, or null when none is selected.
     */
    fun attach(controller: AdbController, device: () -> IDevice?) {
        field.text = AppSettingService.getInstance().lastHttpProxy()

        setButton.addActionListener {
            device()?.let { target ->
                val value = field.text
                AppSettingService.getInstance().saveHttpProxy(value)
                controller.setHttpProxy(value, target)
            }
        }
        field.addActionListener { setButton.doClick() }
        clearButton.addActionListener {
            device()?.let { target -> controller.clearHttpProxy(target) }
        }
    }

    /**
     * Shows what the selected device actually holds, so a proxy left over from an earlier
     * session is visible rather than something to rediscover when requests start failing.
     *
     * A device that cannot be read leaves the field alone: replacing a real value with a
     * blank would be a worse lie than showing a stale one.
     */
    fun refresh(controller: AdbController, device: IDevice?) {
        if (!isVisible || device == null) return
        controller.currentHttpProxy(device) { proxy ->
            if (proxy != null) field.text = proxy.toString()
        }
    }

    fun setActionVisible(visible: Boolean) {
        isVisible = visible
    }
}
