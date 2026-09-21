package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import spock.adb.command.DontKeepActivitiesState
import spock.adb.command.ShowLayoutBoundsState
import spock.adb.command.ShowTapsState
import spock.adb.device.ConnectedDevice
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
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

    private val windowScale = scaleCombo()
    private val transitionScale = scaleCombo()
    private val durationScale = scaleCombo()

    private val windowLabel = JBLabel("Window animation")
    private val transitionLabel = JBLabel("Transition animation")
    private val durationLabel = JBLabel("Animator duration")

    private val resetScales = JButton("Reset animation scales").apply {
        toolTipText = "Put all three animation scales back to 1×"
        isEnabled = false
    }

    private val log = Logger.getInstance(DeveloperOptionsSection::class.java)

    private var controller: AdbController? = null
    private var selectedDevice: () -> ConnectedDevice? = { null }

    /**
     * Started and answered on the EDT, so a read begun for one device cannot set the controls
     * for another.
     *
     * Six blocking reads per refresh, and selecting a second device starts six more without
     * stopping the first six: whichever set of answers came back last won. Switching from a
     * device with animations off to one with them on could leave the second device's controls
     * showing the first device's values — and the listeners are live by then, so the next click
     * writes what is on screen back to the wrong device.
     */
    private val reads = LatestRequest()

    init {
        layout = GridBagLayout()
        border = JBUI.Borders.empty(gap, 0)

        var row = 0
        add(openOnDeviceButton, wide(row++))
        // A little more air before a new group than between the controls inside one.
        add(dontKeepActivities, wide(row++, topGap = true))
        add(showTaps, wide(row++))
        add(showLayoutBounds, wide(row++))

        addScaleRow(windowLabel, windowScale, row++, topGap = true)
        addScaleRow(transitionLabel, transitionScale, row++)
        addScaleRow(durationLabel, durationScale, row++)
        add(resetScales, wide(row, topGap = true))
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
        resetScales.addActionListener {
            withDevice { device ->
                controller.setWindowAnimatorScale(DEFAULT_SCALE, device)
                controller.setTransitionAnimatorScale(DEFAULT_SCALE, device)
                controller.setAnimatorDurationScale(DEFAULT_SCALE, device)
                refresh()
            }
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
        val request = reads.begin()
        val target = selectedDevice()?.device
        ApplicationManager.getApplication().executeOnPooledThread {
            // Caught, not left to escape. A device whose adbd refuses the shell ("closed") threw
            // out of this task, which the IDE reported as an internal error — and the answer
            // below never ran, so the listeners removed above never came back and every control
            // in the section stopped doing anything until a later read happened to succeed.
            val read = runCatching { DeveloperSettings.read(target) }
            read.exceptionOrNull()?.let { log.warn("Could not read developer options from the device", it) }

            ApplicationManager.getApplication().invokeLater {
                // A refresh this one has replaced must not touch the controls: the listeners
                // are re-added by whichever read is still current, so returning here leaves
                // them off for exactly as long as an answer is still outstanding.
                if (!reads.isLatest(request)) return@invokeLater
                removeListeners()
                // A failed read leaves the controls showing what they last showed rather than
                // resetting them to Off, which would claim a state nobody read.
                read.getOrNull()?.let(::show)
                addListeners()
            }
        }
    }

    private fun show(settings: DeveloperSettings) {
        dontKeepActivities.isSelected = settings.dontKeep == DontKeepActivitiesState.ENABLED
        showTaps.isSelected = settings.taps == ShowTapsState.ENABLED
        showLayoutBounds.isSelected = settings.bounds == ShowLayoutBoundsState.ENABLED
        // A device answers "1" where the list holds "1.0", and "null" where the setting has never
        // been written; both used to select nothing, or worse, select Off.
        windowScale.selectedItem = animationScaleEntry(settings.window, SCALES)
        transitionScale.selectedItem = animationScaleEntry(settings.transition, SCALES)
        durationScale.selectedItem = animationScaleEntry(settings.duration, SCALES)
        markNonDefaults()
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

    /**
     * The three scales, and whether any of them is not what a device leaves the factory with.
     *
     * The label carries it rather than the value: the dropdown already shows the value, and what
     * a developer wants at a glance is whether this device is still set up the way they left it.
     */
    private fun markNonDefaults() {
        var changed = false
        listOf(windowLabel to windowScale, transitionLabel to transitionScale, durationLabel to durationScale)
            .forEach { (label, combo) ->
                val isDefault = combo.selectedItem == DEFAULT_SCALE
                if (!isDefault) changed = true
                label.font = label.font.deriveFont(if (isDefault) Font.PLAIN else Font.BOLD)
                label.toolTipText = if (isDefault) null else "Not the usual 1×"
            }
        resetScales.isEnabled = changed
    }

    private fun scaleCombo(): JComboBox<String> = JComboBox(SCALES.toTypedArray()).apply {
        // "1×" and "Off" rather than "1.0" and "0.0": the list holds what the device is sent.
        renderer = SimpleListCellRenderer.create { label, value, _ -> label.text = scaleText(value) }
        // One width for all three, so the three rows read as one form.
        val width = JBUI.scale(SCALE_WIDTH)
        preferredSize = Dimension(width, preferredSize.height)
        maximumSize = Dimension(width, preferredSize.height)
    }

    /** Label then control, side by side, rather than pushed to opposite edges of the tool window. */
    private fun addScaleRow(label: JBLabel, combo: JComboBox<String>, row: Int, topGap: Boolean = false) {
        add(label, labelAt(row, topGap))
        add(combo, controlAt(row, topGap))
    }

    private fun labelAt(row: Int, topGap: Boolean) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(if (topGap) gap else 2, 0, 2, gap)
    }

    private fun controlAt(row: Int, topGap: Boolean) = GridBagConstraints().apply {
        gridx = 1
        gridy = row
        // The filler column takes the slack, so the control stays beside its label.
        weightx = 1.0
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(if (topGap) gap else 2, 0)
    }

    private fun wide(row: Int, topGap: Boolean = false) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        gridwidth = 2
        weightx = 1.0
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(if (topGap) gap else 2, 0)
    }

    private companion object {
        const val SCALE_WIDTH = 96
    }
}

