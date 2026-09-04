package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Inspect the UI currently on screen, from the IDE.
 *
 * The semantics tree, framework detection and accessibility audit were previously reachable
 * only through MCP — useful to an AI agent and invisible to the developer sitting in front
 * of the IDE. This is the same machinery, made visible.
 *
 * It reads the accessibility tree, which is where Jetpack Compose publishes its semantics,
 * so it works identically for Views, Compose and hybrid screens.
 */
class UiInspectorPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val frameworkLabel = JBLabel()
    private val hintLabel = JBLabel()
    private val statusLabel = JBLabel(" ")

    private val treeRoot = DefaultMutableTreeNode("No UI captured")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    private val detailArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
    }

    private val searchField = JBTextField(SEARCH_COLUMNS)
    private val interactiveOnly = JCheckBox("Interactive only")

    private var device: ConnectedDevice? = null
    private var capturedTree: UiTree? = null

    init {
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.isRootVisible = true
        tree.addTreeSelectionListener { showDetails() }

        setToolbar(header())
        setContent(body())
        wire()
        updateStatus()
    }

    fun setDevice(connected: ConnectedDevice?) {
        device = connected
        updateStatus()
    }

    // ---------------------------------------------------------------- layout

    private fun header(): JComponent {
        val actions = DefaultActionGroup().apply {
            add(
                action("Capture UI", "Read the semantics tree from the device", AllIcons.Actions.Refresh) {
                    capture()
                },
            )
            add(
                action(
                    "Accessibility Audit",
                    "Check this screen for accessibility problems",
                    AllIcons.General.InspectionsEye,
                ) { runAudit() },
            )
            add(
                action("Copy Tree", "Copy the whole tree as text", AllIcons.Actions.Copy) { copyTree() },
            )
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this

        val filters = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(0, GAP, 2, GAP)
            add(JBLabel("Find:"))
            add(searchField.apply { toolTipText = "Match test tag, text or content description" })
            add(interactiveOnly)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, GAP)
                    add(frameworkLabel, BorderLayout.NORTH)
                    add(hintLabel, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
            add(filters, BorderLayout.SOUTH)
        }
    }

    private fun body(): JComponent {
        val treePane = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(2, GAP)
                    add(statusLabel, BorderLayout.WEST)
                },
                BorderLayout.SOUTH,
            )
        }
        return OnePixelSplitter(true, SPLIT_PROPORTION).apply {
            firstComponent = treePane
            secondComponent = JBScrollPane(detailArea)
        }
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, description, icon), Toggleable {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun wire() {
        searchField.addKeyListener(
            object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) = rebuildTree()
            },
        )
        interactiveOnly.addActionListener { rebuildTree() }
    }

    // ---------------------------------------------------------------- capture

    private fun capture() {
        val target = device ?: run {
            statusLabel.text = "No device selected. Choose one in the Devices tab."
            return
        }
        statusLabel.text = "Capturing…"

        // The dump is a blocking ADB round trip plus a file read; never on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { readTree(target) }

            ApplicationManager.getApplication().invokeLater({
                result
                    .onSuccess {
                        capturedTree = it
                        rebuildTree()
                        showFramework(it)
                    }
                    .onFailure {
                        statusLabel.text = "Capture failed: ${it.message}"
                        frameworkLabel.text = " "
                        hintLabel.text = " "
                    }
            }) { project.isDisposed }
        }
    }

    private fun readTree(target: ConnectedDevice): UiTree {
        val dumpPath = "/sdcard/spock-adb-inspector.xml"
        val receiver = ShellOutputReceiver()
        target.device.executeShellCommand(
            "uiautomator dump $dumpPath",
            receiver,
            DUMP_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        check(!receiver.toString().contains("ERROR", ignoreCase = true)) {
            "uiautomator could not dump the UI. This happens when the screen is off, a secure " +
                "window is showing, or the UI is still animating."
        }

        val xmlReceiver = ShellOutputReceiver()
        target.device.executeShellCommand(
            "cat ${ShellQuote.quote(dumpPath)}",
            xmlReceiver,
            DUMP_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        runCatching {
            target.device.executeShellCommand(
                "rm -f ${ShellQuote.quote(dumpPath)}",
                ShellOutputReceiver(),
                DUMP_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        }

        val xml = xmlReceiver.toString()
        check(xml.isNotBlank()) { "uiautomator produced an empty dump." }
        return UiTreeParser.parse(xml)
    }

    /**
     * States the framework outright.
     *
     * On a Compose screen "Activity → View hierarchy" is the wrong mental model, and a
     * developer reading a flat list of `android.view.View` nodes has no other way to tell
     * that they are looking at Compose semantics.
     */
    private fun showFramework(uiTree: UiTree) {
        frameworkLabel.text = uiTree.framework.description
        frameworkLabel.foreground = when (uiTree.framework) {
            UiFramework.COMPOSE -> COMPOSE_COLOR
            UiFramework.HYBRID -> HYBRID_COLOR
            else -> JBColor.foreground()
        }

        hintLabel.foreground = JBColor.GRAY
        hintLabel.text = when (uiTree.testTagSupport) {
            UiTree.TestTagSupport.AVAILABLE ->
                "Compose test tags are visible — match on testTag."
            UiTree.TestTagSupport.UNAVAILABLE ->
                "<html>Compose test tags are <b>not</b> visible. Add " +
                    "<code>Modifier.semantics { testTagsAsResourceId = true }</code> to expose " +
                    "them; until then, match on text or content description.</html>"
            UiTree.TestTagSupport.NOT_APPLICABLE -> " "
        }
    }

    // ---------------------------------------------------------------- tree

    private fun rebuildTree() {
        val uiTree = capturedTree ?: return
        val root = uiTree.root ?: return

        val query = searchField.text.orEmpty()
        val selector = query.takeIf { it.isNotBlank() }?.let {
            UiSelector(text = it, interactiveOnly = interactiveOnly.isSelected)
        }

        // When filtering, show a flat list of matches: a filtered hierarchy with the
        // intermediate containers kept is mostly containers and hides what was found.
        val newRoot = if (selector != null || interactiveOnly.isSelected) {
            flatMatches(uiTree, query)
        } else {
            buildBranch(root)
        }

        treeModel.setRoot(newRoot)
        treeModel.reload()
        expandAll()
        updateCount(uiTree)
    }

    private fun flatMatches(uiTree: UiTree, query: String): DefaultMutableTreeNode {
        val matches = uiTree.nodes()
            .filter { it.bounds.isVisible }
            .filter { !interactiveOnly.isSelected || it.isInteractive }
            .filter { node ->
                query.isBlank() ||
                    node.text.contains(query, ignoreCase = true) ||
                    node.contentDescription.contains(query, ignoreCase = true) ||
                    node.testTag.orEmpty().contains(query, ignoreCase = true)
            }
            .toList()

        return DefaultMutableTreeNode("${matches.size} matching element(s)").apply {
            matches.forEach { add(DefaultMutableTreeNode(it)) }
        }
    }

    private fun buildBranch(node: UiNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).apply {
            node.children.forEach { add(buildBranch(it)) }
        }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount && row < MAX_AUTO_EXPAND_ROWS) {
            tree.expandRow(row)
            row++
        }
    }

    private fun updateCount(uiTree: UiTree) {
        val total = uiTree.nodes().count()
        val interactive = uiTree.nodes().count { it.isInteractive && it.bounds.isVisible }
        statusLabel.text = "$total nodes · $interactive interactive · ${uiTree.framework.description}"
    }

    // ---------------------------------------------------------------- details

    private fun selectedNode(): UiNode? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? UiNode)

    private fun showDetails() {
        val node = selectedNode() ?: run {
            detailArea.text = ""
            return
        }
        detailArea.text = buildString {
            appendLine("class:               ${node.className}")
            appendLine("testTag:             ${node.testTag ?: "—"}")
            appendLine("text:                ${node.text.ifBlank { "—" }}")
            appendLine("contentDescription:  ${node.contentDescription.ifBlank { "—" }}")
            appendLine("resource-id:         ${node.resourceId.ifBlank { "—" }}")
            appendLine("bounds:              ${node.bounds}  (${node.bounds.width}x${node.bounds.height})")
            appendLine("centre:              ${node.bounds.centerX}, ${node.bounds.centerY}")
            appendLine()
            appendLine("enabled:             ${node.enabled}")
            appendLine("clickable:           ${node.clickable}")
            appendLine("long-clickable:      ${node.longClickable}")
            appendLine("scrollable:          ${node.scrollable}")
            appendLine("focusable / focused: ${node.focusable} / ${node.focused}")
            appendLine("checkable / checked: ${node.checkable} / ${node.checked}")
            appendLine("selected:            ${node.selected}")
            appendLine("children:            ${node.children.size}")
            if (!node.isInteractive) {
                appendLine()
                appendLine(
                    "Not interactive. In Compose the click handler usually sits on an ancestor, " +
                        "so the tappable element may be this node's parent.",
                )
            }
        }
        detailArea.caretPosition = 0
    }

    private fun runAudit() {
        val uiTree = capturedTree ?: run {
            statusLabel.text = "Capture the UI first."
            return
        }
        val findings = AccessibilityAudit.audit(uiTree)

        detailArea.text = if (findings.isEmpty()) {
            "No accessibility problems found on this screen."
        } else {
            val rendered = findings.joinToString("\n\n") { it.describe(uiTree.framework) }
            "${findings.size} finding(s):\n\n$rendered"
        }
        detailArea.caretPosition = 0
        statusLabel.text = "${findings.size} accessibility finding(s)."
    }

    private fun copyTree() {
        val root = capturedTree?.root ?: run {
            statusLabel.text = "Capture the UI first."
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(renderTree(root, 0)))
        statusLabel.text = "Tree copied."
    }

    private fun renderTree(node: UiNode, depth: Int): String = buildString {
        append("  ".repeat(depth)).append(node.describe()).append('\n')
        node.children.forEach { append(renderTree(it, depth + 1)) }
    }

    private fun updateStatus() {
        // Just the device name: the full describe() plus a hint overflowed a docked panel.
        statusLabel.text = device?.let { "Target: ${it.info.displayName} — press Capture UI" }
            ?: "No device selected."
    }

    override fun dispose() = Unit

    // ---------------------------------------------------------------- rendering

    private class NodeRenderer : DefaultTreeCellRenderer() {
        @Suppress("LongParameterList")
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val node = (value as? DefaultMutableTreeNode)?.userObject as? UiNode ?: return this

            text = node.describe()
            icon = null
            if (!selected) {
                foreground = when {
                    !node.enabled -> JBColor.GRAY
                    node.isInteractive -> INTERACTIVE_COLOR
                    else -> JBColor.foreground()
                }
            }
            return this
        }
    }

    private companion object {
        const val GAP = 4
        const val SEARCH_COLUMNS = 16
        const val SPLIT_PROPORTION = 0.6f
        const val DUMP_TIMEOUT_SECONDS = 30L
        const val MAX_AUTO_EXPAND_ROWS = 200

        val COMPOSE_COLOR = JBColor(0x1F6F4A, 0x57BA8C)
        val HYBRID_COLOR = JBColor(0x8A6100, 0xE0A030)
        val INTERACTIVE_COLOR = JBColor(0x2C5D92, 0x6EA8E0)

        /** One line describing a node, used by both the tree and the copied text. */
        fun UiNode.describe(): String = buildString {
            append(className.substringAfterLast('.'))
            testTag?.let { append("  #").append(it) }
            text.takeIf { it.isNotBlank() }?.let { append("  \"").append(it).append('"') }
            contentDescription.takeIf { it.isNotBlank() }?.let { append("  desc=\"").append(it).append('"') }
            if (isInteractive) append("  ·")
            if (clickable) append(" clickable")
            if (scrollable) append(" scrollable")
            if (!enabled) append("  DISABLED")
        }
    }
}
