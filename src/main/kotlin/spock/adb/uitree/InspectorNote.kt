package spock.adb.uitree

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * A sentence or two that wraps to the panel's width, with an optional icon beside it.
 *
 * Not an HTML label: those report a one-line preferred height, so in a docked tool window the
 * second line was clipped rather than wrapped. And plain text is never read as markup, which
 * matters for the audit's findings, which quote labels from the device.
 */
internal class InspectorNote(icon: Icon? = null, grey: Boolean = true) : JPanel(BorderLayout(JBUI.scale(GAP), 0)) {

    private val area = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
        font = UIUtil.getLabelFont()
        if (grey) foreground = JBColor.GRAY
    }

    var text: String
        get() = area.text
        set(value) {
            area.text = value
            area.caretPosition = 0
        }

    init {
        isOpaque = false
        icon?.let { add(JBLabel(it).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST) }
        add(area, BorderLayout.CENTER)
    }

    private companion object {
        const val GAP = 4
    }
}
