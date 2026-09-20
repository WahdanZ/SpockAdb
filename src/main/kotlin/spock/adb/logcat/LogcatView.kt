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

    /** The records the developer is pointing at: the selection, or the line under the caret. */
    fun selectedEntries(): List<LogcatEntry>

    /** The single line the caret is on, or null when a run of lines is selected. */
    fun caretIndex(): Int?

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
