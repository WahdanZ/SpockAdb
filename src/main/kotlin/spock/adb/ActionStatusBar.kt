package spock.adb

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The last thing an action did, kept on screen at the foot of the tool window.
 *
 * Results were balloons: they said what happened and then went away, which is the wrong shape
 * for the answer to "did that work?" — the question asked after every button. A developer who
 * looked away, or who pressed two things, had nothing left to read.
 */
internal class ActionStatusBar : JPanel(BorderLayout()) {

    private val message = JBLabel(" ").apply {
        // Device-supplied text reaches this line; a label whose text starts with a tag is markup.
        putClientProperty(HTML_DISABLE, true)
        border = JBUI.Borders.empty(2, GAP)
    }

    private val time = JBLabel(" ").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    private val clear = JButton(AllIcons.Actions.GC).apply {
        toolTipText = "Clear this line"
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = JBUI.emptyInsets()
        isVisible = false
    }

    init {
        border = JBUI.Borders.empty(2, GAP)
        add(message, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(GAP), 0)).apply {
                isOpaque = false
                add(time)
                add(clear)
            },
            BorderLayout.EAST,
        )
        clear.addActionListener { clear() }
    }

    /** Shows what an action did, with how long it took when that was measured. */
    fun show(result: ActionResult) {
        val elapsed = result.elapsedMs?.let { "  ·  $it ms" }.orEmpty()
        message.text = "${if (result.ok) OK_MARK else FAIL_MARK}  ${result.message}$elapsed"
        message.foreground = if (result.ok) OK else FAIL
        time.text = TIME_FORMAT.format(Date())
        clear.isVisible = true
    }

    /** Says what the tool window is doing, in the quiet colour used for something in progress. */
    fun working(text: String) {
        message.text = text
        message.foreground = UIUtil.getContextHelpForeground()
        time.text = " "
        clear.isVisible = false
    }

    private fun clear() {
        message.text = " "
        time.text = " "
        clear.isVisible = false
    }

    private companion object {
        const val GAP = 6
        const val HTML_DISABLE = "html.disable"
        const val OK_MARK = "✓"
        const val FAIL_MARK = "✗"

        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val OK = JBColor(0x1F6F4A, 0x57BA8C)
        val FAIL = JBColor(0xB3261E, 0xF2857C)
    }
}
