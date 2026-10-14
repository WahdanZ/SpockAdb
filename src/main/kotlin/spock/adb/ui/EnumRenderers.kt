package spock.adb.ui

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Renders combo box entries with a human label instead of `toString()`.
 *
 * Without this a Kotlin enum renders as its constant name — the logcat preset selector
 * literally read `CURRENT_APP` — which is developer-facing shorthand leaking into the UI.
 */
object EnumRenderers {

    /**
     * A subclass rather than `SimpleListCellRenderer.create`: those factories are scheduled for
     * removal (IntelliJ IDEA 2026.2), and the class itself is the stable API back to 2023.2.
     * A null entry renders blank, as `create("")` did.
     */
    fun <T> labelled(label: (T) -> String): ListCellRenderer<T?> =
        object : SimpleListCellRenderer<T?>() {
            override fun customize(list: JList<out T?>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let(label).orEmpty()
            }
        }
}

fun <T> JComboBox<T>.renderWith(label: (T) -> String) {
    renderer = EnumRenderers.labelled(label)
}
