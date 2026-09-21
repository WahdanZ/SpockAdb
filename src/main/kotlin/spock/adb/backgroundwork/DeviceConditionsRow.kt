package spock.adb.backgroundwork

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.LatestRequest
import spock.adb.command.DeviceCondition
import spock.adb.command.DeviceConditionShell
import spock.adb.command.DeviceConditionTracker
import spock.adb.command.DeviceConditions
import spock.adb.command.GetDeviceConditionsCommand
import spock.adb.command.StandbyBucket
import spock.adb.command.forceDoze
import spock.adb.command.leaveDoze
import spock.adb.command.resetDeviceConditions
import spock.adb.command.setStandbyBucket
import spock.adb.command.unplugBattery
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The Background Work tab's device conditions: force Doze, move the app between standby buckets,
 * unplug the battery, and Reset — with a banner whenever Spock has left the device changed.
 *
 * The banner is the point of the design. These are device-wide changes that survive the session:
 * a device left in forced Doze, or an app left in the rare bucket, misbehaves for whoever picks it
 * up next and nothing on the device says why. So what Spock changed is tracked, whether from here
 * or by an agent, shown until it is reset, and reset automatically when the last project closes.
 *
 * Its own component rather than more fields on [BackgroundWorkPanel].
 */
internal class DeviceConditionsRow(
    private val project: Project,
    private val isDisposed: () -> Boolean,
    private val onStatus: (String) -> Unit,
) : JPanel() {

    private val stateLabel = JBLabel(" ").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    private val dozeButton = JButton(FORCE_DOZE).apply { toolTipText = DOZE_TOOLTIP }
    private val batteryButton = JButton("Unplug battery").apply {
        toolTipText = "Report the battery as unplugged (dumpsys battery unplug): jobs that need charging wait."
    }
    private val bucketCombo = ComboBox(StandbyBucket.SETTABLE.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        toolTipText = "Put the app in an App Standby bucket. The bucket is read back: Android may keep it higher."
    }
    private val bucketButton = JButton("Set bucket")
    private val resetButton = JButton("Reset").apply {
        toolTipText = "Undo forced Doze, the battery override, and the buckets Spock changed."
    }

    private val bannerLabel = JBLabel().apply { foreground = BANNER_COLOR }
    private val bannerReset = JButton("Reset now")
    private val banner = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
        border = JBUI.Borders.empty(2, GAP)
        add(bannerLabel, BorderLayout.CENTER)
        add(bannerReset, BorderLayout.EAST)
        isVisible = false
    }

    private var device: ConnectedDevice? = null
    private var packageName: String? = null
    private var observed: DeviceConditions? = null
    private var busy = false
    private val reads = LatestRequest()

    private val trackerListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater({ updateBanner() }) { isDisposed() || project.isDisposed }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(banner)
        add(
            JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
                border = JBUI.Borders.empty(0, GAP / 2)
                add(JBLabel("Device conditions:"))
                add(dozeButton)
                add(batteryButton)
                add(bucketCombo)
                add(bucketButton)
                add(resetButton)
            },
        )
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, GAP, 2, GAP)
                add(stateLabel, BorderLayout.CENTER)
            },
        )

        dozeButton.addActionListener {
            if (observed?.dozing == true) change("Leaving Doze…") { it.leaveDoze() } else confirmAndForceDoze()
        }
        batteryButton.addActionListener { change("Unplugging the battery…") { it.unplugBattery() } }
        bucketButton.addActionListener {
            val app = packageName ?: return@addActionListener
            val bucket = bucketCombo.item ?: return@addActionListener
            change("Setting $app to ${bucket.label}…") { it.setStandbyBucket(app, bucket) }
        }
        resetButton.addActionListener { reset() }
        bannerReset.addActionListener { reset() }

        DeviceConditionTracker.addListener(trackerListener)
        updateControls()
    }

    fun setDevice(connected: ConnectedDevice?) {
        device = connected
        observed = null
        updateBanner()
        updateControls()
    }

    fun setApp(app: String?) {
        packageName = app
        updateControls()
    }

    /** Reads the device's conditions; called whenever the tab reads jobs and alarms. */
    fun refresh() {
        val target = device ?: return
        val request = reads.begin()
        val app = packageName
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { GetDeviceConditionsCommand().execute(app, project, target.device) }
            result.getOrNull()?.let { DeviceConditionTracker.reconcile(target.serialNumber, it) }
            ApplicationManager.getApplication().invokeLater({
                if (!reads.isLatest(request)) return@invokeLater
                observed = result.getOrNull()
                // Shows the app's real bucket once per read, not on every control update, which
                // would snap the choice back while the developer is making it.
                observed?.bucket?.takeIf { it in StandbyBucket.SETTABLE }?.let { bucketCombo.item = it }
                stateLabel.text = result.fold(
                    { describe(it, app) },
                    { "Could not read device conditions: ${it.message}" },
                )
                updateBanner()
                updateControls()
            }) { isDisposed() || project.isDisposed }
        }
    }

    fun dispose() {
        DeviceConditionTracker.removeListener(trackerListener)
    }

    // ---------------------------------------------------------------- actions

    private fun confirmAndForceDoze() {
        val answer = Messages.showOkCancelDialog(
            project,
            "Force the whole device into deep Doze? Every app's jobs, alarms and network access are deferred " +
                "until you press Reset. Spock resets it when the last project closes.",
            "Force Doze",
            FORCE_DOZE,
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        )
        if (answer == Messages.OK) change("Forcing Doze…") { it.forceDoze() }
    }

    private fun reset() = change("Resetting device conditions…") { it.resetDeviceConditions(packageName) }

    private fun change(working: String, action: (IDevice) -> String) {
        val target = device ?: return
        busy = true
        updateControls()
        onStatus(working)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { action(target.device) }
            ApplicationManager.getApplication().invokeLater({
                busy = false
                onStatus(result.fold({ it }, { it.message ?: "The change failed." }))
                refresh()
            }) { isDisposed() || project.isDisposed }
        }
    }

    // ---------------------------------------------------------------- state

    private fun updateBanner() {
        val serial = device?.serialNumber
        val changed = serial?.let(DeviceConditionTracker::conditions).orEmpty()
        banner.isVisible = changed.isNotEmpty()
        bannerLabel.text = bannerText(changed)
        revalidate()
        repaint()
    }

    private fun updateControls() {
        val api = device?.info?.apiLevel
        val ready = device != null && !busy
        val dozing = observed?.dozing == true

        dozeButton.text = if (dozing) LEAVE_DOZE else FORCE_DOZE
        val dozeReason = DeviceConditionShell.dozeUnavailableReason(api)
        dozeButton.isEnabled = ready && dozeReason == null
        dozeButton.toolTipText = dozeReason ?: DOZE_TOOLTIP
        batteryButton.isEnabled = ready && dozeReason == null && observed?.batteryOverridden != true

        val bucketReason = bucketCombo.item?.let { DeviceConditionShell.bucketUnavailableReason(api, it) }
        bucketCombo.isEnabled = ready && packageName != null
        bucketButton.isEnabled = ready && packageName != null && bucketReason == null
        bucketButton.toolTipText = bucketReason ?: packageName?.let { "Put $it in the chosen bucket" }
            ?: "Choose an app in the header first"

        resetButton.isEnabled = ready
        bannerReset.isEnabled = ready
    }

    companion object {
        private const val GAP = 6
        private const val FORCE_DOZE = "Force Doze"
        private const val LEAVE_DOZE = "Leave Doze"
        private const val DOZE_TOOLTIP =
            "Put the whole device into deep Doze now (dumpsys deviceidle force-idle), with the battery " +
                "reported unplugged. Affects every app until Reset."
        private val BANNER_COLOR = JBColor(0xA15C00, 0xE0A85A)

        /** The one-line summary under the buttons. */
        fun describe(conditions: DeviceConditions, packageName: String?): String = listOfNotNull(
            "Doze: ${if (conditions.dozing) "deep idle" else conditions.deepIdle?.lowercase() ?: "unknown"}",
            packageName?.let { app -> "bucket: ${conditions.bucket?.label ?: "unknown"}".takeIf { app.isNotEmpty() } },
            "battery: " + (conditions.batteryLevel?.let { "$it%" } ?: "?") +
                (if (conditions.powered == true) ", charging" else ", not charging") +
                (if (conditions.batteryOverridden) " (overridden)" else ""),
        ).joinToString("  ·  ")

        fun bannerText(changed: Set<DeviceCondition>): String =
            if (changed.isEmpty()) {
                ""
            } else {
                "<html><b>Spock has changed this device:</b> ${changed.joinToString { it.describe() }}. " +
                    "Background work behaves differently until you reset it.</html>"
            }
    }
}
