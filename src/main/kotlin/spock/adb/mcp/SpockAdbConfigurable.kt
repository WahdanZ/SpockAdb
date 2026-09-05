package spock.adb.mcp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * `Settings → Tools → Spock ADB`.
 *
 * The shortcut table here is **read-only on purpose**. IntelliJ's Keymap is the source of
 * truth: duplicating its editing UI would mean two places to change a binding and one of
 * them being wrong. This exists for discoverability — so a developer can see what is bound
 * without hunting through Keymap — and hands off to the real editor.
 */
class SpockAdbConfigurable : Configurable {

    private val service = McpServerService.getInstance()
    private var historySpinner: JSpinner? = null
    private var panel: JComponent? = null

    /** One checkbox per registered tool, in registry order within its safety group. */
    private val toolChecks = LinkedHashMap<String, JCheckBox>()

    override fun getDisplayName(): String = "Spock ADB"

    override fun createComponent(): JComponent {
        val content = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            gridx = 0
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(0, 0, JBUI.scale(SECTION_GAP), 0)
        }

        content.add(mcpSection(), constraints)
        content.add(toolAccessSection(), constraints)
        content.add(shortcutSection(), constraints)
        content.add(
            JPanel().apply { },
            constraints.apply {
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            },
        )

        return JBScrollPane(content).apply {
            border = JBUI.Borders.empty(GAP)
        }.also { panel = it }
    }

    private fun mcpSection(): JComponent {
        val spinner = JSpinner(
            SpinnerNumberModel(
                service.historySize,
                McpRequestHistory.MIN_CAPACITY,
                McpRequestHistory.MAX_CAPACITY,
                HISTORY_STEP,
            ),
        )
        historySpinner = spinner

        val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            add(JBLabel("Keep the most recent"))
            add(spinner)
            add(JBLabel("MCP requests"))
        }

        return section(
            "MCP Server",
            JPanel(BorderLayout()).apply {
                add(
                    JBLabel(
                        "<html>Exposes the connected device to MCP-compatible AI agents. " +
                            "Off by default. Destructive tools always ask before running.</html>",
                    ),
                    BorderLayout.NORTH,
                )
                add(row, BorderLayout.CENTER)
            },
        )
    }

    /**
     * Which tools an agent may call at all.
     *
     * Grouped by safety level rather than listed alphabetically because the useful decisions
     * are made a group at a time — "read-only and nothing else" is the common one, and it is
     * one click here. The per-call confirmation on destructive tools is unchanged: this
     * decides what may be *attempted*, that decides what actually runs.
     *
     * Every tool is listed, including the disabled ones. A tool that vanished when switched
     * off could not be switched back on.
     */
    private fun toolAccessSection(): JComponent {
        toolChecks.clear()
        val list = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            gridx = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        SAFETY_ORDER.forEach { safety ->
            val group = ToolRegistry.all().filter { it.safety == safety }
            if (group.isEmpty()) return@forEach
            list.add(groupHeader(safety, group.size), constraints)
            group.forEach { tool ->
                val box = JCheckBox(tool.name).apply {
                    isSelected = service.isToolEnabled(tool.name)
                    toolTipText = tool.description
                }
                toolChecks[tool.name] = box
                list.add(box, constraints)
            }
        }

        return section(
            "Tool Access",
            JPanel(BorderLayout()).apply {
                add(
                    JBLabel(
                        "<html>Tools that are switched off stay visible to the agent and refuse " +
                            "when called, so it is told why rather than left guessing.</html>",
                    ),
                    BorderLayout.NORTH,
                )
                add(list, BorderLayout.CENTER)
                add(bulkButtons(), BorderLayout.SOUTH)
            },
        )
    }

    private fun groupHeader(safety: ToolSafety, count: Int): JComponent = JBLabel(
        "${safetyTitle(safety)} ($count)",
    ).apply {
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(GAP, 0, 2, 0)
    }

    private fun safetyTitle(safety: ToolSafety): String = when (safety) {
        ToolSafety.READ_ONLY -> "Read-only"
        ToolSafety.SAFE_ACTION -> "Changes state, undoable by hand"
        ToolSafety.DESTRUCTIVE -> "Destructive — always asks before running"
    }

    private fun bulkButtons(): JComponent =
        JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(GAP))).apply {
            add(JButton("Enable all").apply { addActionListener { selectWhere { true } } })
            add(
                JButton("Read-only only").apply {
                    addActionListener { selectWhere { it.safety == ToolSafety.READ_ONLY } }
                },
            )
        }

    private fun selectWhere(predicate: (AdbTool) -> Boolean) {
        ToolRegistry.all().forEach { tool -> toolChecks[tool.name]?.isSelected = predicate(tool) }
    }

    /** Names of the tools left unchecked. Empty when everything is enabled. */
    private fun uncheckedTools(): Set<String> =
        toolChecks.filterValues { !it.isSelected }.keys.toSet()

    private fun shortcutSection(): JComponent {
        val table = JPanel(GridBagLayout())
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(0, 0, JBUI.scale(2), JBUI.scale(SECTION_GAP))
        }
        val shortcutConstraints = GridBagConstraints().apply {
            gridx = 1
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 0, JBUI.scale(2), 0)
        }

        SHORTCUT_ACTIONS.forEach { actionId ->
            val presentationText = actionText(actionId) ?: return@forEach
            table.add(JBLabel(presentationText), labelConstraints)
            table.add(
                JBLabel(shortcutFor(actionId)).apply {
                    font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
                    if (text == NOT_BOUND) foreground = com.intellij.ui.JBColor.GRAY
                },
                shortcutConstraints,
            )
        }

        return section(
            "Keyboard Shortcuts",
            JPanel(BorderLayout()).apply {
                add(
                    JBLabel(
                        "<html>Spock ADB ships no default shortcuts, so it cannot take a " +
                            "combination you already use. Assign your own in Keymap.</html>",
                    ),
                    BorderLayout.NORTH,
                )
                add(table, BorderLayout.CENTER)
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(GAP))).apply {
                        add(
                            JButton("Edit in Keymap").apply {
                                addActionListener { openKeymap() }
                            },
                        )
                    },
                    BorderLayout.SOUTH,
                )
            },
        )
    }

    /** Live from the active keymap, so the table cannot drift from reality. */
    private fun shortcutFor(actionId: String): String {
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(actionId)
        return shortcuts.firstOrNull()?.let(KeymapUtil::getShortcutText) ?: NOT_BOUND
    }

    private fun actionText(actionId: String): String? =
        ActionManager.getInstance().getAction(actionId)?.templatePresentation?.text

    private fun openKeymap() =
        ShowSettingsUtil.getInstance().showSettingsDialog(null as com.intellij.openapi.project.Project?, KEYMAP_ID)

    private fun section(title: String, body: JComponent): JComponent = JPanel(BorderLayout()).apply {
        border = com.intellij.ui.IdeBorderFactory.createTitledBorder(title, false)
        add(body, BorderLayout.CENTER)
    }

    override fun isModified(): Boolean {
        val historyChanged = (historySpinner?.value as? Int)?.let { it != service.historySize } ?: false
        val toolsChanged = toolChecks.isNotEmpty() && uncheckedTools() != service.disabledTools
        return historyChanged || toolsChanged
    }

    override fun apply() {
        (historySpinner?.value as? Int)?.let { service.historySize = it }
        if (toolChecks.isNotEmpty()) service.setDisabledTools(uncheckedTools())
    }

    override fun reset() {
        historySpinner?.value = service.historySize
        toolChecks.forEach { (name, box) -> box.isSelected = service.isToolEnabled(name) }
    }

    override fun disposeUIResources() {
        panel = null
        historySpinner = null
        toolChecks.clear()
    }

    private companion object {
        const val GAP = 8
        const val SECTION_GAP = 12
        const val HISTORY_STEP = 50
        const val NOT_BOUND = "not bound"
        const val KEYMAP_ID = "preferences.keymap"

        /** Least dangerous first, so the list reads as a widening of what an agent may do. */
        val SAFETY_ORDER = listOf(ToolSafety.READ_ONLY, ToolSafety.SAFE_ACTION, ToolSafety.DESTRUCTIVE)

        /** Shown in the overview. Order matches how often they are actually used. */
        val SHORTCUT_ACTIONS = listOf(
            "spock.adb.actions.RestartAppAction",
            "spock.adb.actions.ForceStopAppAction",
            "spock.adb.actions.ClearAppDataAction",
            "spock.adb.actions.GetCurrentActivityAction",
            "spock.adb.actions.GetCurrentFragmentAction",
            "spock.adb.actions.OpenLogcatAction",
            "spock.adb.actions.OpenCommandCenterAction",
            "spock.adb.actions.OpenMcpPanelAction",
            "spock.adb.mcp.ToggleMcpServerAction",
        )
    }
}
