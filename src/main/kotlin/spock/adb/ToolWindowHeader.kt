package spock.adb

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import spock.adb.context.SpockSelection
import spock.adb.device.ConnectedDevice
import spock.adb.ui.AppPackagePicker
import spock.adb.ui.WrapLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
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
 * Both are views of [SpockSelection]: what is chosen here is chosen for every tab, every tool
 * window and every action, and a choice made anywhere else shows here.
 */
internal class ToolWindowHeader(
    project: Project,
    parent: Disposable,
) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))) {

    val deviceCombo = ComboBox<String>()

    /**
     * The one refresh in the header, and it re-reads both lists.
     *
     * The app picker used to carry a second one. Two identical icons sat side by side with only
     * a tooltip between them, and they were never really two ideas: refreshing devices already
     * reloads the apps, because the reply runs through `setDevice`, which loads the picker.
     */
    val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Read the device and app lists again"
    }

    val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Choose which actions are shown"
    }

    private val appPicker = AppPackagePicker()

    private val selection = SpockSelection.getInstance(project)

    /** The devices in [deviceCombo], in its order. */
    private var listed: List<ConnectedDevice> = emptyList()

    /** True while the combo is filled by code, when a change of selection is not the developer's pick. */
    private var populating = false

    init {
        border = JBUI.Borders.empty(GAP)
        // Neither may widen the tool window: both elide, and the row wraps when it must.
        deviceCombo.minimumSize = Dimension(0, deviceCombo.preferredSize.height)
        deviceCombo.prototypeDisplayValue = ""
        deviceCombo.preferredSize = Dimension(JBUI.scale(DEVICE_WIDTH), deviceCombo.preferredSize.height)
        appPicker.onChosen = { packageName -> selection.selectApp(packageName) }
        refreshButton.addActionListener { selection.refresh() }
        deviceCombo.addItemListener { event ->
            // A combo box fires DESELECTED then SELECTED, and reports index -1 when the model
            // is emptied, so only a SELECTED event with a real index is a choice.
            if (event.stateChange == ItemEvent.SELECTED && !populating) {
                listed.getOrNull(deviceCombo.selectedIndex)?.let { selection.selectDevice(it.serialNumber) }
            }
        }

        add(deviceCombo)
        add(refreshButton)
        add(appPicker)
        add(settingsButton)

        selection.addListener(parent) { snapshot, changes ->
            if (SpockSelection.Change.DEVICES in changes || SpockSelection.Change.DEVICE in changes) {
                showDevices(snapshot)
            }
            appPicker.show(snapshot.apps, snapshot.projectApp, snapshot.app)
        }
    }

    private fun showDevices(snapshot: SpockSelection.Snapshot) {
        populating = true
        try {
            listed = snapshot.devices
            if (listed.isEmpty()) {
                // An empty dropdown with no explanation is indistinguishable from a broken
                // plugin. Say so, and say what to do about it.
                deviceCombo.model = DefaultComboBoxModel(arrayOf(NO_DEVICES))
                deviceCombo.isEnabled = false
                deviceCombo.toolTipText = NO_DEVICES_HINT
            } else {
                deviceCombo.isEnabled = true
                // The name and the Android version only: the serial and the architecture are in
                // the tooltip, where they do not push the name out of a docked tool window.
                deviceCombo.model = DefaultComboBoxModel(listed.map { it.info.shortLabel() }.toTypedArray())
                deviceCombo.selectedIndex = listed.indexOfFirst { it.serialNumber == snapshot.device?.serialNumber }
                // Plain text, never HTML: the model name comes off the device, and a label whose
                // text starts with a tag is rendered as markup.
                deviceCombo.toolTipText = snapshot.device?.info?.let { "${it.describe()} · ${it.details()}" }
            }
        } finally {
            populating = false
        }
    }

    private companion object {
        const val GAP = 4
        const val DEVICE_WIDTH = 240
        const val NO_DEVICES = "No devices connected"
        const val NO_DEVICES_HINT = "Connect a device or start an emulator, then press Refresh. " +
            "If a device is attached, check idea.log for ADB errors."
    }
}
