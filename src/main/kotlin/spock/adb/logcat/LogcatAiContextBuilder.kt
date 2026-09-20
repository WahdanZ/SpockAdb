package spock.adb.logcat

/**
 * What to turn into AI context: the lines, and what the developer was looking at when they
 * asked.
 *
 * The panel hands over both the selection and the visible log; which of the two is used is
 * decided here rather than in the UI, so the rule — a selection wins, otherwise the filtered
 * view, never the raw buffer — is a property of the component that can be tested.
 */
data class LogcatAiRequest(
    val selected: List<LogcatEntry> = emptyList(),
    val visible: List<LogcatEntry> = emptyList(),
    val appPackage: String = "",
    val deviceLabel: String = "",
    val filter: LogcatFilter = LogcatFilter(),
    val maxLines: Int = LogcatAiContextBuilder.MAX_LINES,
    val maxBytes: Int = LogcatAiContextBuilder.MAX_BYTES,
)

/** The finished context, plus what had to be done to it, so the caller can say so. */
data class LogcatAiContext(
    val text: String,
    val lineCount: Int,
    val sourceLineCount: Int,
    val usedSelection: Boolean,
    val truncated: Boolean,
    val redactions: Int,
    val focus: LogcatAiContextBuilder.Focus?,
) {
    /** The context with the question in front of it, ready to be shown in the Assistant. */
    fun asPrompt(): String = "${LogcatAiContextBuilder.QUESTION}\n\n$text"

    /**
     * One line for the status bar: what was sent, and what was done to it.
     *
     * Here rather than in the panel because every one of these facts is a decision this object
     * made, and a developer who is about to send logs to a third party should be told that a
     * redaction happened without having to read the context to find out.
     */
    fun summary(): String = buildString {
        append(lineCount)
        append(if (usedSelection) " selected lines." else " visible lines.")
        focus?.let { append(" Centred on the latest ${it.label}.") }
        if (truncated) append(" Older lines were dropped.")
        if (redactions > 0) append(" $redactions credential-like value(s) redacted.")
    }
}

/**
 * Turns a screenful of logcat into something worth sending to a model.
 *
 * Pasting the raw buffer is the obvious thing and the wrong one: it is mostly framework chatter,
 * it blows the context window, it arrives without saying what was being filtered for, and on a
 * long enough log the crash the developer is asking about has scrolled out of the part that
 * fits. So this trims deliberately — most recent first, centred on a crash or ANR when there is
 * one, complete stack traces kept whole — and says in the text itself what it left out.
 *
 * Deliberately deterministic: every decision here is a line count, a marker table or a byte
 * budget. Nothing is scored or ranked, so the same screen always produces the same context and
 * the rules can be read off the tests.
 */
object LogcatAiContextBuilder {

    const val MAX_LINES = 300
    const val MAX_BYTES = 64 * 1024

    const val QUESTION: String =
        "Analyze these Android logs. Identify the likely root cause, point to the specific " +
            "evidence in the logs, and suggest the next debugging step."

    /** Lines kept after the crash, so the model sees what the app did next. */
    private const val TRAIL_AFTER_FOCUS = 40

    /** How far back the window may stretch to pick up the header of a half-included trace. */
    private const val TRACE_SLACK = 60

    /**
     * Longest single line kept whole.
     *
     * A log line has no length limit, and some are enormous: `nativeloader` prints the whole
     * shared-library path list — roughly 900 characters of `.so` names — on one line, and a
     * search for "json" matches it because one of them is `libjsoncpp.so`. Without a cap, one
     * such line spends more of the budget than fifty useful ones.
     */
    private const val MAX_LINE_CHARS = 600

    enum class Focus(val label: String) { CRASH("crash"), ANR("ANR") }

    /** One source line, with the count of identical lines that immediately followed it. */
    private data class Line(val entry: LogcatEntry, val repeats: Int)

    fun build(request: LogcatAiRequest): LogcatAiContext {
        val usedSelection = request.selected.isNotEmpty()
        val source = if (usedSelection) request.selected else request.visible
        val collapsed = collapseRepeats(source.filterNot { it.message.isBlank() })
        val focusIndex = collapsed.indexOfLast { isFocus(it.entry) }.takeIf { it >= 0 }
        val focus = focusIndex?.let { focusKind(collapsed[it].entry) }
        val window = window(collapsed, focusIndex, request)

        val body = window.lines.joinToString("\n") { render(it) }
        val redacted = LogcatRedactor.redact(body)
        val truncated = window.start > 0 || window.end < collapsed.size

        val header = LogcatAiHeader.render(
            request = request,
            usedSelection = usedSelection,
            shown = window.lines.size,
            sourceSize = source.size,
            truncated = truncated,
            redactions = redacted.count,
            focus = focus,
        )

        return LogcatAiContext(
            text = "$header\n\nRelevant logs:\n${redacted.text}",
            lineCount = window.lines.size,
            sourceLineCount = source.size,
            usedSelection = usedSelection,
            truncated = truncated,
            redactions = redacted.count,
            focus = focus,
        )
    }

