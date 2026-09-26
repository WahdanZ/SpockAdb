package spock.adb.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

/**
 * A view of the app chosen in [spock.adb.context.SpockSelection]: the open project's app, picked
 * on its own, or any other installed app from the list.
 *
 * The list is editable, so a package that is not in it can still be typed. It reads nothing
 * itself — the selection reads the apps and says which is chosen — so every picker on screen
 * shows the same answer.
 */
internal class AppPackagePicker : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)) {

    private val model = DefaultComboBoxModel<String>()
    private val combo = ComboBox(model, JBUI.scale(COMBO_WIDTH))

    private var projectApp: String? = null

    /** The last package shown or handed to [onChosen], so a pick that fires twice is one pick. */
    private var lastChosen: String? = null

    /** True while the list is being filled by code, when a change of selection is not the developer's pick. */
    private var populating = false

    /** Called on the EDT with the app that was picked or typed. */
    var onChosen: (String) -> Unit = {}

    /** The package shown now, including one typed but not yet confirmed. */
    val selected: String?
        get() = combo.editor.item?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    init {
        border = JBUI.Borders.empty()
        combo.isEditable = true
        // Arrow keys move through the list without choosing each app they pass on the way.
        combo.putClientProperty("JComboBox.isTableCellEditor", true)
        combo.toolTipText = "The app every Spock action uses. Storage needs a debuggable build."
        combo.renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = if (value != null && value == projectApp) "$value  (this project)" else value.orEmpty()
        }
        combo.addActionListener {
            if (!populating) selected?.let(::choose)
        }
        add(combo)
    }

    /** Shows [apps] with [shown] selected, without choosing anything. */
    fun show(apps: List<String>, projectApp: String?, shown: String?) {
        this.projectApp = projectApp
        populating = true
        try {
            if ((0 until model.size).map(model::getElementAt) != apps) {
                model.removeAllElements()
                apps.forEach { model.addElement(it) }
            }
            // An editable combo box shows a value that is not in its list, which is what a typed or
            // uninstalled package needs.
            combo.selectedItem = shown
            lastChosen = shown
        } finally {
            populating = false
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        combo.isEnabled = enabled
    }

    private fun choose(packageName: String) {
        if (packageName == lastChosen) return
        lastChosen = packageName
        onChosen(packageName)
    }

    private companion object {
        const val GAP = 2
        const val COMBO_WIDTH = 260
    }
}
