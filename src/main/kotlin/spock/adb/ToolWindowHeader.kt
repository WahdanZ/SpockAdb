package spock.adb

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import spock.adb.device.ConnectedDevice
import spock.adb.ui.AppPackagePicker
import spock.adb.ui.WrapLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * What every tab of the tool window is about: the device, and the app.
 *
 * One header above the whole tool window rather than one per tab. The device was chosen on the
 * Devices tab and every other tab followed it silently, so Logcat and Commands showed no sign of
 * what they were attached to; the app was not on screen at all, because each action resolved the
 * open project's app module for itself.
 *
 * Both are pickers, and both are the single answer: what is chosen here is what every tab and
 * every action uses.
 */
internal class ToolWindowHeader(
    project: Project,
    isAlive: () -> Boolean,
) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))) {

    val deviceCombo = ComboBox<String>()

    val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Read the device list again"
    }

    val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Choose which actions are shown"
    }

    private val appPicker = AppPackagePicker(project, isAlive)

    /**
     * Which device AI agents are driving, when that is not the one selected here.
     *
     * These two selections are genuinely independent: an agent chooses with
     * `android_select_device` and this dropdown does not follow it, so a developer can be
     * watching one phone while an agent clears app data on another. Nothing surfaced that
     * before, which made it a trap rather than a choice.
     */
    private val agentTargetLabel = JBLabel().apply { isVisible = false }

    /** Called on the EDT with the app that was picked, typed, or picked for the developer. */
    var onAppChosen: (String) -> Unit = {}

    /** The package shown now, including one typed but not yet confirmed. */
    val selectedApp: String? get() = appPicker.selected

    init {
        border = JBUI.Borders.empty(GAP)
        // Neither may widen the tool window: both elide, and the row wraps when it must.
        deviceCombo.minimumSize = Dimension(0, deviceCombo.preferredSize.height)
        deviceCombo.prototypeDisplayValue = ""
        deviceCombo.preferredSize = Dimension(JBUI.scale(DEVICE_WIDTH), deviceCombo.preferredSize.height)
        appPicker.onChosen = { packageName -> onAppChosen(packageName) }

        add(deviceCombo)
        add(appPicker)
        add(refreshButton)
        add(settingsButton)
        add(agentTargetLabel)
    }

    /**
     * Shows [selected] as the device, and loads the apps installed on it.
     *
     * @param sameDevice keeps the chosen app when the device did not actually change, so a
     *   reconnect or a device-list refresh does not throw away what is being worked on.
     */
    fun setDevice(selected: ConnectedDevice?, sameDevice: Boolean = false, hint: String? = null) {
        // Plain text, never HTML: the model name comes off the device, and a label whose text
        // starts with a tag is rendered as markup.
        deviceCombo.toolTipText = hint ?: selected?.info?.let { "${it.describe()} · ${it.details()}" }
        appPicker.load(selected, keepSelection = sameDevice)
    }

    fun setAppEnabled(enabled: Boolean) {
        appPicker.isEnabled = enabled
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

    private companion object {
        const val GAP = 4
        const val DEVICE_WIDTH = 240

        val WARNING = JBColor(0x8A6100, 0xE0A030)
    }
}
