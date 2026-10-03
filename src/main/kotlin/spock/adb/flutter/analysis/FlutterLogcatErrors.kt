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
 * only the headline ([FlutterError.repeat]): it joins the earlier full report that reads the same,
 * or else the repeats that read the same.
 *
 * A profile build has no diagnostics tree to print: its first error is
 * `debugPrintStack(label: exception.toString())` instead — the exception's text, then its stack as
 * `#0 …` frames:
 *
 * ```
 * Bad state: Sample error thrown in a tap handler
 * #0      _ErrorsScreenState._throwInTapHandler (package:spock_flutter_sample/fixtures/errors.dart:88)
 * #1      _InkResponseState.handleTap (package:flutter/src/material/ink_well.dart:1170)
 * …
 * ```
 *
 * Any print followed by a stack reads the same, so that shape is taken only before the pid's first
 * `Another exception was thrown` or full report, while Flutter has reported nothing yet.
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
        val reader = Reader(toEpoch)
        log.lineSequence()
            .mapNotNull(LogProblemExtractor::parse)
            .filter { it.tag == FLUTTER && it.pid in pids }
            .forEach { reader.accept(it.pid, it.time, it.message) }
        return reader.finish()
    }

    /** One pass over the `flutter` lines, oldest first: at most one report or stack open at a time. */
    private class Reader(private val toEpoch: (String) -> Long?) {
        val errors = mutableListOf<FlutterError>()
        private var block: Block? = null
        private var stack: ProfileStack? = null

        /** Per pid, the line before this one: a profile error's text, when a stack follows it. */
        private val previous = HashMap<String, Line>()

        /** Pids Flutter has reported an error for: a stack after that is not its first error. */
        private val reported = HashSet<String>()

        fun accept(pid: String, time: String, message: String) {
            val before = previous.put(pid, Line(time, message))
            val header = HEADER.find(message)
            val open = block?.takeIf { it.pid == pid }
            val frames = stack?.takeIf { it.pid == pid }
            when {
                header != null -> {
                    close()
                    reported += pid
                    block = Block(pid, toEpoch(time), header.groupValues[1])
                }
                open != null && FOOTER.matches(message.trim()) -> close()
                message.startsWith(FlutterErrorReader.ANOTHER) -> {
                    close()
                    reported += pid
                    toEpoch(time)?.let { errors += another(it, message) }
                }
                open != null -> open.lines += message
                frames != null && FRAME.containsMatchIn(message) -> frames.lines += message
                FIRST_FRAME.containsMatchIn(message) && pid !in reported && before?.isLabel == true -> {
                    close()
                    reported += pid
                    stack = ProfileStack(toEpoch(before.time), before.message, pid).also { it.lines += message }
                }
                frames != null -> close()
            }
        }

        fun finish(): List<FlutterError> {
            close()
            return errors
        }

        // A report cut off by the window's end, or by a lost line, ends where the next one starts.
        private fun close() {
            block?.read()?.let { errors += it }
            block = null
            stack?.read()?.let { errors += it }
            stack = null
        }
    }

    private class Line(val time: String, val message: String) {
        /** Could be an exception's text: not blank, not a frame, not a report's rule. */
        val isLabel: Boolean
            get() = message.isNotBlank() && !FRAME.containsMatchIn(message) && !FOOTER.matches(message.trim()) &&
                !message.startsWith(FlutterErrorReader.ANOTHER)
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

    /**
     * A profile build's first error: the exception's text, stamped when it was printed, then its
     * stack. Null from [read] when the stamp could not be placed.
     */
    private class ProfileStack(val timestampMs: Long?, private val label: String, val pid: String) {
        val lines = mutableListOf<String>()

        fun read(): FlutterError? {
            val timestampMs = timestampMs ?: return null
            return FlutterError(
                timestampMs = timestampMs,
                description = PROFILE_DESCRIPTION,
                headline = FlutterErrorReader.oneLine(label).takeIf { it.isNotBlank() },
                thrown = null,
                widget = null,
                errorsSinceReload = -1,
                renderedText = FlutterErrorReader.clean((listOf(label) + lines).joinToString("\n")),
                history = false,
                source = FlutterErrorReader.Source.LOGCAT,
            )
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
        repeat = true,
    )

    /** The `logcat` filter that keeps only what Flutter prints: `flutter` at info and above. */
    const val FILTER = "flutter:I *:S"

    private const val FLUTTER = "flutter"

    /** How a one-line repeat reads in a summary: "Another exception was thrown: Bad state: …". */
    private const val ANOTHER_DESCRIPTION = "Another exception was thrown"

    /** A profile build's error: Flutter caught it, and printed only what it says and its stack. */
    private const val PROFILE_DESCRIPTION = "Exception caught by Flutter"

    /** Lines after the widget heading to look for its location in: the name may wrap onto its own. */
    private const val WIDGET_LINES = 3

    /** `══╡ EXCEPTION CAUGHT BY RENDERING LIBRARY ╞═══…`. */
    private val HEADER = Regex("""^══╡ EXCEPTION CAUGHT BY (.+?) ╞""")

    /** The rule that closes a report: nothing but `═`. */
    private val FOOTER = Regex("""^═{20,}$""")

    /** A frame as Dart prints one, `#12     main (package:…)`, or the frames Flutter left out of a stack. */
    private val FRAME = Regex("""^\s*(?:#\d+\s|\(elided \d+ frames?\b|\.\.\.\s*$)""")

    /** A stack's first frame. */
    private val FIRST_FRAME = Regex("""^#0\s""")

    /**
     * `  Row:file:///…/lib/x.dart:30:17` (3.22, the name wrapped onto the line before) or
     * `  Row Row:file:///…` on one line.
     */
    private val WIDGET_LOCATION = Regex("""^\s*(?:\S+\s+)?([\w$<>,]+)[:\s]\s*((?:file|package):\S+?):(\d+:\d+)\s*$""")
}
