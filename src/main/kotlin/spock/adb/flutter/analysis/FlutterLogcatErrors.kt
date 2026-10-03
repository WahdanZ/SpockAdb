package spock.adb.flutter.analysis

import spock.adb.diagnostics.LogProblemExtractor
import spock.adb.flutter.analysis.FlutterErrorReader.FlutterError

/**
 * Flutter framework errors as Flutter prints them to logcat while structured errors are off: a
 * profile build (no inspector), `--dart-define=flutter.inspector.structuredErrors=false`, a
 * no-debug launch or an IDE toggle (spike S7). `FlutterError.presentError` prints the first error
 * since the last hot reload in full and every later one as `Another exception was thrown: …`, each
 * line an info line under the `flutter` tag:
 *
 * ```
 * ══╡ EXCEPTION CAUGHT BY RENDERING LIBRARY ╞═════════════════════════…
 * The following assertion was thrown during layout:
 * A RenderFlex overflowed by 219 pixels on the right.
 *
 * The relevant error-causing widget was:
 *   Row
 *   Row:file:///…/lib/fixtures/layout.dart:30:17
 * …
 * ════════════════════════════════════════════════════════════════════…
 * Another exception was thrown: A RenderFlex overflowed by 219 pixels on the right.
 * ```
 *
 * That text is what a `Flutter.Error` carries as `renderedErrorText`, so a full report is read into
 * the same [FlutterError] — same description, headline and widget — and groups with
 * [FlutterErrorReader.summariseErrors] as one from the VM Service would. A one-line repeat carries
 * only the headline; repeats that read the same are grouped together, apart from the full report.
 *
 * Lines are parsed as [LogProblemExtractor] parses them, and text is cleaned as
 * [FlutterErrorReader] cleans it: no VM Service token, URL query or secret survives. Pure.
 */
object FlutterLogcatErrors {

    /**
     * Every framework error in [log] (`logcat -v threadtime`) printed by one of [pids], oldest first.
     *
     * @param toEpoch a logcat stamp as device epoch ms; a line it cannot place is skipped.
     */
    fun read(log: String, pids: Set<String>, toEpoch: (String) -> Long?): List<FlutterError> {
        val errors = mutableListOf<FlutterError>()
        var block: Block? = null

        // A report cut off by the window's end, or by a lost line, ends where the next one starts.
        fun close() {
            block?.read()?.let { errors += it }
            block = null
        }
        log.lineSequence()
            .mapNotNull(LogProblemExtractor::parse)
            .filter { it.tag == FLUTTER && it.pid in pids }
            .forEach { line ->
                val message = line.message
                val header = HEADER.find(message)
                val open = block?.takeIf { it.pid == line.pid }
                when {
                    header != null -> {
                        close()
                        block = Block(line.pid, toEpoch(line.time), header.groupValues[1])
                    }
                    open != null && FOOTER.matches(message.trim()) -> close()
                    message.startsWith(FlutterErrorReader.ANOTHER) -> {
                        if (open != null) close()
                        toEpoch(line.time)?.let { errors += another(it, message) }
                    }
                    open != null -> open.lines += message
                }
            }
        close()
        return errors
    }

    /** One full report, from its header to its footer. Null from [read] when its stamp could not be placed. */
    private class Block(val pid: String, val timestampMs: Long?, library: String) {
        val description = "Exception caught by ${library.trim().lowercase()}"
        val lines = mutableListOf<String>()

        fun read(): FlutterError? {
            val timestampMs = timestampMs ?: return null
            val thrownAt = lines.indexOfFirst { FlutterErrorReader.THROWN.containsMatchIn(it.trim()) }
            val thrown = lines.getOrNull(thrownAt)
                ?.let { FlutterErrorReader.THROWN.find(it.trim())?.groupValues?.get(1) }
            // The headline runs from the line after "The following … was thrown …:" to the first blank one.
            val headline = lines.drop(thrownAt + 1)
                .dropWhile { it.isBlank() }
                .takeWhile { it.isNotBlank() }
                .joinToString(" ")
                .let(FlutterErrorReader::oneLine)
                .takeIf { it.isNotBlank() }
            return FlutterError(
                timestampMs = timestampMs,
                description = description,
                headline = headline,
                thrown = thrown,
                widget = widget(),
                errorsSinceReload = -1,
                renderedText = FlutterErrorReader.clean(lines.joinToString("\n")),
                history = false,
                source = FlutterErrorReader.Source.LOGCAT,
            )
        }

        /** The location in the lines after "The relevant error-causing widget was:". */
        private fun widget(): String? {
            val at = lines.indexOfFirst { it.trim().startsWith(FlutterErrorReader.ERROR_CAUSING_WIDGET) }
            if (at < 0) return null
            return lines.drop(at + 1).take(WIDGET_LINES).firstNotNullOfOrNull { line ->
                WIDGET_LOCATION.find(line)?.destructured?.let { (name, file, position) ->
                    "$name at ${shortSourcePath(file)}:$position"
                }
            }
        }
    }

    /** `Another exception was thrown: Bad state: …`: the headline alone, as Flutter printed it. */
    private fun another(epochMs: Long, message: String) = FlutterError(
        timestampMs = epochMs,
        description = ANOTHER_DESCRIPTION,
        headline = FlutterErrorReader.oneLine(message.removePrefix(FlutterErrorReader.ANOTHER))
            .takeIf { it.isNotBlank() },
        thrown = null,
        widget = null,
        errorsSinceReload = -1,
        renderedText = FlutterErrorReader.clean(message),
        history = false,
        source = FlutterErrorReader.Source.LOGCAT,
    )

    /** The `logcat` filter that keeps only what Flutter prints: `flutter` at info and above. */
    const val FILTER = "flutter:I *:S"

    private const val FLUTTER = "flutter"

    /** How a one-line repeat reads in a summary: "Another exception was thrown: Bad state: …". */
    private const val ANOTHER_DESCRIPTION = "Another exception was thrown"

    /** Lines after the widget heading to look for its location in: the name may wrap onto its own. */
    private const val WIDGET_LINES = 3

    /** `══╡ EXCEPTION CAUGHT BY RENDERING LIBRARY ╞═══…`. */
    private val HEADER = Regex("""^══╡ EXCEPTION CAUGHT BY (.+?) ╞""")

    /** The rule that closes a report: nothing but `═`. */
    private val FOOTER = Regex("""^═{20,}$""")

    /**
     * `  Row:file:///…/lib/x.dart:30:17` (3.22, the name wrapped onto the line before) or
     * `  Row Row:file:///…` on one line.
     */
    private val WIDGET_LOCATION = Regex("""^\s*(?:\S+\s+)?([\w$<>,]+)[:\s]\s*((?:file|package):\S+?):(\d+:\d+)\s*$""")
}
