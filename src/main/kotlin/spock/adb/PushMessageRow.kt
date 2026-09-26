package spock.adb

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.PushDelivery
import spock.adb.device.ConnectedDevice
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The "Push message" control under Send to device: opens the payload editor, and keeps the
 * last send's verdict on screen beneath it.
 *
 * Its own component rather than more fields on the old Device tab, which is at the size Detekt
 * flags — the same reason [HttpProxyRow] is one.
 */
class PushMessageRow(
    private val project: Project,
    gap: Int,
) : JPanel(BorderLayout(JBUI.scale(gap), 0)) {

    private val composeButton = JButton("Compose…").apply {
        toolTipText = "Send a push message to the selected app over ADB, with no server and no token"
    }

    /** The last verdict, per device. "Sent" alone is never shown: see [PushDelivery]. */
    private val status = JBLabel(" ").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    /** One editor at a time: a second one would be a second, diverging copy of the payload. */
    private var open: PushMessageDialog? = null

    init {
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(2)
        maximumSize = Dimension(
            Int.MAX_VALUE,
            composeButton.preferredSize.height + status.preferredSize.height + JBUI.scale(gap),
        )
        add(JBLabel("Push message"), BorderLayout.WEST)
        add(composeButton, BorderLayout.EAST)
        add(status, BorderLayout.SOUTH)
    }

    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        composeButton.addActionListener {
            open?.takeIf { it.isShowing }?.let { dialog ->
                dialog.toFront()
                return@addActionListener
            }
            val dialog = PushMessageDialog(project, controller, device) { deliveries ->
                status.text = summary(deliveries)
                status.toolTipText = deliveries.joinToString("\n") { it.message }
            }
            // Dropped on close, so a closed editor is not what the next click brings forward.
            Disposer.register(dialog.disposable) { open = null }
            open = dialog
            dialog.show()
        }
    }

    /** The header's device or app changed: an open editor re-reads which devices can receive. */
    fun targetChanged() {
        open?.takeIf { it.isShowing }?.refreshReadiness()
    }

    private fun summary(deliveries: List<PushDelivery>): String {
        val accepted = deliveries.count { it.accepted }
        return when {
            deliveries.size == 1 -> deliveries.single().message
            accepted == deliveries.size -> "All ${deliveries.size} devices accepted the push message."
            else -> "$accepted of ${deliveries.size} devices accepted the push message. Hover for each device."
        }
    }
}
