package spock.adb

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.PushDelivery
import spock.adb.command.PushMessage
import spock.adb.command.PushMessageJson
import spock.adb.command.ShellAccess
import spock.adb.device.ConnectedDevice
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * The push message editor: a key/value table for the data payload, an optional title and body,
 * which devices to send to, and saved payloads.
 *
 * Send leaves the dialog open. Testing a push handler is send, look at the app, change one value,
 * send again — closing after every send would put the payload a click further away each time.
 */
class PushMessageDialog(
    private val project: Project,
    private val controller: AdbController,
    private val selectedDevice: () -> ConnectedDevice?,
    private val onSent: (List<PushDelivery>) -> Unit,
) : DialogWrapper(project, false) {

    private val store = PushPayloadStore.getInstance(project)

    private val saved = ComboBox<String>()
    private val saveButton = JButton("Save as…")
    private val deleteButton = JButton("Delete")
    private val pasteJsonButton = JButton("Paste JSON…").apply {
        toolTipText = "Fill the title, body and data from an FCM message or a data payload in JSON"
    }

    private val titleField = JBTextField().apply { emptyText.text = "Optional: makes it a notification message" }
    private val bodyField = JBTextField()

    private val dataModel = object : DefaultTableModel(arrayOf<Any>("Key", "Value"), 0) {}
    private val dataTable = JBTable(dataModel).apply {
        emptyText.text = "No data pairs. Add one with +"
        // Commit an edit in progress when Send is pressed, rather than sending the old value.
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val sendTo = ComboBox(DefaultComboBoxModel(Target.entries.toTypedArray()))

    /** Which devices can receive, read before anything is sent. */
    private val readiness = JBLabel(" ").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    private val result = JBTextArea(RESULT_ROWS, 0).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = "Nothing sent yet."
    }

    private val sendAction = object : DialogWrapperAction("Send") {
        override fun doAction(e: ActionEvent?) = send()
    }

    /** Started and answered on the EDT, so a slow readiness read cannot label a later choice. */
    private val readinessReads = LatestRequest()

    private enum class Target(private val label: String) {
        SELECTED("Selected device"),
        ALL("Every connected device and emulator"),
        ;

        override fun toString() = label
    }

    init {
        title = "Send Push Message"
        // Not modal: the point of sending is to look at the app, and the logcat, afterwards.
        isModal = false
        setCancelButtonText("Close")
        reloadSaved()
        saved.addActionListener { saved.selectedItem?.toString()?.let(::load) }
        saveButton.addActionListener { saveCurrent() }
        pasteJsonButton.addActionListener { pasteJson() }
        deleteButton.addActionListener {
            saved.selectedItem?.toString()?.let { name ->
                store.remove(name)
                reloadSaved()
            }
        }
        sendTo.addActionListener { refreshReadiness() }
        init()
        refreshReadiness()
    }

    override fun createActions(): Array<Action> = arrayOf(sendAction, cancelAction)

    override fun createCenterPanel(): JComponent {
        val savedRow = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            add(saved, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
                    add(pasteJsonButton)
                    add(saveButton)
                    add(deleteButton)
                },
                BorderLayout.EAST,
            )
        }
        val table = ToolbarDecorator.createDecorator(dataTable)
            .setAddAction {
                dataModel.addRow(arrayOf("", ""))
                val row = dataModel.rowCount - 1
                dataTable.editCellAt(row, 0)
                dataTable.changeSelection(row, 0, false, false)
            }
            .setRemoveAction {
                stopEditing()
                dataTable.selectedRows.sortedDescending().forEach(dataModel::removeRow)
            }
            .disableUpDownActions()
            .createPanel()
            .apply { preferredSize = Dimension(JBUI.scale(TABLE_WIDTH), JBUI.scale(TABLE_HEIGHT)) }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Saved:", savedRow)
            .addSeparator()
            .addLabeledComponent("Title:", titleField)
            .addLabeledComponent("Body:", bodyField)
            .addLabeledComponentFillVertically("Data:", table)
            .addSeparator()
            .addLabeledComponent("Send to:", sendTo)
            .addComponentToRightColumn(readiness)
            .addLabeledComponent("Result:", JBScrollPane(result))
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    private fun send() {
        val message = try {
            current().also { it.requireSendable() }
        } catch (e: IllegalArgumentException) {
            result.text = e.message
            return
        }
        withTargets { devices ->
            if (devices.isEmpty()) {
                result.text = "No device is connected."
                return@withTargets
            }
            sendAction.isEnabled = false
            result.text = "Sending to ${devices.joinToString { it.info.displayName }}…"
            controller.sendPushMessage(message, devices) { deliveries ->
                sendAction.isEnabled = true
                result.text = deliveries.joinToString("\n") { it.message }
                onSent(deliveries)
            }
        }
    }

    /**
     * Says, per device, whether its shell can deliver — so a send that is going to be refused is
     * explained before it is tried, not only after.
     */
    private fun refreshReadiness() {
        val request = readinessReads.begin()
        withTargets { devices ->
            if (!readinessReads.isLatest(request)) return@withTargets
            if (devices.isEmpty()) {
                readiness.text = "No device is connected."
                return@withTargets
            }
            readiness.text = "Checking which devices can deliver push messages…"
            controller.pushShellAccess(devices) { access ->
                if (readinessReads.isLatest(request)) readiness.text = describe(access)
            }
        }
    }

    private fun describe(access: Map<ConnectedDevice, ShellAccess>): String {
        val lines = access.map { (device, level) -> "${device.info.displayName}: ${level.label}" }
        // A JLabel only wraps as HTML; one device per line keeps "which one can't" readable.
        return "<html>" + lines.joinToString("<br>") { it.escapeHtml() } + "</html>"
    }

    private fun withTargets(block: (List<ConnectedDevice>) -> Unit) {
        when (sendTo.selectedItem as? Target ?: Target.SELECTED) {
            Target.SELECTED -> block(listOfNotNull(selectedDevice()?.takeIf { it.info.isUsable }))
            Target.ALL -> controller.connectedDevices { devices -> block(devices.filter { it.info.isUsable }) }
        }
    }

    /** What the editor holds now, including a cell still being typed into. */
    private fun current(name: String = ""): PushMessage {
        stopEditing()
        val data = linkedMapOf<String, String>()
        for (row in 0 until dataModel.rowCount) {
            val key = dataModel.getValueAt(row, 0)?.toString().orEmpty().trim()
            val value = dataModel.getValueAt(row, 1)?.toString().orEmpty()
            // A row left entirely empty is an unfinished add, not a pair with a blank key.
            if (key.isEmpty() && value.isEmpty()) continue
            data[key] = value
        }
        return PushMessage(
            data = data,
            title = titleField.text.takeIf { it.isNotBlank() },
            body = bodyField.text.takeIf { it.isNotBlank() },
            name = name,
        )
    }

    private fun load(name: String) {
        store.payloads().firstOrNull { it.name == name }?.let(::show)
    }

    /**
     * Asks for JSON and fills the editor from it. The clipboard is offered first when it holds
     * JSON, since pasting what was just copied from a backend or a console is the point. A parse
     * error is shown under the text as it is typed, so a stray comma is fixed in place rather than
     * after a round trip through an error dialog.
     */
    private fun pasteJson() {
        val clipboard = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
            ?.trim()?.takeIf { it.startsWith("{") }
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                runCatching { PushMessageJson.parse(inputString) }.exceptionOrNull()?.message
        }
        val text = Messages.showMultilineInputDialog(
            project,
            "An FCM message ({\"message\": …} or {\"notification\": …, \"data\": …}), " +
                "or a data payload. It replaces what the editor holds.",
            "Paste Push Message JSON",
            clipboard ?: EXAMPLE_JSON,
            null,
            validator,
        ) ?: return
        show(PushMessageJson.parse(text))
        result.text = "Filled from JSON. Nothing sent yet."
    }

    private fun show(message: PushMessage) {
        stopEditing()
        titleField.text = message.title.orEmpty()
        bodyField.text = message.body.orEmpty()
        dataModel.rowCount = 0
        message.data.forEach { (key, value) -> dataModel.addRow(arrayOf(key, value)) }
    }

    private fun saveCurrent() {
        val suggested = saved.selectedItem?.toString().orEmpty()
        val name = Messages.showInputDialog(
            contentPanel,
            "Save this payload as:",
            "Save Push Message",
            null,
            suggested,
            null,
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        store.save(current(name))
        reloadSaved(select = name)
    }

    private fun reloadSaved(select: String? = null) {
        val names = store.payloads().map { it.name }
        // Replacing the model fires the action listener; loading a payload over the editor
        // because the list was refreshed would throw away what was being edited.
        val listeners = saved.actionListeners
        listeners.forEach(saved::removeActionListener)
        saved.model = DefaultComboBoxModel(names.toTypedArray())
        saved.selectedItem = select
        listeners.forEach(saved::addActionListener)
        deleteButton.isEnabled = names.isNotEmpty()
    }

    private fun stopEditing() {
        if (dataTable.isEditing) dataTable.cellEditor?.stopCellEditing()
    }

    private companion object {
        const val GAP = 4
        const val RESULT_ROWS = 4
        const val TABLE_WIDTH = 460
        const val TABLE_HEIGHT = 160

        const val EXAMPLE_JSON = """{
  "notification": { "title": "Order shipped", "body": "Order 42 is on its way" },
  "data": { "orderId": "42", "deepLink": "myapp://orders/42" }
}"""
    }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
