package spock.adb.logcat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Font
import javax.swing.JComponent
import kotlin.math.max

/**
 * The log drawn in a real editor: the default view.
 *
 * It began as a `JBList` with a cell renderer, which is the obvious choice and the wrong one:
 * a list cell is an all-or-nothing unit, so a developer could select *rows* but could never
 * drag across part of a message, or from the end of one line into the next — the thing everyone
 * does in a terminal, and the thing they asked for first. There is no way to add that to a
 * list; text selection is what an editor is.
 *
 * What the editor brings for free is most of the rest of this file's former job: character
 * selection, the platform's own copy, find-in-view, soft wraps, and a scrolling model that does
 * not fight the user. What it costs is that a document is flat text, so the mapping from a line
 * back to its [LogcatEntry] has to be maintained here, exactly, and every line must be a single
 * line — any newline inside a message would silently shift every entry after it out of step.
 *
 * All mutation is on the EDT inside a write action, as the platform requires for any document.
 */
class LogcatEditorView(private val project: Project) : LogcatView {

    private val factory = EditorFactory.getInstance()
    private val document = factory.createDocument("")

    /** A viewer, not an editor: the log is selectable and copyable, and cannot be typed into. */
    private val editor: EditorEx = factory.createViewer(document, project) as EditorEx

    /** Line *i* of the document is `entries[i]`. Kept exact; see the class comment. */
    private val entries = mutableListOf<LogcatEntry>()

    /** Set while this class is rewriting the document, so its own edits do not read as input. */
    private var updating = false

    override var onSelectionChanged: () -> Unit = {}

    override val component: JComponent get() = editor.component

    override val contentComponent: JComponent get() = editor.contentComponent