    // ---------------------------------------------------------------- selection

    private data class Window(val lines: List<Line>, val start: Int, val end: Int)

    /**
     * The slice to send: the tail of the log, or the run around the last crash or ANR.
     *
     * The end is pinned before the start is, because what matters is which event the window is
     * about; how far back it reaches is then whatever the budget allows.
     */
    private fun window(lines: List<Line>, focusIndex: Int?, request: LogcatAiRequest): Window {
        if (lines.isEmpty()) return Window(emptyList(), 0, 0)

        val end = when (focusIndex) {
            null -> lines.size
            else -> minOf(lines.size, endOfFocusBlock(lines, focusIndex) + trail(lines, focusIndex, request.maxLines))
        }
        var start = maxOf(0, end - request.maxLines)
        start = keepTraceWhole(lines, start, request.maxLines)
        start = fitBytes(lines, start, end, request.maxBytes)
        start = dropOrphanFrames(lines, start, end)

        return Window(lines.subList(start, end), start, end)
    }

    /**
     * How far past the crash to keep reading.
     *
     * Never more than the budget can afford to spend *after* the event: a fixed trail with a
     * small budget pushed the window past the very crash it was supposed to be about, which is
     * the one failure mode that would make this feature worse than pasting the tail of the log.
     */
    private fun trail(lines: List<Line>, focusIndex: Int, maxLines: Int): Int {
        val block = endOfFocusBlock(lines, focusIndex) - focusIndex
        return minOf(TRAIL_AFTER_FOCUS, maxOf(0, maxLines - block) / 2)
    }

    /** A crash is a header plus its frames; cutting after the header would send half of it. */
    private fun endOfFocusBlock(lines: List<Line>, focusIndex: Int): Int {
        var end = focusIndex + 1
        while (end < lines.size && LogcatSignals.isStackFrame(lines[end].entry)) end++
        return end
    }

    /**
     * Stretches the window back to the exception header when it would otherwise start in the
     * middle of a trace — a list of frames with no exception above them tells a model almost
     * nothing, and costs the same number of tokens.
     */
    private fun keepTraceWhole(lines: List<Line>, start: Int, maxLines: Int): Int {
        if (start == 0 || !LogcatSignals.isStackFrame(lines[start].entry)) return start
        val header = LogcatStackTrace.headerIndex(lines.map { it.entry }, start) ?: return start
        val stretched = start - header
        return if (stretched <= minOf(TRACE_SLACK, maxLines)) header else start
    }

    /** Drops from the front — oldest first — until the rendered text is inside the budget. */
    private fun fitBytes(lines: List<Line>, from: Int, end: Int, maxBytes: Int): Int {
        var start = from
        var bytes = (start until end).sumOf { render(lines[it]).toByteArray().size + 1 }
        while (start < end && bytes > maxBytes) {
            bytes -= render(lines[start]).toByteArray().size + 1
            start++
        }
        return start
    }

    /** Whatever the byte budget left behind: frames whose header did not survive. */
    private fun dropOrphanFrames(lines: List<Line>, from: Int, end: Int): Int {
        var start = from
        while (start < end && start > 0 && LogcatSignals.isStackFrame(lines[start].entry)) start++
        return start
    }

    // ---------------------------------------------------------------- shaping

    /**
     * Folds a run of identical lines into one with a count.
     *
     * Empty-message records are dropped before this (see [build]): they cost a line of budget
     * and say nothing, and a device produces more of them than you would expect — every
     * `adb shell log` invocation ends with one.
     *
     * A retry loop or a per-frame warning can fill the whole budget with the same sentence, and
     * "this happened 400 times" is both shorter and more informative than 400 copies of it.
     */
    private fun collapseRepeats(entries: List<LogcatEntry>): List<Line> {
        fun isSameLine(a: LogcatEntry, b: LogcatEntry) =
            a.level == b.level && a.tag == b.tag && a.message == b.message

        val result = mutableListOf<Line>()
        entries.forEach { entry ->
            val last = result.lastOrNull()
            if (last != null && isSameLine(last.entry, entry)) {
                result[result.lastIndex] = last.copy(repeats = last.repeats + 1)
            } else {
                result += Line(entry, 0)
            }
        }
        return result
    }

