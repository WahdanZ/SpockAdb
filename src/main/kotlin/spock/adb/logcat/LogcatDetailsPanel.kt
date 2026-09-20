package spock.adb.logcat

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Everything about one line that does not belong on every line.
 *
 * The fields a developer needs occasionally — PID, TID, the owning process, the raw text as the
 * device sent it, and the stack trace the line is part of — were previously either in every row
 * or nowhere. Neither works: in every row they crowd out the message, and nowhere means leaving
 * the IDE for a terminal to read a trace.
 *
 * Shown only while a row is selected, so the list is what the panel is about.
 */
class LogcatDetailsPanel : JPanel(BorderLayout()) {

    private val title = JBLabel("Log details").apply {
        font = font.deriveFont(Font.BOLD)
    }

    private val fields = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
        border = JBUI.Borders.empty(2, GAP)
    }

    private val body = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        border = JBUI.Borders.empty(2, GAP)
    }

    private val copyLine = JButton("Copy raw line", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the line exactly as the device sent it"
        addActionListener { copy(entry?.raw) }
    }

    private val copyGroup = JButton("Copy block", AllIcons.Actions.Copy).apply {
        // A selection is copied raw — it is usually on its way into a bug report — while a
        // trace or a body is copied as messages, which is what stays readable when pasted.
        addActionListener {
            copy(
                group.takeIf { it.isMultiLine }
                    ?.let { if (it.kind == LogcatGroup.Kind.SELECTION) it.renderRaw() else it.render() },
            )
        }
    }

    private val close = JButton(AllIcons.Actions.Close).apply {
        toolTipText = "Hide the details"
        addActionListener { onClose() }
    }

    /** Called when the developer closes the panel, so the owner can collapse the splitter. */
    var onClose: () -> Unit = {}

    /** Reported so the owner can say what was copied without reaching into this panel. */
    var onCopied: (String) -> Unit = {}

    private var entry: LogcatEntry? = null
    private var group: LogcatGroup.Group = LogcatGroup.Group(emptyList(), LogcatGroup.Kind.SINGLE)

    init {
        border = JBUI.Borders.emptyTop(2)
        add(header(), BorderLayout.NORTH)
        add(
            JPanel(BorderLayout()).apply {
                add(fields, BorderLayout.NORTH)
                add(JBScrollPane(body).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
        show(null, LogcatGroup.Group(emptyList(), LogcatGroup.Kind.SINGLE), null)
    }

    private fun header(): JComponent = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
        border = JBUI.Borders.empty(2, GAP)
        add(title)
        add(copyLine)
        add(copyGroup)
        add(close)
    }

    /**
     * @param appPackage the selected app's id, used only to say whether the line came from it —
     *   the panel never resolves anything itself, so it stays free of device calls.
     */
    fun show(selected: LogcatEntry?, selectedGroup: LogcatGroup.Group, appPackage: String?) {
        entry = selected
        group = selectedGroup

        fields.removeAll()
        if (selected == null) {
            title.text = LogcatGroup.Kind.SINGLE.label
            body.text = ""
            copyLine.isEnabled = false
            copyGroup.isEnabled = false
            fields.add(hint("Select a line to see its process, its raw text and the block it is part of."))
        } else {
            // The heading names what was found, so a block of 30 lines is not mistaken for one
            // line with a scrollbar.
            title.text = when {
                selectedGroup.isMultiLine -> "${selectedGroup.kind.label} · ${selectedGroup.entries.size} lines"
                else -> LogcatGroup.Kind.SINGLE.label
            }
            copyGroup.text = "Copy ${selectedGroup.kind.label.lowercase()}"
            copyGroup.toolTipText = "Copy every line shown below"
            copyLine.isEnabled = true
            copyGroup.isEnabled = selectedGroup.isMultiLine
            describe(selected, selectedGroup, appPackage).forEach { (name, value) -> fields.add(field(name, value)) }
            body.text = bodyText(selected, selectedGroup)
            body.caretPosition = 0
        }
        fields.revalidate()
        fields.repaint()
    }

    private fun describe(
        entry: LogcatEntry,
        group: LogcatGroup.Group,
        appPackage: String?,
    ): List<Pair<String, String>> = when (group.kind) {
        // For a chosen set of rows, the fields of the first one would be a half-truth. What is
        // worth knowing is the shape of the selection: how much, from where, over what span.
        LogcatGroup.Kind.SELECTION -> summarise(group)
        else -> buildList {
            if (entry.timestamp.isNotEmpty()) add("Time" to entry.timestamp)
            add("Level" to entry.level.label)
            if (entry.tag.isNotEmpty()) add("Tag" to entry.tag)
            if (entry.pid != 0) add("PID / TID" to "${entry.pid} / ${entry.tid}")
            appPackage?.takeIf { it.isNotBlank() }?.let { add("App" to it) }
        }
    }

    private fun summarise(group: LogcatGroup.Group): List<Pair<String, String>> = buildList {
        val entries = group.entries
        add("Lines" to entries.size.toString())

        val tags = entries.map { it.tag }.filter { it.isNotEmpty() }.distinct()
        if (tags.isNotEmpty()) add("Tags" to tags.take(MAX_LISTED).joinToString(", ") + more(tags.size))

        val pids = entries.map { it.pid }.filter { it != 0 }.distinct()
        if (pids.isNotEmpty()) add("Processes" to pids.take(MAX_LISTED).joinToString(", ") + more(pids.size))

        val stamps = entries.map { it.timestamp }.filter { it.isNotEmpty() }
        if (stamps.size > 1) add("Span" to "${stamps.first()} → ${stamps.last()}")

        val levels = entries.map { it.level }.distinct().sortedByDescending { it.ordinal }
        add("Levels" to levels.joinToString(", ") { it.label })
    }

    private fun more(total: Int): String = if (total > MAX_LISTED) " +${total - MAX_LISTED} more" else ""

    /**
     * The block first, the raw record second.
     *
     * The block is what was asked for by clicking; the raw line is the detail underneath it,
     * and putting it first meant scrolling past log furniture to reach the JSON you selected.
     */
    private fun bodyText(entry: LogcatEntry, group: LogcatGroup.Group): String = when {
        group.kind == LogcatGroup.Kind.SELECTION -> group.renderRaw()
        group.isMultiLine -> "${group.render()}\n\n--- raw record ---\n${entry.raw}"
        else -> entry.raw
    }

    private fun field(name: String, value: String): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(JBLabel("$name:").apply { foreground = UIUtil.getContextHelpForeground() })
            add(JBLabel(value))
        }

    private fun hint(text: String): JComponent =
        JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun copy(text: String?) {
        if (text.isNullOrEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        onCopied("Copied ${text.lines().size} line(s).")
    }

    private companion object {
        const val GAP = 4
        const val MAX_LISTED = 4
    }
}
