package spock.adb.mcp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
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

    override fun isModified(): Boolean =
        (historySpinner?.value as? Int)?.let { it != service.historySize } ?: false

    override fun apply() {
        (historySpinner?.value as? Int)?.let { service.historySize = it }
    }

    override fun reset() {
        historySpinner?.value = service.historySize
    }

    override fun disposeUIResources() {
        panel = null
        historySpinner = null
    }

    private companion object {
        const val GAP = 8
        const val SECTION_GAP = 12
        const val HISTORY_STEP = 50
        const val NOT_BOUND = "not bound"
        const val KEYMAP_ID = "preferences.keymap"

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
