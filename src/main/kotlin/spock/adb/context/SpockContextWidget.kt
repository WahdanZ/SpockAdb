package spock.adb.context

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import spock.adb.actions.SpockActionsPopup

/**
 * The device and app every Spock surface acts on, in the status bar.
 *
 * Next to the Git branch and the encoding, because it is the same kind of fact: something
 * chosen once that everything else in the window depends on. It is on screen when every Spock
 * tool window is hidden — which is when an action run from the keyboard would otherwise act on a
 * device nobody could see — and a click changes it for all of them.
 */
class SpockContextWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "Spock ADB Device and App"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = SpockContextWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "SpockAdb.Context"
    }
}

internal class SpockContextWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {

    private val selection = SpockSelection.getInstance(project)
    private var statusBar: StatusBar? = null

    override fun ID(): String = SpockContextWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        selection.addListener(this) { _, _ -> statusBar.updateWidget(ID()) }
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getSelectedValue(): String = ContextText.of(selection.snapshot)

    override fun getTooltipText(): String = ContextText.tooltip(selection.snapshot, selection.followsStudio)

    /** The 2023.1+ entry point. */
    override fun getPopup(): JBPopup = popup()

    /** What 2023.2 builds before [getPopup] ask for; the same popup. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getPopupStep(): ListPopup = popup()

    private fun popup(): ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
        "Spock ADB: Device and App",
        ContextActions.group(project, selection),
        SimpleDataContext.getProjectContext(project),
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
        true,
    )
}

/** What the widget says, separate from Swing so it can be tested. */
internal object ContextText {

    fun of(snapshot: SpockSelection.Snapshot): String {
        val device = snapshot.device ?: return if (snapshot.devices.isEmpty()) NO_DEVICE else NO_SELECTION
        val app = snapshot.app ?: return device.info.compactLabel()
        return "${device.info.compactLabel()} · $app"
    }

    fun tooltip(snapshot: SpockSelection.Snapshot, followsStudio: Boolean): String {
        val device = snapshot.device?.info?.let { "${it.describe()} · ${it.details()}" } ?: NO_DEVICE
        val app = snapshot.app ?: "no app chosen"
        val follow = if (followsStudio) "Follows Android Studio's run target." else "Chosen here only."
        return "Spock ADB acts on $device, app $app. $follow Click to change."
    }

    const val NO_DEVICE = "No Android device"
    const val NO_SELECTION = "Choose a device"
}

/** The popup's entries: every device, the apps on the selected one, and how the choice is made. */
internal object ContextActions {

    fun group(project: Project, selection: SpockSelection): DefaultActionGroup = DefaultActionGroup().apply {
        val snapshot = selection.snapshot
        add(Separator.create("Device"))
        if (snapshot.devices.isEmpty()) add(disabled(ContextText.NO_DEVICE))
        snapshot.devices.forEach { device ->
            add(
                choice(device.info.shortLabel(), selected = device.serialNumber == snapshot.device?.serialNumber) {
                    selection.selectDevice(device.serialNumber)
                },
            )
        }
        add(Separator.create("App"))
        snapshot.apps.forEach { app ->
            val label = if (app == snapshot.projectApp) "$app  (this project)" else app
            add(choice(label, selected = app == snapshot.app) { selection.selectApp(app) })
        }
        add(
            DumbAwareAction.create("Other App…") {
                Messages.showInputDialog(
                    project,
                    "Package name of the app to act on:",
                    "Choose App",
                    null,
                    snapshot.app.orEmpty(),
                    null,
                )?.let(selection::selectApp)
            },
        )
        add(Separator.create())
        add(
            object : DumbAwareToggleAction("Follow Android Studio's Device") {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(event: AnActionEvent) = selection.followsStudio
                override fun setSelected(event: AnActionEvent, state: Boolean) {
                    selection.followsStudio = state
                }
            },
        )
        add(DumbAwareAction.create("Refresh Devices and Apps") { selection.refresh() })
        add(Separator.create())
        add(
            DumbAwareAction.create("Spock Actions…") { event ->
                // Its actions read the project from the context and are disabled without one.
                val context = event.dataContext.takeIf { CommonDataKeys.PROJECT.getData(it) != null }
                    ?: SimpleDataContext.getProjectContext(project)
                SpockActionsPopup.show(project, context)
            },
        )
    }

    private fun choice(text: String, selected: Boolean, choose: () -> Unit): AnAction =
        object : DumbAwareToggleAction(text) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(event: AnActionEvent) = selected
            override fun setSelected(event: AnActionEvent, state: Boolean) = choose()
        }

    private fun disabled(text: String): AnAction = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = false
        }
        override fun actionPerformed(event: AnActionEvent) = Unit
    }
}
