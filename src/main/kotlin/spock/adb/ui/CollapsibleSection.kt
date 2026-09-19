package spock.adb.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A card: a titled group of controls, on its own ground, that can be collapsed.
 *
 * The Devices tab was a single column of full-width buttons roughly fifteen rows tall, so in a
 * docked tool window most of it was below the fold and reaching Developer Options meant
 * scrolling past everything else. Grouping into sections let a developer keep open only what
 * they use; drawing each group as a card is what makes the grouping visible before it is read —
 * a titled separator left seven headings looking like one list.
 *
 * Written rather than taken from the platform so the behaviour is identical across every
 * supported IDE version — the collapsible panels in the platform have moved between packages
 * and visibility over the range this plugin supports.
 */
class CollapsibleSection(
    title: String,
    private val content: JComponent,
    /** Stable key for persisting the expanded state. */
    private val stateKey: String,
    expandedByDefault: Boolean = true,
    icon: Icon? = null,
) : JPanel(BorderLayout()) {

    private val plainTitle = title
    private val properties = PropertiesComponent.getInstance()

    private val heading = JBLabel(title, icon, JBLabel.LEADING).apply {
        font = font.deriveFont(Font.BOLD)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        iconTextGap = JBUI.scale(GAP)
    }

    private val marker = JBLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private var expanded: Boolean = properties.getBoolean(propertyKey(), expandedByDefault)

    /** Open because a search has matches inside, rather than because the developer opened it. */
    private var forced: Boolean = false

    /** Whether the content is showing, for whichever reason. */
    val isExpanded: Boolean get() = expanded || forced

    /**
     * Called after the developer expands or collapses this section.
     *
     * For a container whose arrangement depends on the state — so it can follow the click rather
     * than override it on the next resize.
     */
    var onToggled: (() -> Unit)? = null

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(TOP_INSET, 0, 0, 0),
            RoundedLineBorder(JBColor.border(), JBUI.scale(ARC), 1),
            JBUI.Borders.empty(PAD),
        )

        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(heading, BorderLayout.WEST)
            add(marker, BorderLayout.EAST)
        }
        val toClick = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
        }
        listOf(heading, marker, titleRow).forEach { it.addMouseListener(toClick) }

        add(titleRow, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        applyState()
    }

    /**
     * Opens the section while something inside it matches a search, without overwriting the
     * state the developer chose: a search that permanently expanded what it looked through
     * would leave the tab rearranged once the search was cleared.
     */
    fun setForcedExpanded(forced: Boolean) {
        if (this.forced == forced) return
        this.forced = forced
        applyState()
    }

    private fun toggle() {
        // A section collapsed by hand stays collapsed, search or no search.
        forced = false
        expanded = !expanded
        properties.setValue(propertyKey(), expanded, true)
        applyState()
        onToggled?.invoke()
    }

    private fun applyState() {
        val open = expanded || forced
        content.isVisible = open
        marker.text = if (open) EXPANDED_MARKER else COLLAPSED_MARKER
        heading.toolTipText = if (open) "Hide $plainTitle" else "Show $plainTitle"
        revalidate()
        repaint()
    }

    /** Hides the whole section, heading included, when every action in it is turned off. */
    fun setSectionVisible(visible: Boolean) {
        isVisible = visible
    }

    private fun propertyKey() = "spock.adb.section.$stateKey"

    private companion object {
        const val EXPANDED_MARKER = "▾"
        const val COLLAPSED_MARKER = "▸"
        const val TOP_INSET = 6
        const val ARC = 10
        const val PAD = 8
        const val GAP = 6
    }
}
