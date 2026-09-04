package spock.adb.ui

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JComboBox
import javax.swing.ListCellRenderer

/**
 * Renders combo box entries with a human label instead of `toString()`.
 *
 * Without this a Kotlin enum renders as its constant name — the logcat preset selector
 * literally read `CURRENT_APP` — which is developer-facing shorthand leaking into the UI.
 */
object EnumRenderers {

    fun <T> labelled(label: (T) -> String): ListCellRenderer<T?> =
        SimpleListCellRenderer.create("") { item: T? -> item?.let(label).orEmpty() }
}

fun <T> JComboBox<T>.renderWith(label: (T) -> String) {
    renderer = EnumRenderers.labelled(label)
}
