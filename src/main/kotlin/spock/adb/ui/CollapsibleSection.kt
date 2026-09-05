package spock.adb.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A titled section that can be collapsed, remembering its state between sessions.
 *
 * The Devices tab was a single column of full-width buttons roughly fifteen rows tall, so in
 * a docked tool window most of it was below the fold and reaching Developer Options meant
 * scrolling past everything else. Grouping into collapsible sections lets a developer keep
 * open only what they use.
 *
 * Written rather than taken from the platform so the behaviour is identical across every
 * supported IDE version — the collapsible panels in the platform have moved between
 * packages and visibility over the range this plugin supports.
 */
class CollapsibleSection(
    title: String,
    private val content: JComponent,
    /** Stable key for persisting the expanded state. */
    private val stateKey: String,
    expandedByDefault: Boolean = true,
) : JPanel(BorderLayout()) {

    private val separator = TitledSeparator(title)
    private val plainTitle = title
    private val properties = PropertiesComponent.getInstance()

    private var expanded: Boolean = properties.getBoolean(propertyKey(), expandedByDefault)

    init {
        separator.label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        separator.label.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle()
            },
        )
        separator.border = JBUI.Borders.emptyTop(TOP_INSET)

        add(separator, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        applyState()
    }

    private fun toggle() {
        expanded = !expanded
        properties.setValue(propertyKey(), expanded, true)
        applyState()
    }

    private fun applyState() {
        content.isVisible = expanded
        val marker = if (expanded) EXPANDED_MARKER else COLLAPSED_MARKER
        separator.label.text = "$marker  $plainTitle"
        revalidate()
        repaint()
    }

    /**
     * Opens or closes the section without recording the change.
     *
     * For callers that decide by layout rather than by click: a panel too short to show the
     * section open should not overwrite the developer's own last choice, which they will want
     * back the moment the panel is roomy again.
     */
    fun setExpandedTransiently(expanded: Boolean) {
        if (this.expanded == expanded) return
        this.expanded = expanded
        applyState()
    }

    /** Hides the whole section, heading included, when every action in it is turned off. */
    fun setSectionVisible(visible: Boolean) {
        isVisible = visible
    }

    private fun propertyKey() = "spock.adb.section.$stateKey"

    private companion object {
        const val EXPANDED_MARKER = "▾"
        const val COLLAPSED_MARKER = "▸"
        const val TOP_INSET = 4
    }
}
