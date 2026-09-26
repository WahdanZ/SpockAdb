package spock.adb.commandcenter

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Completion for the Command Center's command field: a list of what can come next under the
 * field, and the documentation of the selected entry — or of the command being typed — below it.
 *
 * The popup never takes focus, so typing carries on in the field. ↑/↓ choose, Tab inserts,
 * Enter inserts only once an entry has been chosen (otherwise it runs the command, as before),
 * Esc closes, and Ctrl+Space opens it on demand.
 */
internal class CommandCompletionPopup(
    private val field: JTextComponent,
    private val packages: () -> List<String>,
    private val completer: ShellCompleter = ShellCompleter(),
) : Disposable {

    private val list = JBList<ShellCompleter.Suggestion>().apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = VISIBLE_ROWS
        cellRenderer = SuggestionRenderer()
    }
    private val doc = JBLabel().apply {
        border = JBUI.Borders.empty(DOC_PAD)
        verticalAlignment = JBLabel.TOP
    }
    private val hint = JBLabel("↑↓ choose · Tab or Enter insert · Esc close").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
        border = JBUI.Borders.empty(0, DOC_PAD, DOC_PAD, DOC_PAD)
    }
    private val content = JPanel(BorderLayout()).apply {
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLineTop(JBColor.border())
                add(doc, BorderLayout.CENTER)
                add(hint, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )
    }

    private var popup: JBPopup? = null
    private var result: ShellCompleter.Result? = null

    val isShowing: Boolean get() = popup?.isVisible == true

    init {
        field.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = scheduleRefresh(open = field.isFocusOwner)
                override fun removeUpdate(e: DocumentEvent) = scheduleRefresh(open = false)
                override fun changedUpdate(e: DocumentEvent) = Unit
            },
        )
        // Tab is ours while completing; outside of that it still moves focus, by hand below.
        field.focusTraversalKeysEnabled = false
        field.addKeyListener(Keys())
        field.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = hide()
            },
        )
        list.addListSelectionListener { showDoc() }
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    list.locationToIndex(e.point).takeIf { it >= 0 }?.let { insert(list.model.getElementAt(it)) }
                }
            },
        )
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    override fun dispose() = hide()

    // The caret moves after the document event, so the word at the caret is read once it has.
    private fun scheduleRefresh(open: Boolean) =
        ApplicationManager.getApplication().invokeLater { refresh(open || isShowing) }

    private fun refresh(open: Boolean) {
        if (!open) return
        val completion = completer.complete(field.text, field.caretPosition, packages())
        result = completion
        if (completion.suggestions.isEmpty()) {
            hide()
            return
        }
        list.setListData(completion.suggestions.toTypedArray())
        // After a space nothing is chosen yet, so Enter still runs the command; once a word is
        // being typed the best match is chosen, so Enter or Tab finishes it.
        if (completion.prefix.isEmpty()) list.clearSelection() else list.selectedIndex = 0
        list.ensureIndexIsVisible(maxOf(list.selectedIndex, 0))
        showDoc()
        show()
    }

    private fun show() {
        val current = popup
        if (current != null && current.isVisible) {
            current.pack(true, true)
            return
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(false)
            .createPopup()
            .also { it.show(RelativePoint(field, Point(0, field.height))) }
    }

    private fun showDoc() {
        val chosen = list.selectedValue
        val (usage, summary) = when {
            chosen != null -> chosen.usage to chosen.summary
            else -> result?.context?.let { it.usage to it.summary } ?: ("" to "Choose what comes next.")
        }
        doc.text = "<html><body style='width:${JBUI.scale(DOC_WIDTH)}px'>" +
            (if (usage.isNotEmpty()) "<code><b>${escape(usage)}</b></code><br>" else "") +
            "${escape(summary)}</body></html>"
        popup?.takeIf { it.isVisible }?.pack(true, true)
    }

    private fun insert(suggestion: ShellCompleter.Suggestion) {
        val completion = result ?: return
        val (text, caret) = completer.accept(field.text, completion, suggestion, field.caretPosition)
        field.text = text
        field.caretPosition = caret
        // What can follow the inserted word is the next thing to choose.
        scheduleRefresh(open = true)
    }

    private fun move(delta: Int) {
        val size = list.model.size
        if (size == 0) return
        val next = if (list.selectedIndex < 0) 0 else (list.selectedIndex + delta).mod(size)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private inner class Keys : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            val handled = when {
                e.keyCode == KeyEvent.VK_SPACE && e.isControlDown -> true.also { refresh(open = true) }
                e.keyCode == KeyEvent.VK_TAB -> true.also { tab(e.isShiftDown) }
                isShowing -> navigate(e.keyCode)
                else -> false
            }
            if (handled) e.consume()
        }

        private fun tab(backward: Boolean) = when {
            backward -> field.transferFocusBackward()
            isShowing -> insert(list.selectedValue ?: list.model.getElementAt(0))
            else -> field.transferFocus()
        }

        /** Whether the key was the popup's; an unchosen Enter is not, so it still runs the command. */
        private fun navigate(keyCode: Int): Boolean {
            when (keyCode) {
                KeyEvent.VK_DOWN -> move(1)
                KeyEvent.VK_UP -> move(-1)
                KeyEvent.VK_ESCAPE -> hide()
                KeyEvent.VK_ENTER -> list.selectedValue?.let(::insert) ?: run {
                    hide()
                    return false
                }
                else -> return false
            }
            return true
        }
    }

    private class SuggestionRenderer : ColoredListCellRenderer<ShellCompleter.Suggestion>() {
        override fun customizeCellRenderer(
            list: JList<out ShellCompleter.Suggestion>,
            value: ShellCompleter.Suggestion?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            val style = if (value.kind == ShellCompleter.Kind.FLAG) {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            }
            append(value.text, style)
            append("  ${value.summary}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        const val VISIBLE_ROWS = 8
        const val DOC_PAD = 6
        const val DOC_WIDTH = 420

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
