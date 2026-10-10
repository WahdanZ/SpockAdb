package spock.adb.home

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import spock.adb.ApplicationBackStack
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.ui.AppBackStackList
import spock.adb.ui.className
import spock.adb.ui.toAppBackStackRows
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * App Back Stack for an app with a Flutter session: its activities, exactly as App Back Stack lists
 * them, and its Flutter routes, each a section of its own. The routes are not set under an
 * activity: with add-to-app, Spock cannot prove which activity hosts the Flutter engine.
 *
 * Each section stands alone: one that could not be read says why, and the other is still shown.
 */
internal object AppBackStackPopup {

    fun show(
        activities: Result<ApplicationBackStack>,
        routes: FlutterNavigationState,
        title: String,
        under: JComponent,
        open: (className: String) -> Unit,
    ) {
        var popup: JBPopup? = null
        val list = activities.getOrNull()?.takeIf { it.activities.isNotEmpty() }?.let { stack ->
            AppBackStackList(stack.activities.toAppBackStackRows(stack.applicationId)).apply {
                onChosen {
                    popup?.cancel()
                    open(it)
                }
            }
        }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(GAP)
            add(heading(FlutterRouteText.ACTIVITIES))
            add(list ?: FlutterRoutesSection.note(FlutterRouteText.activitiesNote(activities).orEmpty()))
            add(heading(FlutterRouteText.FLUTTER_ROUTES).apply { border = JBUI.Borders.empty(SECTION_GAP, 0, GAP, 0) })
            add(FlutterRoutesSection.create(routes))
            components.forEach { (it as JComponent).alignmentX = Component.LEFT_ALIGNMENT }
        }
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, list)
            .setTitle(title)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .createPopup()
        popup = created
        created.showUnderneathOf(under)
    }

    /** As the chooser App Back Stack always was: a click or Enter opens the row's class. */
    private fun AppBackStackList.onChosen(choose: (className: String) -> Unit) {
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    val index = locationToIndex(event.point)
                    if (index >= 0 && getCellBounds(index, index)?.contains(event.point) == true) {
                        choose(model.getElementAt(index).className)
                    }
                }
            },
        )
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), CHOOSE)
        actionMap.put(
            CHOOSE,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    selectedValue?.let { choose(it.className) }
                }
            },
        )
    }

    private fun heading(text: String) = JBLabel(text).apply {
        font = JBFont.label().asBold()
        border = JBUI.Borders.emptyBottom(GAP)
    }

    private const val GAP = 4
    private const val SECTION_GAP = 12
    private const val CHOOSE = "spock.chooseRow"
}
