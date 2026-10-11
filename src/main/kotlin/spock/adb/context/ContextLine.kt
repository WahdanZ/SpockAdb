package spock.adb.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * One line at the top of the Spock ADB window naming the device and app, which opens the same
 * chooser as the status bar.
 *
 * It replaces the header's two dropdowns, a refresh and a gear, which took two rows of a docked
 * window for choices made a few times a day. The status-bar widget is the main place to change
 * them; this line is here because a widget that small is easy to miss, and a tool window should
 * say what it is about.
 */
internal class ContextLine(private val project: Project, parent: Disposable) :
    JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

    private val selection = SpockSelection.getInstance(project)

    private val link = ActionLink(ContextText.NO_DEVICE) { choose() }.apply {
        // Device-supplied text: a label whose text starts with a tag would be rendered as markup.
        putClientProperty("html.disable", true)
        setDropDownLinkIcon()
    }

    init {
        border = JBUI.Borders.empty(GAP, GAP * 2, 0, GAP)
        add(link)
        selection.addListener(parent) { snapshot, _ ->
            link.text = ContextText.of(snapshot)
            link.toolTipText = ContextText.tooltip(snapshot, selection.followsStudio)
        }
    }

    private fun choose() {
        JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            ContextActions.group(project, selection, ::acknowledge),
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        ).showUnderneathOf(link)
    }

    /**
     * A short balloon under the line saying what was chosen. The line's text changing was the only
     * sign a choice had been made, and in a busy panel that went unnoticed. Shown after the popup
     * has closed, which would otherwise take the balloon with it.
     */
    private fun acknowledge(text: String) {
        ApplicationManager.getApplication().invokeLater({
            if (!link.isShowing) return@invokeLater
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(StringUtil.escapeXmlEntities(text), MessageType.INFO, null)
                .setFadeoutTime(FADE_MS)
                .setHideOnClickOutside(true)
                .createBalloon()
                .show(RelativePoint.getSouthOf(link), Balloon.Position.below)
        }) { project.isDisposed }
    }

    private companion object {
        const val GAP = 4
        const val FADE_MS = 3_000L
    }
}
