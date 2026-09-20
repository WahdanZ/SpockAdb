package spock.adb.logcat

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * The log drawn as a list of records: one row, one log line.
 *
 * Kept alongside [LogcatEditorView] rather than replaced by it, because the two are good at
 * different things. A row is a discrete object: selection is always whole records, a renderer
 * can draw things text cannot — the level chip here is a painted bar, not a letter — and there
 * is no document to keep in step with anything. What it cannot do is let you select *text*: a
 * list cell is all-or-nothing, so dragging from the middle of one message into the next is
 * impossible by construction. That is why the editor is the default and this is the switch.
 */
class LogcatListView : LogcatView {

    private val model = DefaultListModel<LogcatEntry>()
    private val list = JBList(model)
    private val scroll = JBScrollPane(list).apply { border = JBUI.Borders.empty() }

    override var onSelectionChanged: () -> Unit = {}

    override val component: JComponent get() = scroll

    override val contentComponent: JComponent get() = list

    init {
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.cellRenderer = LogcatRowRenderer()
        list.setEmptyText("Not streaming. Select a device and press Live.")
        list.addListSelectionListener { event -> if (!event.valueIsAdjusting) onSelectionChanged() }
    }

    override fun entries(): List<LogcatEntry> = (0 until model.size()).map(model::get)

    override fun size(): Int = model.size()

    override fun selectedEntries(): List<LogcatEntry> = list.selectedValuesList.toList()

    override fun caretIndex(): Int? = list.selectedIndex.takeIf { it >= 0 && list.selectedIndices.size == 1 }

    override fun setAll(replacement: List<LogcatEntry>, autoScroll: Boolean) {
        model.clear()
        replacement.forEach(model::addElement)
        if (autoScroll) scrollToEnd()
    }

    override fun append(batch: List<LogcatEntry>, autoScroll: Boolean) {
        if (batch.isEmpty()) return
        batch.forEach(model::addElement)
        // The visible model stays bounded independently of the backing buffer.
        while (model.size() > VISIBLE_LIMIT) model.remove(0)
        if (autoScroll) scrollToEnd()
    }

    override fun clear() = model.clear()

    override fun scrollToEnd() {
        if (model.size() > 0) list.ensureIndexIsVisible(model.size() - 1)
    }

    override fun focusLineAt(point: Point) {
        val index = list.locationToIndex(point)
        // Right-clicking inside the selection keeps it, so a multi-row copy is not lost to a
        // stray click; clicking elsewhere acts on the row under the cursor.
        if (index >= 0 && index !in list.selectedIndices) list.selectedIndex = index
    }

    override fun installCopyShortcut(copy: () -> Unit) {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        list.inputMap.put(stroke, COPY_ACTION)
        list.actionMap.put(
            COPY_ACTION,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = copy()
            },
        )
    }

    override fun dispose() = model.clear()

    private companion object {
        const val VISIBLE_LIMIT = 10_000
        const val COPY_ACTION = "spock.logcat.copy"
    }
}

/**
 * One log line, drawn as a hierarchy rather than as a coloured string.
 *
 * The weight follows the reading order — dim time, a small colour chip for the level, the tag in
 * a secondary colour, the message at full contrast — and colour is spent only where it carries
 * information. Colouring whole rows by severity, which is where this started, made a screen with
 * a few errors read as a wall of red and buried the only part anyone reads.
 */
internal class LogcatRowRenderer : ColoredListCellRenderer<LogcatEntry>() {