    private fun render(line: Line): String {
        val entry = line.entry
        val head = when {
            entry.timestamp.isEmpty() -> entry.message
            else -> "${entry.timestamp} ${entry.pid}-${entry.tid} ${entry.level.code}/${entry.tag}: ${entry.message}"
        }
        val capped = when {
            head.length <= MAX_LINE_CHARS -> head
            else -> head.take(MAX_LINE_CHARS) + "… (+${head.length - MAX_LINE_CHARS} more characters)"
        }
        return if (line.repeats > 0) "$capped  (repeated ${line.repeats + 1}×)" else capped
    }

    private fun isFocus(entry: LogcatEntry): Boolean =
        LogcatSignals.isAnr(entry) || LogcatSignals.isCrash(entry)

    private fun focusKind(entry: LogcatEntry): Focus =
        if (LogcatSignals.isAnr(entry)) Focus.ANR else Focus.CRASH

    // ---------------------------------------------------------------- header
}

/**
 * The preamble: what the developer was looking at when they asked.
 *
 * Its own object because it answers a different question from the rest of the builder. The
 * builder decides *which lines* to send; this says *what that selection means* — and the two
 * have separate failure modes. A header that describes a filter which was not actually applied
 * is worse than no header at all: it does not merely omit context, it asserts a false one, and
 * the model has no way to check it against the lines.
 */
private object LogcatAiHeader {

    private const val HEADING = "Spock ADB — Logcat context"
    private const val BYTES_PER_KB = 1024

    @Suppress("LongParameterList")
    fun render(
        request: LogcatAiRequest,
        usedSelection: Boolean,
        shown: Int,
        sourceSize: Int,
        truncated: Boolean,
        redactions: Int,
        focus: LogcatAiContextBuilder.Focus?,
    ): String = buildString {
        appendLine(HEADING)
        appendLine()
        if (request.appPackage.isNotBlank()) appendLine("App: ${request.appPackage}")
        if (request.deviceLabel.isNotBlank()) appendLine("Device: ${request.deviceLabel}")
        appendLine("Scope: ${request.filter.scope.label}${scopeCaveat(request.filter)}")
        appendLine("Level: ${request.filter.minLevel.label}+")
        if (request.filter.intent != LogcatIntent.ALL) appendLine("Showing: ${request.filter.intent.label}")
        if (request.filter.query.isNotBlank()) appendLine("Search: ${request.filter.query}")
        appendLine("Source: ${if (usedSelection) "the selected lines" else "the filtered view"}")
        appendLine(lineCount(shown, sourceSize, truncated))
        focus?.let { appendLine("Focus: the window is centred on the most recent ${it.label}") }
        if (truncated) {
            appendLine(
                "Truncated: older lines were dropped to stay within ${request.maxLines} lines " +
                    "and ${request.maxBytes / BYTES_PER_KB} KB. Ask for more if the cause is not here.",
            )
        }
        if (redactions > 0) {
            appendLine(
                "Redacted: $redactions value(s) that looked like credentials were replaced " +
                    "with ${LogcatRedactor.PLACEHOLDER} before sending.",
            )
        }
    }.trimEnd()

    /**
     * Says so when the scope on the label is not the scope that was applied.
     *
     * "Scope: App" above lines the app did not write tells a model these are the app's own
     * logs, and a model that believes it will attribute system behaviour to the code under
     * debug. Taken from the filter so this can never disagree with the status bar.
     */
    private fun scopeCaveat(filter: LogcatFilter): String =
        filter.scopeCaveat()?.let { " — $it" }.orEmpty()

    /**
     * How many lines, and why that is fewer than the view held.
     *
     * Two different things reduce the count and they mean opposite things to a reader: folding
     * identical lines loses nothing, while truncating drops evidence. "12 of 16" said neither,
     * and read as silent loss.
     */
    private fun lineCount(shown: Int, sourceSize: Int, truncated: Boolean): String = when {
        shown == sourceSize -> "Lines: $shown"
        truncated -> "Lines: $shown of $sourceSize"
        else ->
            "Lines: $shown, folded from $sourceSize records (identical lines collapsed, " +
                "empty ones dropped) — nothing was truncated"
    }
}
