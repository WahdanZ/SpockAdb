package spock.adb.logcat

import com.intellij.openapi.Disposable
import java.awt.Point
import javax.swing.JComponent

/**
 * The log, however it is drawn.
 *
 * Two implementations, because they are good at different things and the choice is the
 * developer's: [LogcatEditorView] is a real editor, so text selects across lines the way it does
 * in a terminal; [LogcatListView] is a row list, which is lighter and renders each record as a
 * discrete thing rather than as text. The panel above them only ever talks to this interface, so
 * switching is swapping one component for another and refilling it from the buffer.
 *
 * Everything here is called on the EDT.
 */
interface LogcatView : Disposable {

    /** The component to put on screen, scrolling included. */
    val component: JComponent

    /** Where a context menu is anchored and where a data context is taken from. */
    val contentComponent: JComponent

    /** Called when the developer moves the caret or changes the selection. */
    var onSelectionChanged: () -> Unit

    /** Everything currently shown, in order. */
    fun entries(): List<LogcatEntry>

    fun size(): Int

    /**
     * Only what the developer deliberately selected — empty when they have merely clicked.
     *
     * Distinct from [focused] because the two answer different questions and conflating them
     * had a real cost: "Ask AI" promised the filtered view when nothing was selected, and an
     * editor reports a caret line as a selection, so it sent exactly one line instead.
     */
    fun selection(): List<LogcatEntry>

    /**
     * What a details pane or a context menu should act on: the selection, or the caret line.
     *
     * One snapshot rather than two calls, so the lines and the index cannot describe different
     * moments — the selection can change between them while a menu is being built.
     */
    fun focus(): Focus

    /**
     * @param entries the records in focus.
     * @param singleIndex the index of the only record in focus, or null when several are.
     */
    data class Focus(val entries: List<LogcatEntry>, val singleIndex: Int?) {
        companion object {
            val NONE = Focus(emptyList(), null)
        }
    }

    /** Replaces everything, for a filter change. */
    fun setAll(replacement: List<LogcatEntry>, autoScroll: Boolean)

    /** Adds newly received lines. */
    fun append(batch: List<LogcatEntry>, autoScroll: Boolean)

    fun clear()

    fun scrollToEnd()

    /** Moves the selection to what was right-clicked, unless the click was inside a selection. */
    fun focusLineAt(point: Point)

    /**
     * Binds the platform's copy shortcut, for a view that does not already have one.
     *
     * On the interface rather than done by the panel for both, because doing it for the editor
     * would shadow the editor's own copy action with a worse one.
     */
    fun installCopyShortcut(copy: () -> Unit)
}
