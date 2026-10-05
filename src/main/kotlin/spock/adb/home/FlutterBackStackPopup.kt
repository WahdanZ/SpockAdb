package spock.adb.home

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.flutter.navigation.FlutterNavigationState
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * The Flutter back stack, as App Back Stack shows the activities: one line per route, the root at
 * the top and the current route at the bottom, marked; then how sure Spock is, or why there is no
 * stack to show.
 */
internal object FlutterBackStackPopup {

    fun show(state: FlutterNavigationState, under: JComponent) {
        val rows = FlutterRouteText.rows(state)
        val list = rows.takeIf { it.isNotEmpty() }?.let(::RouteList)
        val panel = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
            border = JBUI.Borders.empty(GAP)
            list?.let { add(it, BorderLayout.CENTER) }
            add(footer(FlutterRouteText.footer(state)), BorderLayout.SOUTH)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, list)
            .setTitle("Flutter Back Stack")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(under)
    }

    /** Wrapped to a docked tool window's width; route names may hold anything, so it is escaped. */
    private fun footer(text: String) = JBLabel(
        "<html><body style='width: ${JBUI.scale(FOOTER_WIDTH)}px'>${StringUtil.escapeXmlEntities(text)}</body></html>",
    ).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    /** Opens on the current route, the last line. */
    private class RouteList(rows: List<FlutterRouteText.Row>) : JBList<FlutterRouteText.Row>(rows) {
        init {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = RouteRenderer()
            selectedIndex = rows.lastIndex
        }
    }

    /** A route's name, or its class quieter when it has none, with `root` / `current` on the right. */
    private class RouteRenderer : ListCellRenderer<FlutterRouteText.Row> {
        private val text = JBLabel().apply { putClientProperty(HTML_DISABLE, true) }
        private val mark = JBLabel().apply { font = JBFont.small().asBold() }
        private val panel = JPanel(BorderLayout(JBUI.scale(GAP * 2), 0)).apply {
            isOpaque = true
            add(text, BorderLayout.CENTER)
            add(mark, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out FlutterRouteText.Row>,
            value: FlutterRouteText.Row?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val row = value ?: return panel
            val muted = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
            panel.background = if (isSelected) list.selectionBackground else list.background
            panel.border = JBUI.Borders.empty(ROW_PAD, PAD)
            text.text = row.text
            text.font = if (row.unnamed) JBFont.label().asItalic() else JBFont.label()
            text.foreground = when {
                isSelected -> list.selectionForeground
                row.unnamed -> muted
                else -> list.foreground
            }
            mark.text = row.mark.orEmpty()
            mark.isVisible = row.mark != null
            mark.foreground = muted
            return panel
        }
    }

    private const val GAP = 4
    private const val PAD = 10
    private const val ROW_PAD = 3
    private const val FOOTER_WIDTH = 300
    private const val HTML_DISABLE = "html.disable"
}
