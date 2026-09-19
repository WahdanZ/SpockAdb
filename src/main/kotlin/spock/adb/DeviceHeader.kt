package spock.adb

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.GetApplicationIDCommand
import spock.adb.device.ConnectedDevice
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * What every action on the Devices tab is about: the device it runs on, and the app it acts on.
 *
 * Pinned above the scrolling column rather than sitting at the top of it. The tab is long
 * enough that the device dropdown scrolled out of view well before Network or App storage, so
 * the answer to "what is this about to affect" was somewhere above the fold — for actions that
 * clear an app's data.
 *
 * The app is a label rather than a picker because it is not a choice: every app action on this
 * tab resolves the application ID of the open project's app module. Saying so is the point —
 * with the package only in the project's build files, a developer with two projects open had
 * nothing on screen telling them which one Clear data would empty.
 *
 * Its own component, as [HttpProxyRow] is, so [SpockAdbViewer] does not grow past the size
 * Detekt already flags.
 */
internal class DeviceHeader(private val project: Project) : JPanel(GridBagLayout()) {

    val deviceCombo = JComboBox<String>()

    val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Choose which actions are shown"
    }

    private val appLabel = JBLabel().apply { putClientProperty(HTML_DISABLE, true) }

    private val appSource = JBLabel(PROJECT_SOURCE).apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    /**
     * Which device AI agents are driving, when that is not the one selected here.
     *
     * These two selections are genuinely independent: an agent chooses with
     * `android_select_device` and this dropdown does not follow it, so a developer can be
     * watching one phone while an agent clears app data on another. Nothing surfaced that
     * before, which made it a trap rather than a choice.
     */
    private val agentTargetLabel = JBLabel().apply { isVisible = false }

    /**
     * Narrows the tab to the actions whose name answers what is typed.
     *
     * Fifteen buttons of near-identical weight under six headings is a lot to read through for
     * the one you want, and the heading it is under may be collapsed — so the search opens the
     * sections that hold a match rather than searching only what happens to be on screen.
     */
    private val actionSearch = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search actions…"
        toolTipText = "Show only the matching actions, opening the sections that hold them"
    }

    /** Called on the EDT with what is typed in the search field. */
    var onSearch: (String) -> Unit = {}

    init {
        border = JBUI.Borders.empty(GAP, GAP, GAP, GAP)
        // A long device or package name must not widen the tool window; both elide instead.
        deviceCombo.minimumSize = java.awt.Dimension(0, deviceCombo.preferredSize.height)
        deviceCombo.prototypeDisplayValue = ""
        appLabel.minimumSize = java.awt.Dimension(0, appLabel.preferredSize.height)

        add(JBLabel("Device:"), labelConstraints(row = 0))
        add(deviceCombo, fieldConstraints(row = 0))
        add(settingsButton, trailingConstraints(row = 0))

        add(JBLabel("App:"), labelConstraints(row = 1))
        add(appLabel, fieldConstraints(row = 1))
        add(appSource, trailingConstraints(row = 1))

        add(actionSearch, wideConstraints(row = 2))
        add(agentTargetLabel, wideConstraints(row = 3))
        actionSearch.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = onSearch(actionSearch.text)
            },
        )
        refreshApp()
    }

    /** Re-reads the project's application ID, which Gradle sync can resolve long after startup. */
    fun refreshApp() {
        val applicationId = runCatching { GetApplicationIDCommand.resolve(project) }.getOrNull()
        appLabel.text = applicationId ?: UNRESOLVED
        appLabel.foreground = if (applicationId == null) WARNING else UIUtil.getLabelForeground()
        appLabel.toolTipText = if (applicationId == null) UNRESOLVED_HINT else RESOLVED_HINT
        appSource.isVisible = applicationId != null
    }

    fun setDeviceDetails(selected: ConnectedDevice?, hint: String? = null) {
        // Plain text, never HTML: the model name comes off the device, and a label whose text
        // starts with a tag is rendered as markup.
        deviceCombo.toolTipText = hint ?: selected?.info?.let { "${it.describe()} \u00b7 ${it.details()}" }
    }

    /**
     * Says something only when there is something to say.
     *
     * Silent when the server is stopped, and silent when the agent is on the same device the
     * developer is looking at — a permanent "everything agrees" banner would train them to stop
     * reading it, which is the opposite of what the mismatch case needs.
     */
    fun setAgentTarget(serial: String?) {
        agentTargetLabel.isVisible = serial != null
        if (serial == null) return
        agentTargetLabel.text = "<html>⚠ AI agents are targeting <b>$serial</b>, not the device selected here.</html>"
        agentTargetLabel.foreground = WARNING
        agentTargetLabel.toolTipText =
            "An agent chose this device with android_select_device. Actions you run from this " +
                "panel still apply to the device in the dropdown above."
    }

    private fun labelConstraints(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0, 2, GAP)
    }

    private fun fieldConstraints(row: Int) = GridBagConstraints().apply {
        gridx = 1
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0)
    }

    private fun trailingConstraints(row: Int) = GridBagConstraints().apply {
        gridx = 2
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, GAP, 2, 0)
    }

    private fun wideConstraints(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        gridwidth = GRID_COLUMNS
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0)
    }

    private companion object {
        const val GAP = 4
        const val GRID_COLUMNS = 3

        /** Swing's client property that stops a component rendering text that starts with `<html>`. */
        const val HTML_DISABLE = "html.disable"

        val WARNING = JBColor(0x8A6100, 0xE0A030)

        const val PROJECT_SOURCE = "from this project"
        const val UNRESOLVED = "not resolved"

        const val RESOLVED_HINT = "App actions on this tab act on the app module of the open project, not on " +
            "whatever is in the foreground on the device."
        const val UNRESOLVED_HINT = "No application ID could be resolved. Open an Android project and let its " +
            "Gradle sync finish; until then the app actions on this tab cannot run."
    }
}