    override fun customizeCellRenderer(
        list: JList<out LogcatEntry>,
        value: LogcatEntry?,
        index: Int,
        selected: Boolean,
        cellHasFocus: Boolean,
    ) {
        val entry = value ?: return
        ipad = JBUI.insets(0, PAD_H, 0, PAD_H)
        // Log text is code: proportional fonts make a wrapped stack frame hard to follow.
        font = JBUI.Fonts.create(Font.MONOSPACED, list.font.size)

        if (entry.timestamp.isEmpty()) {
            // A banner — `--------- beginning of main` — has no fields to lay out.
            append(entry.message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }

        val highlight = LogcatHighlighter.classify(entry)
        val continuation = continuesAbove(list, index, entry)
        // The chip marks a statement, not a line. On a continuation it is replaced by an empty
        // icon of the same size rather than dropped, so the whole block stays in one column.
        icon = if (continuation) BLANK_CHIP else chip(highlight, entry.level)
        iconTextGap = JBUI.scale(GAP)

        // A line that continues the statement above repeats nothing: not the timestamp, not
        // the level, not the tag. Twelve lines of a JSON body are one statement, and printing
        // its labels twelve times pushes the body itself out of the reading order.
        val time = entry.timestamp.substringAfter(' ', entry.timestamp)
        if (continuation) {
            append(" ".repeat(time.length), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        } else {
            append(time, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        append("  ")
        if (entry.tag.isNotEmpty()) {
            val repeated = continuation || repeatsTag(list, index, entry)
            if (repeated) {
                append(" ".repeat(entry.tag.length), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            } else {
                append(entry.tag, TAG)
            }
            append("  ")
        }
        append(entry.message, message(highlight))
    }

    /** Read from the model, so scrolling never changes which rows show a tag. */
    private fun repeatsTag(list: JList<out LogcatEntry>, index: Int, entry: LogcatEntry): Boolean {
        val previous = previousOf(list, index) ?: return false
        return previous.tag == entry.tag && previous.pid == entry.pid
    }

    private fun continuesAbove(list: JList<out LogcatEntry>, index: Int, entry: LogcatEntry): Boolean {
        val previous = previousOf(list, index) ?: return false
        return LogcatGroup.continues(previous, entry)
    }

    private fun previousOf(list: JList<out LogcatEntry>, index: Int): LogcatEntry? =
        if (index <= 0 || index >= list.model.size) null else list.model.getElementAt(index - 1)

    private fun message(highlight: LogcatHighlighter.Highlight): SimpleTextAttributes = when (highlight) {
        LogcatHighlighter.Highlight.CRASH -> CRASH_TEXT
        LogcatHighlighter.Highlight.ANR -> ANR_TEXT
        LogcatHighlighter.Highlight.ERROR -> ERROR_TEXT
        // A warning is worth noticing, not worth shouting: the chip carries it.
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    private fun chip(highlight: LogcatHighlighter.Highlight, level: LogLevel): Icon {
        val colour = when (highlight) {
            LogcatHighlighter.Highlight.CRASH -> Palette.CRASH
            LogcatHighlighter.Highlight.ANR -> Palette.ANR
            else -> levelColour(level)
        }
        return CHIPS.getOrPut(colour) { LevelChip(colour) }
    }

    private fun levelColour(level: LogLevel): JBColor = when (level) {
        LogLevel.ASSERT, LogLevel.ERROR -> Palette.ERROR
        LogLevel.WARN -> Palette.WARNING
        LogLevel.INFO -> Palette.INFO
        LogLevel.DEBUG -> Palette.DEBUG
        LogLevel.VERBOSE -> Palette.VERBOSE
    }

    /**
     * A rounded bar in the level's colour.
     *
     * A letter would need a legend and would compete with the tag for attention; a bar reads as
     * severity at a glance and takes four pixels of width to do it.
     */
    private class LevelChip(private val colour: JBColor) : Icon {

        override fun getIconWidth(): Int = JBUI.scale(CHIP_W)

        override fun getIconHeight(): Int = JBUI.scale(CHIP_H)

        override fun paintIcon(component: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colour
                val arc = JBUI.scale(CHIP_W)
                g2.fillRoundRect(x, y, iconWidth, iconHeight, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    /** Light value first, dark second — a hex pair per role, not arithmetic. */
    @Suppress("MagicNumber")
    private object Palette {
        val CRASH = JBColor(0xC7222A, 0xFF6B6B)
        val ANR = JBColor(0x7B1FA2, 0xCE93D8)
        val ERROR = JBColor(0xB3261E, 0xF2857C)
        val WARNING = JBColor(0xB08000, 0xE0A030)
        val INFO = JBColor(0x3574F0, 0x548AF7)
        val DEBUG = JBColor(0x9AA0A6, 0x6E7377)
        val VERBOSE = JBColor(0xC4C8CC, 0x50565B)
        val TAG = JBColor(0x6C707E, 0x9DA0A8)
    }

    private companion object {
        const val PAD_H = 6
        const val GAP = 6
        const val CHIP_W = 3
        const val CHIP_H = 11

        val TAG = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.TAG)
        val CRASH_TEXT = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.CRASH)
        val ANR_TEXT = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Palette.ANR)
        val ERROR_TEXT = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.ERROR)

        /** One icon per colour, not per row: a busy log repaints thousands of times a minute. */
        val CHIPS = mutableMapOf<JBColor, Icon>()

        /** Holds the chip's column open on a line that has no chip of its own. */
        val BLANK_CHIP: Icon = EmptyIcon.create(JBUI.scale(CHIP_W), JBUI.scale(CHIP_H))
    }
}
