package spock.adb.flutter.analysis

import com.google.gson.JsonObject
import spock.adb.diagnostics.DiagnosticShell
import spock.adb.diagnostics.LikelyProblem
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.flutter.vmservice.Redaction
import spock.adb.logcat.LogcatRedactor
import java.time.ZoneId

/**
 * Turns `Flutter.Error` events into one-line problems.
 *
 * A `Flutter.Error` is the error's diagnostics tree as JSON, posted by the inspector when
 * structured errors are on (debug builds, by default). The first error after a hot reload
 * carries the whole rendered report in `renderedErrorText`; later ones only
 * `Another exception was thrown: <summary>` — the same split the console makes. The tree itself
 * is the same either way, so the summary is read from it: the `ErrorSummary` node is the
 * headline ("A RenderFlex overflowed by 219 pixels on the right."), the `ErrorDescription` before
 * it names what was thrown ("The following StateError was thrown …"), and the
 * "relevant error-causing widget" block gives a source location.
 *
 * Events DDS replayed on subscribe are kept apart in the count, so the report can say an error
 * happened before Spock connected rather than just now. Their absence proves nothing: frame
 * events evict old errors from DDS's replay buffer (spike S6).
 *
 * Text the app wrote is scrubbed of VM Service tokens and URL query strings, and redacted as
 * logcat is, as it is read: a [FlutterError] or [Group] is safe to show or keep wherever it goes.
 *
 * Pure: no device, no IDE.
 */
object FlutterErrorReader {

    /** One `Flutter.Error`, read. */
    data class FlutterError(
        val timestampMs: Long,
        /** `Exception caught by rendering library`. */
        val description: String,
        /** `A RenderFlex overflowed by 219 pixels on the right.`, on one line. */
        val headline: String?,
        /** What was thrown: `StateError`, `ArgumentError`, `assertion`; null when not stated. */
        val thrown: String?,
        /** `Row at lib/fixtures/layout.dart:30:17`, when the error names the widget. */
        val widget: String?,
        /**
         * As the app sends it: how many errors it reported **before** this one since the last hot
         * reload (0 for the first, which is the one that carries the full report); -1 when absent.
         */
        val errorsSinceReload: Int,
        val renderedText: String,
        val history: Boolean,
    ) {
        /** One line, before clipping: `Exception caught by gesture: Bad state: … — Row at lib/x.dart:3:5`. */
        val summary: String
            get() = listOfNotNull(
                listOfNotNull(description, headline).joinToString(": "),
                widget,
            ).joinToString(" — ")
    }

    /**
     * Errors from the same site that read the same, counted together. [problems][Result.problems]
     * are in the same order, one per group.
     */
    data class Group(
        val first: FlutterError,
        val last: FlutterError,
        val count: Int,
        /** How many of [count] happened before Spock connected. */
        val historyCount: Int,
    ) {
        /** Epoch ms on the device's clock, to line the group up with log lines of the same time. */
        val firstSeenMs: Long get() = first.timestampMs
        val lastSeenMs: Long get() = last.timestampMs
    }

    data class Result(
        val groups: List<Group>,
        val problems: List<LikelyProblem>,
        val liveCount: Int,
        val historyCount: Int,
        /**
         * How many errors the app has reported since its last hot reload, the latest included, or
         * null when it did not say. Read from the latest error, so it covers errors from before
         * Spock connected that DDS no longer replays.
         */
        val errorsSinceReload: Int?,
    )

    /** Every `Flutter.Error` in [events], in order; other kinds and unreadable payloads are skipped. */
    fun read(events: List<FlutterExtensionEvent>): List<FlutterError> =
        events.filter { it.kind == FlutterExtensionEvent.ERROR }.map(::read)