    init {
        configure()
        editor.caretModel.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = notifySelection()
            },
            this,
        )
        editor.selectionModel.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(event: SelectionEvent) = notifySelection()
            },
            this,
        )
    }

    private fun configure() {
        editor.setCaretEnabled(true)
        // Nothing in the gutter is meaningful for a log, and the line-marker area is the widest
        // thing that would be empty in a docked tool window.
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isCaretRowShown = true
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isUseSoftWraps = false
        }
        editor.setHorizontalScrollbarVisible(true)
        editor.setVerticalScrollbarVisible(true)
        // The editor's own menu is about editing code — Go To Declaration and the rest — none of
        // which applies here. Replaced by the panel's, which is about log lines.
        editor.setContextMenuGroupId(null)
        editor.setPlaceholder("Not streaming. Select a device and press Live.")
        editor.setShowPlaceholderWhenFocused(true)
    }

    private fun notifySelection() {
        if (updating) return
        onSelectionChanged()
    }

    // ---------------------------------------------------------------- reading

    override fun entries(): List<LogcatEntry> = entries.toList()

    override fun size(): Int = entries.size

    /**
     * The entries the developer is pointing at: the selected lines, or the caret's line.
     *
     * A partial selection counts the whole line — half a message is not a log record, and every
     * consumer of this (the details pane, the AI context, copy) is about records.
     */
    override fun selectedEntries(): List<LogcatEntry> {
        val range = selectedLineRange() ?: return emptyList()
        return entries.subList(range.first, range.last + 1).toList()
    }

    /** The single line the caret is on, or null when a run of lines is selected. */
    override fun caretIndex(): Int? {
        val range = selectedLineRange() ?: return null
        return range.first.takeIf { range.first == range.last }
    }

    private fun selectedLineRange(): IntRange? {
        if (entries.isEmpty()) return null
        val selection = editor.selectionModel
        val first: Int
        val last: Int
        if (selection.hasSelection()) {
            first = document.getLineNumber(selection.selectionStart)
            // A selection that ends exactly at a line start does not include that line: dragging
            // to the beginning of the next row should not silently add it.
            val end = selection.selectionEnd
            val endLine = document.getLineNumber(end)
            last = if (end == document.getLineStartOffset(endLine) && endLine > first) endLine - 1 else endLine
        } else {
            first = editor.caretModel.logicalPosition.line
            last = first
        }
        if (first !in entries.indices) return null
        return first..last.coerceAtMost(entries.lastIndex)
    }

    // ---------------------------------------------------------------- writing

    /** Replaces everything, for a filter change. */
    override fun setAll(replacement: List<LogcatEntry>, autoScroll: Boolean) = update {
        document.setText("")
        editor.markupModel.removeAllHighlighters()
        entries.clear()
        appendInternal(replacement)
        if (autoScroll) scrollToEnd()
    }

    /** Adds newly received lines. */
    override fun append(batch: List<LogcatEntry>, autoScroll: Boolean) {
        if (batch.isEmpty()) return
        update {
            appendInternal(batch)
            trim()
            if (autoScroll) scrollToEnd()
        }
    }

    override fun clear() = update {
        document.setText("")
        editor.markupModel.removeAllHighlighters()
        entries.clear()
    }

    /**
     * Scrolls without touching the caret.
     *
     * Moving the caret to the end would be the obvious way and would wipe the developer's
     * selection every time a line arrived — on a busy device, several times a second, which
     * makes selecting anything impossible while streaming.
     */
    override fun scrollToEnd() {
        val bottom = editor.offsetToXY(document.textLength).y
        val visible = editor.scrollingModel.visibleArea.height
        editor.scrollingModel.scrollVertically(max(0, bottom - visible + editor.lineHeight))
    }

    /**
     * Puts the caret where the developer right-clicked, unless they clicked inside a selection.
     *
     * Matches every list and editor in the IDE: a menu opened over a selection acts on it, and
     * one opened elsewhere acts on what is under the cursor.
     */
    override fun focusLineAt(point: java.awt.Point) {
        val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(point))
        val selection = editor.selectionModel
        if (selection.hasSelection() && offset in selection.selectionStart..selection.selectionEnd) return
        selection.removeSelection()
        editor.caretModel.moveToOffset(offset.coerceIn(0, document.textLength))
    }

    fun scrollTo(index: Int) {
        if (index !in entries.indices) return
        editor.scrollingModel.scrollTo(
            com.intellij.openapi.editor.LogicalPosition(index, 0),
            ScrollType.MAKE_VISIBLE,
        )
    }

    /**
     * Every document change, on the EDT, in a write action **and** a command.
     *
     * A write action alone is not enough and fails loudly:
     * `IncorrectOperationException: Must not change document outside command or
     * undo-transparent action`. Undo-transparent is the right kind here — a log is not
     * something the developer edits, so its arriving lines must never land on the undo stack,
     * where Ctrl+Z in a neighbouring editor could reach them.
     */
    private fun update(block: () -> Unit) {
        updating = true
        try {
            ApplicationManager.getApplication().runWriteAction {
                CommandProcessor.getInstance().runUndoTransparentAction(block)
            }
        } finally {
            updating = false
        }
    }

    private fun appendInternal(batch: List<LogcatEntry>) {
        val start = document.textLength
        val text = StringBuilder()
        val styles = mutableListOf<Styled>()

        batch.forEachIndexed { offset, entry ->
            val previous = if (offset == 0) entries.lastOrNull() else batch[offset - 1]
            val lineStart = start + text.length
            val line = LogcatLine.render(entry, previous)
            text.append(line.text).append('\n')
            line.spans.forEach { span ->
                styles += Styled(lineStart + span.from, lineStart + span.to, span.attributes)
            }
        }

        document.insertString(start, text)
        entries.addAll(batch)
        styles.forEach { style ->
            editor.markupModel.addRangeHighlighter(
                style.from,
                style.to,
                HighlighterLayer.SYNTAX,
                style.attributes,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
    }

    /**
     * Keeps the document bounded, dropping whole lines from the front.
     *
     * Highlighters inside the removed range are invalidated by the markup model itself, and the
     * ones after it move with the text, so the colours stay attached to their lines.
     */
    private fun trim() {
        if (entries.size <= VISIBLE_LIMIT) return
        val excess = entries.size - VISIBLE_LIMIT
        val cut = document.getLineEndOffset(excess - 1) + 1
        document.deleteString(0, cut.coerceAtMost(document.textLength))
        repeat(excess) { entries.removeAt(0) }
    }

    /**
     * Nothing to install: an editor already binds the platform's copy, and it copies exactly
     * what is selected on screen — which is the reason for using one.
     */
    override fun installCopyShortcut(copy: () -> Unit) = Unit

    override fun dispose() {
        // An editor that is not released is a leak the platform reports loudly at shutdown.
        factory.releaseEditor(editor)
    }

    private data class Styled(val from: Int, val to: Int, val attributes: TextAttributes)

    private companion object {
        const val VISIBLE_LIMIT = 10_000
    }
}

/**
 * One line of the log, as text plus the ranges worth colouring.
 *
 * Separated from the view so what a line looks like can be read — and tested — without an
 * editor, a project or a document in the way.
 */
internal object LogcatLine {

    /** Wide enough for the tags that matter, narrow enough to leave a docked window usable. */
    private const val TAG_WIDTH = 18
    private const val TIME_WIDTH = 12

    data class Span(val from: Int, val to: Int, val attributes: TextAttributes)

    data class Rendered(val text: String, val spans: List<Span>)

    fun render(entry: LogcatEntry, previous: LogcatEntry?): Rendered {
        // A banner — `--------- beginning of main` — has no fields to lay out.
        if (entry.timestamp.isEmpty()) {
            val text = entry.message.singleLine()
            return Rendered(text, listOf(Span(0, text.length, GRAYED)))
        }

        val spans = mutableListOf<Span>()
        val text = StringBuilder()

        // A continuation of the statement above repeats none of its labelling — not the time,
        // not the level, not the tag. Twelve lines of one JSON body carrying twelve copies of
        // all three is the columns winning over the content they are supposed to be labelling.
        val continuation = previous != null && LogcatGroup.continues(previous, entry)
        val time = entry.timestamp.substringAfter(' ', entry.timestamp).padEnd(TIME_WIDTH)
        if (continuation) {
            text.append(" ".repeat(time.length))
        } else {
            text.append(time)
            spans += Span(0, text.length, GRAYED)
        }
        text.append(' ')

        val levelAt = text.length
        if (continuation) {
            text.append(' ')
        } else {
            text.append(entry.level.code)
            spans += Span(levelAt, text.length, level(entry))
        }
        text.append("  ")

        // The tag is printed only when it changes: twenty rows of "ApiClient" say nothing, and
        // they sit exactly where the eye lands. The column is kept so the message does not move.
        val tagAt = text.length
        val tag = entry.tag.take(TAG_WIDTH)
        val repeated = continuation ||
            (previous != null && previous.tag == entry.tag && previous.pid == entry.pid)
        text.append(if (repeated) " ".repeat(tag.length) else tag)
        if (!repeated) spans += Span(tagAt, text.length, TAG)
        text.append(" ".repeat(TAG_WIDTH - tag.length))
        text.append("  ")

        val messageAt = text.length
        text.append(entry.message.singleLine())
        message(entry)?.let { spans += Span(messageAt, text.length, it) }

        return Rendered(text.toString(), spans)
    }

    /**
     * A record is one line, always.
     *
     * The document's line numbering *is* the index of the entry it belongs to, so a message
     * carrying a newline would shift every entry after it out of step with what is on screen —
     * silently, and permanently.
     */
    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

    private fun level(entry: LogcatEntry): TextAttributes = when (LogcatHighlighter.classify(entry)) {
        LogcatHighlighter.Highlight.CRASH -> CRASH_LEVEL
        LogcatHighlighter.Highlight.ANR -> ANR_LEVEL
        LogcatHighlighter.Highlight.ERROR -> ERROR_LEVEL
        LogcatHighlighter.Highlight.WARNING -> WARN_LEVEL
        LogcatHighlighter.Highlight.NONE -> levelOnly(entry.level)
    }

    private fun levelOnly(level: LogLevel): TextAttributes = when (level) {
        LogLevel.ASSERT, LogLevel.ERROR -> ERROR_LEVEL
        LogLevel.WARN -> WARN_LEVEL
        LogLevel.INFO -> INFO_LEVEL
        else -> GRAYED
    }

    /** Null leaves the message in the editor's own foreground: most lines are not special. */
    private fun message(entry: LogcatEntry): TextAttributes? = when (LogcatHighlighter.classify(entry)) {
        LogcatHighlighter.Highlight.CRASH -> CRASH_TEXT
        LogcatHighlighter.Highlight.ANR -> ANR_TEXT
        LogcatHighlighter.Highlight.ERROR -> ERROR_TEXT
        else -> null
    }

    private fun plain(colour: JBColor) = TextAttributes(colour, null, null, null, Font.PLAIN)

    private fun bold(colour: JBColor) = TextAttributes(colour, null, null, null, Font.BOLD)

    /** Light value first, dark second — a hex pair per role, not arithmetic. */
    @Suppress("MagicNumber")
    private object Palette {
        val CRASH = JBColor(0xC7222A, 0xFF6B6B)
        val ANR = JBColor(0x7B1FA2, 0xCE93D8)
        val ERROR = JBColor(0xB3261E, 0xF2857C)
        val WARNING = JBColor(0xB08000, 0xE0A030)
        val INFO = JBColor(0x3574F0, 0x548AF7)
        val DIM = JBColor(0x8C8C8C, 0x6E7377)
        val TAG = JBColor(0x6C707E, 0x9DA0A8)
    }

    private val GRAYED = plain(Palette.DIM)
    private val TAG = plain(Palette.TAG)
    private val CRASH_LEVEL = bold(Palette.CRASH)
    private val ANR_LEVEL = bold(Palette.ANR)
    private val ERROR_LEVEL = bold(Palette.ERROR)
    private val WARN_LEVEL = bold(Palette.WARNING)
    private val INFO_LEVEL = plain(Palette.INFO)
    private val CRASH_TEXT = bold(Palette.CRASH)
    private val ANR_TEXT = bold(Palette.ANR)
    private val ERROR_TEXT = plain(Palette.ERROR)
}
