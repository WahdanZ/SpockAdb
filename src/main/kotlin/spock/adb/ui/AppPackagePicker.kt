package spock.adb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.JBUI
import spock.adb.LatestRequest
import spock.adb.command.GetApplicationIDCommand
import spock.adb.command.InstalledPackages
import spock.adb.command.ListInstalledPackagesCommand
import spock.adb.device.ConnectedDevice
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Which app the storage tab shows: the open project's app, picked on its own, or any other
 * installed app from the list.
 *
 * The list is editable, so a package that is not in it can still be typed. The project's app is
 * resolved each time the list loads rather than once when the tab opens, because Gradle sync
 * often finishes after that, and a field left empty is exactly what made the developer type it.
 *
 * Shared by the tool window's header and anything that needs to name an app, so there is one
 * answer to "which app" rather than one per tab.
 */
internal class AppPackagePicker(
    private val project: Project,
    private val isAlive: () -> Boolean,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)) {

    private val model = DefaultComboBoxModel<String>()
    private val combo = ComboBox(model, JBUI.scale(COMBO_WIDTH))
    private val refreshButton = JButton(AllIcons.Actions.Refresh)

    private var device: ConnectedDevice? = null
    private var projectApp: String? = null

    /** The last package handed to [onChosen], so a pick that fires twice does not list the files twice. */
    private var lastChosen: String? = null

    /** True while the list is being filled by code, when a change of selection is not the developer's pick. */
    private var populating = false

    /** Started and answered on the EDT, so a slow load cannot replace the list of a newer one. */
    private val loads = LatestRequest()

    /** Called on the EDT with the app that was picked, typed, or picked for the developer. */
    var onChosen: (String) -> Unit = {}

    /** Called on the EDT when the list of apps could not be read. */
    var onFailure: (String) -> Unit = {}

    /** The package shown now, including one typed but not yet confirmed. */
    val selected: String?
        get() = combo.editor.item?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    init {
        border = JBUI.Borders.empty()
        combo.isEditable = true
        // Arrow keys move through the list without choosing each app they pass on the way.
        combo.putClientProperty("JComboBox.isTableCellEditor", true)
        combo.toolTipText = "The app whose storage to show. It must be a debuggable build."
        combo.renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = if (value != null && value == projectApp) "$value  (this project)" else value.orEmpty()
        }
        combo.addActionListener {
            if (!populating) selected?.let(::choose)
        }
        refreshButton.toolTipText = "Reload the installed apps"
        refreshButton.addActionListener { load(device, keepSelection = true) }
        add(combo)
        add(refreshButton)
    }

    /**
     * Reads the apps installed on [connected] and selects the project's app, which is then handed
     * to [onChosen]. With [keepSelection], the app shown now stays selected and nothing is chosen.
     */
    fun load(connected: ConnectedDevice?, keepSelection: Boolean = false) {
        device = connected
        val request = loads.begin()
        val kept = selected.takeIf { keepSelection }
        if (!keepSelection) lastChosen = null
        projectApp = runCatching { GetApplicationIDCommand.resolve(project) }.getOrNull()
        if (connected == null) {
            fill(emptyList(), kept)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val installed = runCatching { ListInstalledPackagesCommand().execute(project, connected.device) }
            ApplicationManager.getApplication().invokeLater({
                if (!loads.isLatest(request)) return@invokeLater
                installed.onFailure { onFailure("Could not list the installed apps: ${it.message}") }
                val choices = InstalledPackages.choices(installed.getOrDefault(emptyList()), projectApp)
                // The project's app is shown even when it is not installed, so the reason it has no
                // files is said once it is listed, rather than the field being left for the developer.
                fill(choices, kept ?: projectApp)
                if (kept == null) projectApp?.let(::choose)
            }) { !isAlive() }
        }
    }

    /** Shows [packageName] again without choosing it, after the developer declined to leave unapplied edits. */
    fun revertTo(packageName: String?) {
        populating = true
        try {
            combo.selectedItem = packageName
            lastChosen = packageName
        } finally {
            populating = false
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        combo.isEnabled = enabled
        refreshButton.isEnabled = enabled
    }

    private fun choose(packageName: String) {
        if (packageName == lastChosen) return
        lastChosen = packageName
        onChosen(packageName)
    }

    private fun fill(choices: List<String>, shown: String?) {
        populating = true
        try {
            model.removeAllElements()
            choices.forEach { model.addElement(it) }
            // An editable combo box shows a value that is not in its list, which is what a typed or
            // uninstalled package needs.
            combo.selectedItem = shown
        } finally {
            populating = false
        }
    }

    private companion object {
        const val GAP = 2
        const val COMBO_WIDTH = 260
    }
}