    fun read(event: FlutterExtensionEvent): FlutterError {
        val data = event.data
        val properties = FlutterJson.array(data, "properties")?.filterIsInstance<JsonObject>().orEmpty()
        val rendered = clean(FlutterJson.string(data, "renderedErrorText").orEmpty())
        val headline = properties.firstOrNull { FlutterJson.string(it, "type") == ERROR_SUMMARY }
            ?.let { FlutterJson.string(it, "description") }
            ?.let(::oneLine)
            ?.takeIf { it.isNotBlank() }
            ?: rendered.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(ANOTHER) }
                ?.removePrefix(ANOTHER)?.let(::oneLine)
        val thrown = properties.firstNotNullOfOrNull { property ->
            FlutterJson.string(property, "description")?.let { THROWN.find(it)?.groupValues?.get(1) }
        }
        return FlutterError(
            timestampMs = event.timestampMs,
            description = FlutterJson.string(data, "description")?.let(::oneLine)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_DESCRIPTION,
            headline = headline,
            thrown = thrown,
            widget = errorCausingWidget(properties),
            errorsSinceReload = FlutterJson.long(data, "errorsSinceReload")?.toInt() ?: -1,
            renderedText = rendered,
            history = event.history,
        )
    }

    /**
     * Groups repeats and turns each group into an ERROR [LikelyProblem] of type [TYPE].
     *
     * @param zone the **device's** time zone, for `lastSeen`: logcat stamps are the device's local
     *   time, so the host's zone would put the problem hours away from its log lines.
     */
    fun summarise(events: List<FlutterExtensionEvent>, zone: ZoneId): Result {
        val errors = read(events)
        val groups = linkedMapOf<String, MutableList<FlutterError>>()
        errors.forEach { groups.getOrPut(groupKey(it)) { mutableListOf() }.add(it) }
        val grouped = groups.values.map { list ->
            Group(first = list.first(), last = list.last(), count = list.size, historyCount = list.count { it.history })
        }
        return Result(
            groups = grouped,
            problems = grouped.map { problem(it, zone) },
            liveCount = errors.count { !it.history },
            historyCount = errors.count { it.history },
            errorsSinceReload = errors.lastOrNull()?.errorsSinceReload?.takeIf { it >= 0 }?.plus(1),
        )
    }

    private fun problem(group: Group, zone: ZoneId): LikelyProblem {
        val whenSeen = when (group.historyCount) {
            0 -> ""
            group.count -> " (before Spock connected)"
            else -> " (${group.historyCount} before Spock connected)"
        }
        return LikelyProblem(
            type = TYPE,
            severity = Severity.ERROR,
            // Clipped before the note, so a long error cannot cut off when it happened.
            summary = DiagnosticShell.clip(
                LogcatRedactor.redact(group.first.summary).text,
                DiagnosticShell.MAX_VALUE_CHARS - whenSeen.length,
            ) + whenSeen,
            count = group.count,
            lastSeen = logcatTime(group.last.timestampMs, zone),
            section = FLUTTER_SECTION,
        )
    }

    /** The widget block's first line: `Row Row:file:///…/lib/fixtures/layout.dart:30:17`. */
    private fun errorCausingWidget(properties: List<JsonObject>): String? {
        val block = properties.firstOrNull {
            FlutterJson.string(it, "name")?.startsWith(ERROR_CAUSING_WIDGET) == true
        } ?: return null
        val line = FlutterJson.array(block, "children")
            ?.filterIsInstance<JsonObject>()
            ?.firstNotNullOfOrNull { FlutterJson.string(it, "description") }
            ?: return null
        val match = WIDGET_LOCATION.find(line) ?: return null
        val (name, file, position) = match.destructured
        return "$name at ${shortSourcePath(file)}:$position"
    }

    /**
     * Digits in the headline differ between repeats of one error (overflow pixels, an id), so they
     * count as one. The widget's `file:line:col` is the site and is kept as it is: the same
     * overflow in two rows of a layout is two problems to fix.
     */
    private fun groupKey(error: FlutterError): String =
        listOf(error.description, error.headline?.replace(DIGITS, "#"), error.widget).joinToString("|")

    /** VM Service tokens, then URL queries, then what logcat's redaction takes (secrets, e-mails). */
    private fun clean(text: String): String = LogcatRedactor.redact(stripUrlQueries(Redaction.scrub(text))).text

    /** A multi-line summary (an assertion's message, a wrapped description) on one line. */
    private fun oneLine(text: String): String = clean(text).replace(WHITESPACE, " ").trim()

    /** The problem type a [LikelyProblem] from a `Flutter.Error` carries. */
    const val TYPE = FlutterProblemTypes.FLUTTER_ERROR

    private const val ERROR_SUMMARY = "ErrorSummary"
    private const val ANOTHER = "Another exception was thrown:"
    private const val ERROR_CAUSING_WIDGET = "The relevant error-causing widget was"
    private const val DEFAULT_DESCRIPTION = "Flutter error"

    private val THROWN = Regex("""^The following (.+?) was thrown\b""")
    private val WIDGET_LOCATION = Regex("""^(\S+)\s.*?((?:file|package):\S+?):(\d+:\d+)\s*$""")
    private val DIGITS = Regex("""\d+""")
    private val WHITESPACE = Regex("""\s+""")
}