/** What the device is sent for each entry of the animation-scale dropdowns. */
internal val SCALES = listOf("0.0", "0.5", "1.0", "1.5", "2.0", "5.0", "10.0")

/** A device that has never had these written leaves them at this. */
internal const val DEFAULT_SCALE = "1.0"

/** `0.0` is off, and the rest are multipliers — which is how the system settings screen says it. */
internal fun scaleText(value: String?): String = when {
    value == null -> ""
    value == "0.0" -> "Off"
    else -> value.trimEnd('0').trimEnd('.') + "\u00d7"
}

/**
 * The dropdown entry a device's answer names, or null when it named none.
 *
 * `settings get` answers with whatever is stored — "1", "1.0", or the literal "null" where the
 * setting has never been written — so the raw string rarely equals the entry it means. Matching
 * on the number is what makes the dropdown show what the device is actually set to.
 */
internal fun animationScaleEntry(raw: String?, choices: List<String>): String? {
    val value = raw?.trim()?.toFloatOrNull() ?: return null
    return choices.firstOrNull { it.toFloat() == value }
}

/** What [DeveloperOptionsSection] shows, read in one go. Every field is null without a device. */
internal data class DeveloperSettings(
    val dontKeep: DontKeepActivitiesState?,
    val taps: ShowTapsState?,
    val bounds: ShowLayoutBoundsState?,
    val window: String?,
    val transition: String?,
    val duration: String?,
) {
    companion object {
        /** Six blocking shell reads. Throws whatever the device's shell throws. */
        fun read(device: IDevice?): DeveloperSettings = DeveloperSettings(
            dontKeep = device?.areDontKeepActivitiesEnabled(),
            taps = device?.areShowTapsEnabled(),
            bounds = device?.areShowLayoutBoundsEnabled(),
            window = device?.getWindowAnimatorScale(),
            transition = device?.getTransitionAnimationScale(),
            duration = device?.getAnimatorDurationScale(),
        )
    }
}
