package spock.adb.ui

import com.intellij.ui.components.JBLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JList

/**
 * What the popup draws for a row.
 *
 * The `CURRENT` badge exists so the foreground task is identifiable when the selection is
 * somewhere else entirely — which it usually is, since the popup opens on an activity.
 */
class ActivityStackRendererTest {

    private val renderer = ActivityStackRenderer()
    private val list = JList<ActivityStackRow>()

    private fun render(row: ActivityStackRow): JComponent =
        renderer.getListCellRendererComponent(list, row, 0, false, false) as JComponent

    private fun Container.labels(): List<JBLabel> =
        components.flatMap { if (it is JBLabel) listOf(it) else (it as? Container)?.labels().orEmpty() }

    /** The row's own text, in the order it is drawn, without the badge. */
    private fun Container.textLabels(): List<JBLabel> = labels().filter { it.text != CURRENT_BADGE }

    private fun badge(row: ActivityStackRow): JBLabel =
        render(row).labels().single { it.text == CURRENT_BADGE }

    @Test
    fun `the foreground task is badged`() {
        assertTrue(badge(ActivityStackRow.Task("com.example.app", isForeground = true)).isVisible)
    }

    @Test
    fun `every other row is not`() {
        assertFalse(badge(ActivityStackRow.Task("com.example.app", isForeground = false)).isVisible)
        assertFalse(badge(ActivityStackRow.Activity("com.example.app.MainActivity", "com.example.app")).isVisible)
        assertFalse(badge(ActivityStackRow.NoActivity("com.example.app")).isVisible)
    }

    @Test
    fun `a row renders its display text and carries the full name as a tooltip`() {
        val component = render(ActivityStackRow.Activity("com.example.app.MainActivity", "com.example.app"))
        val label = component.textLabels().first { it.isVisible }

        assertEquals("MainActivity", label.text)
        assertEquals("com.example.app.MainActivity", label.toolTipText)
    }

    @Test
    fun `a named task draws its package as a quieter second line`() {
        val component = render(ActivityStackRow.Task("com.example.app", appLabel = "My Application"))
        val shown = component.textLabels().filter { it.isVisible }

        assertEquals(listOf("My Application", "com.example.app"), shown.map { it.text })
        assertTrue(shown.last().font.size <= shown.first().font.size)
    }

    @Test
    fun `an unnamed task draws one line only`() {
        val component = render(ActivityStackRow.Task("com.example.app"))

        assertEquals(listOf("com.example.app"), component.textLabels().filter { it.isVisible }.map { it.text })
    }

    @Test
    fun `a row is measured for itself, not for the row drawn before it`() {
        // One renderer component is reused for every cell. A layout that caches what it
        // measured last time reports the short row's width for the long one, and the list then
        // draws `com.google.android.apps.nexuslauncher` ellipsised to the width of `Kanban`.
        val short = render(ActivityStackRow.Task("com.app", appLabel = "Kanban")).preferredSize.width
        val long = render(ActivityStackRow.Task("com.google.android.apps.nexuslauncher")).preferredSize.width

        assertTrue(long > short, "long row measured $long, short row $short")
    }

    @Test
    fun `an activity row is indented further than its task`() {
        val task = render(ActivityStackRow.Task("com.example.app", isForeground = false)).border.getBorderInsets(list)
        val activity = render(ActivityStackRow.Activity("com.example.app.MainActivity", "com.example.app"))
            .border.getBorderInsets(list)

        assertTrue(activity.left > task.left, "activity inset ${activity.left} vs task ${task.left}")
    }
}
