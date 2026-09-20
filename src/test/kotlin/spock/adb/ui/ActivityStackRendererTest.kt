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

    private fun Container.labels(): List<JBLabel> = components.filterIsInstance<JBLabel>()

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
        val label = component.labels().single { it.text != CURRENT_BADGE }

        assertEquals("MainActivity", label.text)
        assertEquals("com.example.app.MainActivity", label.toolTipText)
    }

    @Test
    fun `an activity row is indented further than its task`() {
        val task = render(ActivityStackRow.Task("com.example.app", isForeground = false)).border.getBorderInsets(list)
        val activity = render(ActivityStackRow.Activity("com.example.app.MainActivity", "com.example.app"))
            .border.getBorderInsets(list)

        assertTrue(activity.left > task.left, "activity inset ${activity.left} vs task ${task.left}")
    }
}
