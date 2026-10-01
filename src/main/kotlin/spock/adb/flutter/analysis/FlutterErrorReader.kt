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
 * Pure: no device, no IDE.
 */
object FlutterErrorReader {

    /** One `Flutter.Error`, read. */
    data class FlutterError(
        val timestampMs: Long,
        /** `Exception caught by rendering library`. */
        val description: String,
        /** `A RenderFlex overflowed by 219 pixels on the right.` */
        val headline: String?,
        /** What was thrown: `StateError`, `ArgumentError`, `assertion`; null when not stated. */
        val thrown: String?,
        /** `Row at lib/fixtures/layout.dart:30:17`, when the error names the widget. */
        val widget: String?,
        /** The app's count of errors before this one since the last hot reload; -1 when absent. */
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

    /** Errors that read the same, counted together. */
    data class Group(
        val first: FlutterError,
        val last: FlutterError,
        val count: Int,
        /** How many of [count] happened before Spock connected. */
        val historyCount: Int,
    )

    data class Result(
        val groups: List<Group>,
        val problems: List<LikelyProblem>,
        val liveCount: Int,
        val historyCount: Int,
        /** From the latest error: how many the app had seen since its last hot reload, or null. */
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
            ?.let(::clean)
            ?.takeIf { it.isNotBlank() }
            ?: rendered.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(ANOTHER) }
                ?.removePrefix(ANOTHER)?.trim()
        val thrown = properties.firstNotNullOfOrNull { property ->
            FlutterJson.string(property, "description")?.let { THROWN.find(it)?.groupValues?.get(1) }
        }
        return FlutterError(
            timestampMs = event.timestampMs,
            description = FlutterJson.string(data, "description")?.let(::clean)?.takeIf { it.isNotBlank() }
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
     * @param zone the device's time zone, for `lastSeen`.
     */
    fun summarise(events: List<FlutterExtensionEvent>, zone: ZoneId = ZoneId.systemDefault()): Result {
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
            errorsSinceReload = errors.lastOrNull()?.errorsSinceReload?.takeIf { it >= 0 },
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

    /** Digits differ between otherwise identical errors (overflow pixels, ids); they count as one. */
    private fun groupKey(error: FlutterError): String =
        (error.description + "|" + error.headline + "|" + error.widget).replace(DIGITS, "#")

    private fun clean(text: String): String = Redaction.scrub(text)

    /** The problem type a [LikelyProblem] from a `Flutter.Error` carries. */
    const val TYPE = "flutterError"

    private const val ERROR_SUMMARY = "ErrorSummary"
    private const val ANOTHER = "Another exception was thrown:"
    private const val ERROR_CAUSING_WIDGET = "The relevant error-causing widget was"
    private const val DEFAULT_DESCRIPTION = "Flutter error"

    private val THROWN = Regex("""^The following (.+?) was thrown\b""")
    private val WIDGET_LOCATION = Regex("""^(\S+)\s.*?((?:file|package):\S+?):(\d+:\d+)\s*$""")
    private val DIGITS = Regex("""\d+""")
}
