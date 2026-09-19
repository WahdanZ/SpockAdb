package spock.adb

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.AppInfo
import spock.adb.device.ConnectedDevice
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Which app, exactly: its package, version, UID, and whether it is running.
 *
 * The header names a package and nothing else, so a debug build and a release one — or two
 * flavours whose names differ by a suffix — look identical in it. The version and the UID are
 * what tell them apart, and "not running" is what explains why Force stop appeared to do
 * nothing.
 */
internal class AppInfoCard : JPanel(GridBagLayout()) {

    private val packageValue = value()
    private val versionValue = value()
    private val processValue = value()
    private val uidValue = value()

    private val copyPackage = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the package name"
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = JBUI.emptyInsets()
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(packageValue.text))
        }
    }

    private var controller: AdbController? = null
    private var selectedDevice: () -> ConnectedDevice? = { null }

    /** Started and answered on the EDT, so a slow read cannot label a later app. */
    private val reads = LatestRequest()

    init {
        border = JBUI.Borders.empty(GAP, 0, 0, 0)
        var row = 0
        add(label("Package name"), labelAt(row))
        add(packageValue, valueAt(row))
        add(copyPackage, trailingAt(row++))
        add(label("Version"), labelAt(row))
        add(versionValue, valueAt(row++))
        add(label("Process"), labelAt(row))
        add(processValue, valueAt(row++))
        add(label("UID"), labelAt(row))
        add(uidValue, valueAt(row))
        show(null)
    }

    fun attach(controller: AdbController, device: () -> ConnectedDevice?) {
        this.controller = controller
        this.selectedDevice = device
    }

    /** Reads the selected app on the selected device. Reads nothing before [attach] or while hidden. */
    fun refresh() {
        val controller = controller
        val request = reads.begin()
        val target = selectedDevice()
        if (controller == null || target == null || !isVisible) return show(null)

        packageValue.text = READING
        controller.appInfo(target.device) { result ->
            if (reads.isLatest(request)) show(result.getOrNull())
        }
    }

    private fun show(info: AppInfo?) {
        packageValue.text = info?.packageName ?: UNKNOWN
        versionValue.text = info?.version() ?: UNKNOWN
        uidValue.text = info?.uid ?: UNKNOWN
        // "Stopped" rather than an empty line: an app that is not running is the answer to a
        // question, not a field the device failed to fill in.
        processValue.text = when {
            info == null -> UNKNOWN
            info.isRunning -> "${info.packageName}  (pid ${info.pid})"
            else -> "Not running"
        }
        processValue.foreground = if (info?.isRunning == false) STOPPED else UIUtil.getLabelForeground()
        copyPackage.isEnabled = info != null
    }

    private fun label(text: String) = JBLabel(text).apply {
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    /** Device-supplied text: a label whose text starts with a tag would be rendered as markup. */
    private fun value() = JBLabel(UNKNOWN).apply {
        putClientProperty(HTML_DISABLE, true)
        minimumSize = java.awt.Dimension(0, preferredSize.height)
    }

    private fun labelAt(row: Int) = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0, 2, GAP * 2)
    }

    private fun valueAt(row: Int) = GridBagConstraints().apply {
        gridx = 1
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, 0)
    }

    private fun trailingAt(row: Int) = GridBagConstraints().apply {
        gridx = 2
        gridy = row
        anchor = GridBagConstraints.LINE_START
        insets = JBUI.insets(2, GAP, 2, 0)
    }

    private companion object {
        const val GAP = 4
        const val UNKNOWN = "—"
        const val READING = "…"
        const val HTML_DISABLE = "html.disable"

        val STOPPED = JBColor(0x8A6100, 0xE0A030)
    }
}
