package spock.adb

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.ui.WrapLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * The actions a developer pinned to the top of the Devices tab, in the order they put them in.
 *
 * The tab is fifteen buttons of near-identical weight under six headings, so the ones somebody
 * runs twenty times a day sit wherever the grouping happened to put them — two sections down,
 * behind a heading they have to keep expanded. Pinning moves the button rather than copying it:
 * the same action in two places on one screen is worse than either place alone.
 *
 * Right-click is how an action is pinned, unpinned or nudged along the row; pinned buttons can
 * also be dragged over one another. Its own component so [SpockAdbViewer], which Detekt already
 * watches, gains a field rather than a drag implementation.
 */
internal class QuickActionsBar(
    /** Called after the pinned set or its order changed, to lay the tab out again. */
    private val onChanged: () -> Unit,
) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP))) {

    private val settings = AppSettingService.getInstance()
    private val properties = PropertiesComponent.getInstance()

    private val hint = JBLabel(HINT).apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    /** Where a drag started, as an index into [pinned]; -1 when nothing is being dragged. */
    private var draggingFrom = -1

    var pinned: List<QuickAction> = settings.pinnedActions().ifEmpty { defaultPins() }
        private set

    /**
     * True until the developer has pinned something, which is when the hint has done its job.
     *
     * Right-click is not discoverable, and an empty band above every action on the tab forever
     * would be a worse answer than the one it is explaining.
     */
    private val needsHint: Boolean
        get() = pinned.isEmpty() && !properties.getBoolean(HAS_PINNED_KEY, false)

    /** True once the developer has chosen for themselves, after which the defaults are gone. */
    private val chosen: Boolean get() = properties.getBoolean(HAS_PINNED_KEY, false)

    /** Whether there is anything to show: an empty row with no hint left is not a section. */
    val hasContent: Boolean get() = pinned.isNotEmpty() || needsHint

    init {
        border = JBUI.Borders.empty(GAP, 0)
    }

    /**
     * Adds the right-click menu, and on a pinned action the drag, to [button].
     *
     * Called once per button. The menu is built when it opens rather than now, because whether
     * an action is pinned — and whether it can move further left — changes under it.
     */
    fun install(action: QuickAction, button: JButton) {
        // Both listeners, from one adapter: `addMouseListener` alone registers the click half,
        // and the drag arrives on the motion half.
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // macOS raises the popup trigger on press, X11 and Windows on release.
                if (e.isPopupTrigger) return showMenu(action, button, e)
                draggingFrom = pinned.indexOf(action)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(action, button, e)
                val from = draggingFrom
                draggingFrom = -1
                button.cursor = Cursor.getDefaultCursor()
                if (from >= 0) dropped(from, e)
            }

            override fun mouseDragged(e: MouseEvent) {
                if (draggingFrom >= 0) button.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }
        }
        button.addMouseListener(mouse)
        button.addMouseMotionListener(mouse)
    }

    /** Fills the row with [buttons], which the caller has already put in [pinned] order. */
    fun fill(buttons: List<JButton>) {
        removeAll()
        if (buttons.isEmpty() && needsHint) add(hint) else buttons.forEach { add(it) }
        revalidate()
        repaint()
    }

    private fun dropped(from: Int, e: MouseEvent) {
        // Only a drop onto the row itself reorders. Releasing over the button it started on, or
        // anywhere off the row, leaves the order alone — and the click goes through as a click.
        val point = SwingUtilities.convertPoint(e.component, e.point, this)
        if (!contains(point)) return
        val reordered = movedTo(pinned, from, dropIndexAt(buttonBounds(), point))
        if (reordered != pinned) save(reordered)
    }

    private fun buttonBounds(): List<Rectangle> = components.map { it.bounds }

    private fun showMenu(action: QuickAction, button: JButton, e: MouseEvent) {
        val index = pinned.indexOf(action)
        JPopupMenu().apply {
            if (index < 0) {
                add(item("Pin to quick actions") { save(pinned + action) })
            } else {
                add(item("Unpin from quick actions") { save(pinned - action) })
                addSeparator()
                add(item("Move left", enabled = index > 0) { save(movedTo(pinned, index, index - 1)) })
                add(
                    item("Move right", enabled = index < pinned.lastIndex) {
                        save(movedTo(pinned, index, index + 2))
                    },
                )
            }
        }.show(button, e.x, e.y)
    }

    private fun item(text: String, enabled: Boolean = true, onClick: () -> Unit) = JMenuItem(text).apply {
        isEnabled = enabled
        addActionListener { onClick() }
    }

    /**
     * What is pinned before anybody has pinned anything.
     *
     * An empty Quick actions row explaining itself is a worse first impression than the three
     * actions almost everyone reaches for; these are the ones the rest of the tab is arranged
     * around. Once the developer pins or unpins anything, their list is the list.
     */
    private fun defaultPins(): List<QuickAction> =
        if (chosen) {
            emptyList()
        } else {
            listOf(QuickAction.RESTART_APP, QuickAction.ATTACH_DEBUGGER, QuickAction.CURRENT_ACTIVITY)
        }

    private fun save(next: List<QuickAction>) {
        pinned = next
        settings.savePinnedActions(next)
        if (next.isNotEmpty()) properties.setValue(HAS_PINNED_KEY, true, false)
        onChanged()
    }

    private companion object {
        const val GAP = 4
        const val HINT = "Right-click any action below to pin it here."
        const val HAS_PINNED_KEY = "spock.adb.quickActions.used"
    }
}
