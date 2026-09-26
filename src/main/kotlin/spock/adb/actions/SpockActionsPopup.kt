package spock.adb.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import spock.adb.AppSettingService
import spock.adb.premission.CheckBoxDialog
import spock.adb.premission.ListItem

/**
 * Every Spock ADB action in one searchable popup: the pinned ones first, then the last few
 * used, then all of them.
 *
 * The tool window made each action a button in a section, so running one meant opening the
 * window, finding the section and scrolling to it. From here it is one shortcut and a few
 * letters, with the tool windows closed. The entries are the registered actions — the same
 * ones as the Tools menu and Find Action — so each asks the same confirmation wherever it is
 * run from, and acts on the device and app in the status bar.
 */
class SpockActionsPopupAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        SpockActionsPopup.show(project, event.dataContext)
    }
}

/** Chooses which actions are pinned to the top of the Spock Actions popup. */
class CustomizeSpockActionsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val settings = AppSettingService.getInstance()
        val all = SpockActionsPopup.allActionIds()
        val pinned = settings.pinnedActionIds().toMutableList()
        val items = all.map { id -> ListItem(SpockActionsPopup.label(id), id in pinned) }
        CheckBoxDialog(items) { item ->
            val id = all[items.indexOf(item)]
            if (item.isSelected) pinned += id else pinned -= id
            // Kept in the order of the full list, so the pins read the way the menu does.
            settings.savePinnedActionIds(all.filter { it in pinned })
        }.apply {
            title = "Pinned Spock Actions"
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}

internal object SpockActionsPopup {

    private const val GROUP_ID = "SpockAdb.ActionGroup"
    private const val POPUP_ID = "spock.adb.actions.SpockActionsPopupAction"
    private const val CUSTOMIZE_ID = "spock.adb.actions.CustomizeSpockActionsAction"

    fun show(project: Project, dataContext: DataContext) {
        JBPopupFactory.getInstance().createActionGroupPopup(
            "Spock Actions",
            group(),
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        ).showCenteredInCurrentWindow(project)
    }

    /** The registered actions the popup offers, in the Tools menu's order, without itself. */
    fun allActionIds(): List<String> {
        val manager = ActionManager.getInstance()
        val group = manager.getAction(GROUP_ID) as? DefaultActionGroup ?: return emptyList()
        return group.childActionsOrStubs
            .filterNot { it is Separator || it is ActionGroup }
            .mapNotNull { manager.getId(it) }
            .filterNot { it == POPUP_ID || it == CUSTOMIZE_ID }
    }

    fun label(id: String): String =
        ActionManager.getInstance().getAction(id)?.templatePresentation?.text ?: id

    private fun group(): DefaultActionGroup {
        val settings = AppSettingService.getInstance()
        val all = allActionIds()
        val sections = PopupSections.of(
            pinned = settings.pinnedActionIds(),
            recent = settings.recentActionIds(),
            all = all,
        )
        return DefaultActionGroup().apply {
            section("Pinned", sections.pinned)
            section("Recent", sections.recent)
            section("All Actions", sections.all)
            add(Separator.create())
            ActionManager.getInstance().getAction(CUSTOMIZE_ID)?.let(::add)
        }
    }

    private fun DefaultActionGroup.section(title: String, ids: List<String>) {
        if (ids.isEmpty()) return
        add(Separator.create(title))
        ids.forEach { id -> ActionManager.getInstance().getAction(id)?.let { add(recording(id, it)) } }
    }

    /** The action, remembered as recent when it runs from here. */
    private fun recording(id: String, action: AnAction): AnAction = object : AnActionWrapper(action) {
        override fun actionPerformed(event: AnActionEvent) {
            AppSettingService.getInstance().recordRecentAction(id)
            super.actionPerformed(event)
        }
    }
}

/**
 * What goes in each section, apart from the IDE so it can be tested: pins and recents are
 * limited to actions that still exist, and a recent one already pinned is not listed twice.
 */
internal data class PopupSections(val pinned: List<String>, val recent: List<String>, val all: List<String>) {
    companion object {
        fun of(pinned: List<String>, recent: List<String>, all: List<String>): PopupSections {
            val known = all.toSet()
            val pins = pinned.filter { it in known }.distinct()
            return PopupSections(
                pinned = pins,
                recent = recent.filter { it in known && it !in pins }.distinct(),
                all = all,
            )
        }
    }
}
