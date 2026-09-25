package spock.adb.uitree

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import spock.adb.ui.WrapLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The details pane's "Source" line: where the selected element most likely comes from, and a link
 * to go there. [onJump] is handed the link, for a list of candidates to open beneath it.
 */
internal class SourceLine(private val onJump: (JComponent) -> Unit) : JPanel() {

    private val status = JBLabel()
    private val link = ActionLink("Jump to Source", ActionListener { onJump(it.source as JComponent) })

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        listOf(
            TitledSeparator("Source"),
            JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(LINK_GAP), JBUI.scale(2))).apply {
                isOpaque = false
                add(status)
                add(link)
            },
        ).forEach {
            it.alignmentX = Component.LEFT_ALIGNMENT
            add(it)
        }
        clear()
    }

    fun clear() = say("")

    fun searching() = say(SourceStatus.SEARCHING, busy = true)

    /** No search to show: nothing to look for, or indexing in progress. */
    fun say(message: String, busy: Boolean = false) {
        status.icon = if (busy) AnimatedIcon.Default.INSTANCE else null
        status.text = message
        status.toolTipText = null
        link.isVisible = false
    }

    /** [opened]: the answer is being opened as well as shown. */
    fun show(result: SourceResult, opened: Boolean = false) {
        val found = SourceStatus.found(result, opened) ?: return say(SourceStatus.notFound(result))
        say(found)
        status.toolTipText = SourceStatus.HOW_FOUND
        link.text = if (result.hits.size > 1) "Choose from ${result.hits.size}…" else "Jump to Source"
        link.isVisible = true
    }

    private companion object {
        const val LINK_GAP = 12
    }
}
