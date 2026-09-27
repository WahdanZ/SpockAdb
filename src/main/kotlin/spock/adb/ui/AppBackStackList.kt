package spock.adb.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.models.ActivityData
import spock.adb.models.FragmentData
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ToolTipManager

/**
 * One line of the App Back Stack popup: an activity of the selected app, or a fragment it has
 * added, indented under it by how deep it nests.
 *
 * The popup used to be a chooser over bare strings — `RecipesActivity(Activity)`,
 * `  |--HomeFragment (Fragment)` — with no order, no state, and no highlight on the selected row.
 */
internal sealed interface AppBackStackRow {

    /** [position] 1 is the top of the stack; [state] is `Resumed`, `Stopped`… or blank. */
    data class Activity(
        val className: String,
        val appPackage: String,
        val position: Int,
        val state: String,
    ) : AppBackStackRow

    /** [className] as `dumpsys` printed it: a simple name on current androidx. */
    data class Fragment(val className: String, val depth: Int) : AppBackStackRow
}

/** The activities of [appPackage], top first, each followed by its fragments. */
internal fun List<ActivityData>.toAppBackStackRows(appPackage: String): List<AppBackStackRow> = buildList {
    this@toAppBackStackRows.forEachIndexed { index, activity ->
        add(AppBackStackRow.Activity(activity.activity, appPackage, index + 1, activity.status))
        activity.fragments.forEach { addFragment(it, depth = 1) }
    }
}

private fun MutableList<AppBackStackRow>.addFragment(fragment: FragmentData, depth: Int) {
    add(AppBackStackRow.Fragment(fragment.fragment, depth))
    fragment.innerFragments.forEach { addFragment(it, depth + 1) }
}

/** An activity in the app's package shortened to the part that differs; a fragment by simple name. */
internal fun AppBackStackRow.displayText(): String = when (this) {
    is AppBackStackRow.Activity -> className.removePrefix("$appPackage.").ifBlank { className }
    is AppBackStackRow.Fragment -> className.substringAfterLast('.')
}

/** What the badge on the right says: the activity's state, nothing for a fragment. */
internal fun AppBackStackRow.badgeText(): String? =
    (this as? AppBackStackRow.Activity)?.state?.takeIf { it.isNotBlank() }?.uppercase()

internal val AppBackStackRow.className: String
    get() = when (this) {
        is AppBackStackRow.Activity -> className
        is AppBackStackRow.Fragment -> className
    }

/**
 * The App Back Stack popup's list. Opens on the top activity, so Enter goes straight to the code
 * of what is on screen.
 */
internal class AppBackStackList(rows: List<AppBackStackRow>) : JBList<AppBackStackRow>(rows) {

    init {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = AppBackStackRenderer()
        ToolTipManager.sharedInstance().registerComponent(this)
        if (rows.isNotEmpty()) selectedIndex = 0
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val index = locationToIndex(event.point)
        if (index < 0 || getCellBounds(index, index)?.contains(event.point) != true) return null
        return model.getElementAt(index).className
    }
}

/**
 * An activity reads as its position and name with its state on the right — `RESUMED` in green,
 * anything else quieter — and its fragments sit under it, indented by depth.
 */
private class AppBackStackRenderer : ListCellRenderer<AppBackStackRow> {

    private val position = JBLabel().apply { font = JBFont.small() }
    private val text = JBLabel()
    private val badge = JBLabel().apply { font = JBFont.small().asBold() }
    private val panel = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
        isOpaque = true
        add(position, BorderLayout.WEST)
        add(text, BorderLayout.CENTER)
        add(badge, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out AppBackStackRow>,
        value: AppBackStackRow?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val row = value ?: return panel
        val muted = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()

        panel.background = if (isSelected) list.selectionBackground else list.background
        panel.border = when (row) {
            is AppBackStackRow.Activity -> JBUI.Borders.empty(ACTIVITY_TOP_PAD, PAD, ROW_PAD, PAD)
            is AppBackStackRow.Fragment -> JBUI.Borders.empty(ROW_PAD, PAD + INDENT * row.depth, ROW_PAD, PAD)
        }

        position.isVisible = row is AppBackStackRow.Activity
        position.text = (row as? AppBackStackRow.Activity)?.position?.toString().orEmpty()
        position.foreground = muted

        text.text = if (row is AppBackStackRow.Fragment) "$FRAGMENT_MARK ${row.displayText()}" else row.displayText()
        text.font = if (row is AppBackStackRow.Activity) JBFont.label().asBold() else JBFont.label()
        text.foreground = if (isSelected) list.selectionForeground else list.foreground

        val badgeText = row.badgeText()
        badge.isVisible = badgeText != null
        badge.text = badgeText.orEmpty()
        badge.foreground = when {
            isSelected -> list.selectionForeground
            badgeText == RESUMED -> RESUMED_COLOUR
            else -> UIUtil.getContextHelpForeground()
        }
        return panel
    }

    private companion object {
        const val GAP = 8
        const val PAD = 10
        const val INDENT = 16
        const val ROW_PAD = 2
        const val ACTIVITY_TOP_PAD = 6
        const val FRAGMENT_MARK = "└"
        const val RESUMED = "RESUMED"
        val RESUMED_COLOUR = JBColor(0x1A7F37, 0x57A64A)
    }
}
