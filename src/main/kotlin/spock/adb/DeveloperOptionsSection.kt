package spock.adb

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import spock.adb.command.AnimatorDurationScaleCommand
import spock.adb.command.DontKeepActivitiesState
import spock.adb.command.ShowLayoutBoundsState
import spock.adb.command.ShowTapsState
import spock.adb.command.TransitionAnimatorScaleCommand
import spock.adb.command.WindowAnimatorScaleCommand
import spock.adb.device.ConnectedDevice
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Developer Options the plugin can set: the three switches and the three animation scales.
 *
 * Its own component, as [HttpProxyRow] is, because the tab it sat on is the class Detekt calls
 * too large — and because the listener dance these controls need is entirely their own: the
 * values are read from the device and written back into Swing, so every listener has to come
 * off before they are set and go back on afterwards, or the act of displaying what the device
 * holds writes it straight back to the device.
 */
class DeveloperOptionsSection(private val gap: Int) : JPanel() {

    private val openOnDeviceButton = JButton("Open developer options").apply {
        toolTipText = "Open the system Developer Options screen on the device"
    }
    private val dontKeepActivities = JCheckBox("Don't keep activities")
    private val showTaps = JCheckBox("Show taps")
    private val showLayoutBounds = JCheckBox("Show layout bounds")
    private val windowScale = JComboBox(ANIMATION_SCALES)
    private val transitionScale = JComboBox(ANIMATION_SCALES)
    private val durationScale = JComboBox(ANIMATION_SCALES)

    private var controller: AdbController? = null
    private var selectedDevice: () -> ConnectedDevice? = { null }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(gap, 0)
        add(leftAligned(openOnDeviceButton))
        add(leftAligned(dontKeepActivities))
        add(leftAligned(showTaps))
        add(leftAligned(showLayoutBounds))
        add(scaleRow("Window animation", windowScale))
        add(scaleRow("Transition animation", transitionScale))
        add(scaleRow("Animator duration", durationScale))
    }

    /**
     * @param device supplies the currently selected device, or null when none is selected.
     */
    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        this.controller = controller
        this.selectedDevice = device
        openOnDeviceButton.addActionListener {
            withDevice { target -> controller.openDeveloperOptions(target) }
        }
        addListeners()
    }

    /**
     * Reads the device's settings into the controls.
     *
     * Safe to call on the EDT: the reads are blocking ADB calls, so they run on a pooled thread
     * and the controls are set back on the EDT — with the listeners off, because setting a
     * checkbox fires them and a stale callback would write the value that was just read.
     */
    fun refresh() {
        removeListeners()
        val target = selectedDevice()?.device
        ApplicationManager.getApplication().executeOnPooledThread {
            val dontKeep = target?.areDontKeepActivitiesEnabled()
            val taps = target?.areShowTapsEnabled()
            val bounds = target?.areShowLayoutBoundsEnabled()
            val window = target?.getWindowAnimatorScale()
            val transition = target?.getTransitionAnimationScale()
            val duration = target?.getAnimatorDurationScale()

            ApplicationManager.getApplication().invokeLater {
                removeListeners()
                dontKeepActivities.isSelected = dontKeep == DontKeepActivitiesState.ENABLED
                showTaps.isSelected = taps == ShowTapsState.ENABLED
                showLayoutBounds.isSelected = bounds == ShowLayoutBoundsState.ENABLED
                windowScale.selectedItem = WindowAnimatorScaleCommand.getWindowAnimatorScaleIndex(window)
                transitionScale.selectedItem =
                    TransitionAnimatorScaleCommand.getTransitionAnimatorScaleIndex(transition)
                durationScale.selectedItem = AnimatorDurationScaleCommand.getAnimatorDurationScaleIndex(duration)
                addListeners()
            }
        }
    }

    private fun addListeners() {
        dontKeepActivities.addActionListener(::onDontKeepActivities)
        showTaps.addActionListener(::onShowTaps)
        showLayoutBounds.addActionListener(::onShowLayoutBounds)
        windowScale.addActionListener(::onWindowScale)
        transitionScale.addActionListener(::onTransitionScale)
        durationScale.addActionListener(::onDurationScale)
    }

    private fun removeListeners() {
        listOf(dontKeepActivities, showTaps, showLayoutBounds)
            .forEach { box -> box.actionListeners.forEach(box::removeActionListener) }
        listOf(windowScale, transitionScale, durationScale)
            .forEach { combo -> combo.actionListeners.forEach(combo::removeActionListener) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDontKeepActivities(event: ActionEvent) =
        withDevice { device -> controller?.enableDisableDontKeepActivities(device) }

    @Suppress("UNUSED_PARAMETER")
    private fun onShowTaps(event: ActionEvent) =
        withDevice { device -> controller?.enableDisableShowTaps(device) }

    @Suppress("UNUSED_PARAMETER")
    private fun onShowLayoutBounds(event: ActionEvent) = withDevice { device ->
        controller?.enableDisableShowLayoutBounds(device)
        device.refreshUi()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onWindowScale(event: ActionEvent) = withDevice { device ->
        controller?.setWindowAnimatorScale(windowScale.selectedItem as String, device)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onTransitionScale(event: ActionEvent) = withDevice { device ->
        controller?.setTransitionAnimatorScale(transitionScale.selectedItem as String, device)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDurationScale(event: ActionEvent) = withDevice { device ->
        controller?.setAnimatorDurationScale(durationScale.selectedItem as String, device)
    }

    private inline fun withDevice(block: (com.android.ddmlib.IDevice) -> Unit) {
        selectedDevice()?.device?.let(block)
    }

    /** Label left, control right, so a setting and its value are read as one line. */
    private fun scaleRow(label: String, combo: JComboBox<String>): JPanel =
        JPanel(BorderLayout(JBUI.scale(gap), 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, combo.preferredSize.height + JBUI.scale(gap))
            border = JBUI.Borders.emptyTop(2)
            add(JBLabel(label), BorderLayout.WEST)
            add(combo, BorderLayout.EAST)
        }

    private fun leftAligned(component: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(2))).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height + JBUI.scale(gap))
            add(component)
        }

    private companion object {
        val ANIMATION_SCALES = arrayOf("0.0", "0.5", "1.0", "1.5", "2.0", "5.0", "10.0")
    }
}
