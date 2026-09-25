package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import spock.adb.device.ConnectedDevice
import spock.adb.device.ops.InspectionOperations
import spock.adb.device.ops.UiTreeOperations
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.function.Function
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
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
 *
 * This class lays the tab out and owns its state; what each part draws lives in its own
 * component: [InspectorHeader], [UiNodeRenderer], [NodeDetailsPanel] and [AuditFindingsPanel].
 * [SourceNavigator] finds and opens the source a selected element most likely comes from.
 */
class UiInspectorPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val header = InspectorHeader(::notice)
    private val statusLabel = JBLabel(" ")

    private val treeModel = DefaultTreeModel(null)
    private val tree = Tree(treeModel)

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Test tag, text or content description"
        toolTipText = "Match test tag, text or content description"
    }
    private val matchCount = JBLabel().apply { foreground = JBColor.GRAY }
    private var interactiveOnly = false

    private val source = SourceNavigator(project, ::notice)
    private val details = NodeDetailsPanel(::notice, source.line)
    private val findings = AuditFindingsPanel(::select)
    private val detailTabs = JBTabbedPane()

    private var connected: ConnectedDevice? = null
    private val device: DeviceLabel? get() = connected?.let { DeviceLabel(it.serialNumber, it.info.displayName) }
    private var captured: UiObservation? = null

    /** The Activity resumed when [captured] was taken: Jump to Source's answer when nothing else is found. */
    private var capturedActivity: String? = null

    /** Each captured node's place in the viewport, worked out with the capture on the pooled thread. */
    private var visibility: Map<UiNode, NodeVisibility> = emptyMap()
    private var capturedFrom: DeviceLabel? = null
    private var capturing = false
    private var failure: Throwable? = null
    private var disposed = false

    /** A one-off message — "Copied …" — shown until the next change of state. */
    private var pendingNotice: String? = null

    private val capturedTree: UiTree? get() = captured?.tree

    init {
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = UiNodeRenderer { visibility[it] }
        tree.addTreeSelectionListener { showDetails() }
        // Typing in the tree jumps to a row, as in any IDE tree; the search field filters instead.
        TreeSpeedSearch.installOn(tree, true, Function { path: TreePath -> path.uiNode?.describe().orEmpty() })
        PopupHandler.installFollowingSelectionTreePopup(tree, copyActions(), TREE_POPUP_PLACE)
        source.install(tree, focusScope = this)
        Disposer.register(this, source)

        setToolbar(top())
        setContent(body())
        wire()
        refresh()
    }

    /**
     * Called whenever the device list refreshes, which it does on its own — so this must not
     * disturb a capture of the same device. A different device leaves the tree where it is and
     * marks it stale instead of replacing it.
     */
    fun setDevice(device: ConnectedDevice?) {
        if (device?.serialNumber != connected?.serialNumber) {
            // A failure, or a "Copied" notice, is about the device that was selected before.
            failure = null
            pendingNotice = null
        }
        connected = device
        refresh()
    }

    // ---------------------------------------------------------------- layout

    private fun top(): JComponent {
        val actions = DefaultActionGroup().apply {
            add(
                action("Capture UI", "Read the semantics tree from the device", AllIcons.Actions.Refresh, {
                    !capturing
                }) { capture() },
            )
            add(
                action(
                    "Accessibility Audit",
                    "Check this screen for accessibility problems",
                    AllIcons.General.InspectionsEye,
                    { capturedTree != null },
                ) { runAudit() },
            )
            add(
                action("Copy Tree", "Copy the whole tree as text", AllIcons.Actions.Copy, { capturedTree != null }) {
                    copyTree()
                },
            )
            addSeparator()
            add(source.autoscrollAction)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
            add(searchRow(), BorderLayout.SOUTH)
        }
    }

    private fun searchRow(): JComponent {
        val filter = object : ToggleAction(
            "Interactive Only",
            "Show only elements that can be tapped, long-pressed, checked or scrolled",
            AllIcons.General.Filter,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = interactiveOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                interactiveOnly = state
                rebuildTree()
            }
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, DefaultActionGroup(filter), true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            border = JBUI.Borders.empty(2, GAP, 2, GAP)
            add(searchField, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
                    add(matchCount, BorderLayout.WEST)
                    add(toolbar.component, BorderLayout.EAST)
                },
                BorderLayout.EAST,
            )
        }
    }

    private fun body(): JComponent {
        detailTabs.addTab("Properties", details)
        detailTabs.addTab(AUDIT_TAB, findings)

        val splitter = OnePixelSplitter(true, SPLIT_PROPORTION).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = detailTabs
        }
        return JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                        JBUI.Borders.empty(2, GAP * 2),
                    )
                    add(statusLabel, BorderLayout.CENTER)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun action(
        text: String,
        description: String,
        icon: Icon,
        enabled: () -> Boolean = { true },
        run: () -> Unit,
    ) = object : AnAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    /** The tree's right-click menu: its source, then a selector for the element under the pointer, in each form. */
    private fun copyActions() = DefaultActionGroup().apply {
        add(source.jumpAction)
        addSeparator()
        val forms: List<Triple<String, String, (SelectorSuggestion) -> String?>> = listOf(
            Triple("Copy MCP Selector", "Copy JSON arguments for the MCP element tools", { it.mcpJson }),
            Triple("Copy Compose Test Finder", "Copy a composeTestRule finder", { it.composeTest }),
            Triple("Copy UI Automator Selector", "Copy a UI Automator BySelector", { it.uiAutomator }),
        )
        forms.forEach { (text, description, form) ->
            val available = { selectedSuggestion()?.let(form) != null }
            add(action(text, description, AllIcons.Actions.Copy, available) { copySelector(form) })
        }
        addSeparator()
        add(
            action("Copy Tree", "Copy the whole tree as text", AllIcons.Actions.Copy, { capturedTree != null }) {
                copyTree()
            },
        )
    }

    private fun wire() {
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = rebuildTree()
            },
        )
    }

    // ---------------------------------------------------------------- capture

    private fun capture() {
        val target = connected ?: return refresh()
        val from = DeviceLabel(target.serialNumber, target.info.displayName)
        capturing = true
        failure = null
        pendingNotice = null
        refresh()

        // The dump is a blocking ADB round trip plus a file read; never on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val observation = observe(target)
                observation to ViewportVisibility.classifyAll(observation)
            }

            ApplicationManager.getApplication().invokeLater({
                capturing = false
                result
                    .onSuccess { (observation, classified) -> onCaptured(observation, classified, from) }
                    .onFailure { failure = it }
                refresh()
            }) { project.isDisposed || disposed }

            // Read once the tree is up: it is a last resort for Jump to Source, and on Android 13+ two
            // more `dumpsys` round trips that no capture should wait for.
            val observation = result.getOrNull()?.first ?: return@executeOnPooledThread
            val activity = resumedActivity(target) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                // A newer capture has its own Activity to read.
                if (captured !== observation) return@invokeLater
                capturedActivity = activity
                source.activityRead(activity)
            }) { project.isDisposed || disposed }
        }
    }

    /** The same capture the `android_get_ui_tree` family runs — see [UiTreeOperations]. */
    private fun observe(target: ConnectedDevice): UiObservation =
        UiTreeOperations(target.device, serial = target.serialNumber).observe()

    /**
     * The Activity on screen, read after the tree is shown with the same code as Current Activity.
     * Null when it cannot be read: it is only a last resort for Jump to Source, never a reason to fail
     * or delay a capture.
     */
    private fun resumedActivity(target: ConnectedDevice): String? =
        runCatching { InspectionOperations(target.device).currentActivity() }.getOrNull()

    private fun onCaptured(observation: UiObservation, classified: Map<UiNode, NodeVisibility>, from: DeviceLabel) {
        captured = observation
        // Filled in by the read that follows the capture; the last capture's is not this screen's.
        capturedActivity = null
        visibility = classified
        capturedFrom = from
        header.show(observation, from)
        findings.clear()
        detailTabs.setTitleAt(AUDIT_TAB_INDEX, AUDIT_TAB)
        rebuildTree()
    }

    // ---------------------------------------------------------------- tree

    private fun rebuildTree() {
        val uiTree = capturedTree ?: return refresh()
        val root = uiTree.root ?: run {
            treeModel.setRoot(null)
            matchCount.text = ""
            return refresh()
        }
        val query = searchField.text.orEmpty().trim()
        val filtering = query.isNotEmpty() || interactiveOnly

        if (filtering) {
            val matches = inspectorMatches(uiTree, query, interactiveOnly)
            treeModel.setRoot(DefaultMutableTreeNode().apply { matches.forEach { add(DefaultMutableTreeNode(it)) } })
            tree.isRootVisible = false
            tree.showsRootHandles = false
            matchCount.text = if (matches.size == 1) "1 match" else "${matches.size} matches"
        } else {
            treeModel.setRoot(buildBranch(root))
            tree.isRootVisible = true
            tree.showsRootHandles = true
            matchCount.text = ""
        }
        expandAll()
        refresh()
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

    /**
     * Selects [node] in the tree, clearing the filters first when they hide it: a finding the
     * developer clicked on must land somewhere, not on a row the search left out.
     */
    private fun select(node: UiNode) {
        val path = pathTo(node) ?: run {
            if (searchField.text.isNotEmpty()) searchField.text = ""
            interactiveOnly = false
            rebuildTree()
            pathTo(node)
        } ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun pathTo(node: UiNode): TreePath? {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return null
        val found = root.depthFirstEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { it.userObject === node }
        return found?.let { TreePath(it.path) }
    }

    // ---------------------------------------------------------------- details

    private fun selectedNode(): UiNode? = tree.selectionPath?.uiNode

    private fun selectedSuggestion(): SelectorSuggestion? {
        val node = selectedNode() ?: return null
        return SelectorSuggestion.forNode(node, capturedTree?.framework ?: UiFramework.UNKNOWN)
    }

    private fun showDetails() {
        val node = selectedNode()
        details.show(node, captured, capturedTree, node?.let { visibility[it] })
        source.select(node, captured, capturedActivity)
        pendingNotice = null
        refresh()
    }

    private fun runAudit() {
        val observation = captured ?: return
        val found = AccessibilityAudit.audit(observation)
        findings.show(found, observation)
        detailTabs.setTitleAt(AUDIT_TAB_INDEX, "$AUDIT_TAB (${found.size})")
        detailTabs.selectedIndex = AUDIT_TAB_INDEX
        notice(if (found.size == 1) "1 accessibility finding." else "${found.size} accessibility findings.")
    }

    private fun copyTree() {
        val root = capturedTree?.root ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(renderTree(root)))
        notice("Tree copied.")
    }

    private fun copySelector(form: (SelectorSuggestion) -> String?) {
        val text = selectedSuggestion()?.let(form) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notice("Copied $text")
    }

    // ---------------------------------------------------------------- status

    private fun notice(message: String) {
        pendingNotice = message
        statusLabel.text = message
    }

    /** Puts the status line and the empty tree's text in step with the state. */
    private fun refresh() {
        statusLabel.text = pendingNotice ?: InspectorStatus.text(device, capturedFrom, capturedTree, capturing, failure)
        refreshEmptyText()
    }

    private fun refreshEmptyText() {
        val empty = tree.emptyText
        empty.clear()
        val uiTree = capturedTree
        when {
            uiTree != null && uiTree.root == null -> empty.appendText("The capture contained no UI nodes.")
            uiTree != null -> empty.appendText("Nothing matches the search.")
            capturing -> empty.appendText("Capturing…")
            device == null -> {
                empty.appendLine("No device selected")
                empty.appendLine("Choose one in the Devices tab", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            else -> {
                empty.appendLine("No UI captured yet")
                empty.appendLine("Capture UI", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { capture() }
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    private companion object {
        const val GAP = 4
        const val SPLIT_PROPORTION = 0.55f
        const val MAX_AUTO_EXPAND_ROWS = 200
        const val AUDIT_TAB = "Accessibility"
        const val AUDIT_TAB_INDEX = 1
        const val TREE_POPUP_PLACE = "SpockAdb.UiInspector.Tree"

        val TreePath.uiNode: UiNode? get() = (lastPathComponent as? DefaultMutableTreeNode)?.userObject as? UiNode
    }
}
