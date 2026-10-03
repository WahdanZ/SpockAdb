package spock.adb.diagnostics

import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.logcat.LogcatRedactor
import spock.adb.timeline.DeviceClock

/**
 * Turns a window of `logcat -v threadtime` into a handful of one-line problems.
 *
 * An agent handed 300 raw lines spends its context reading them and still has to decide what
 * matters. What it needs is the crash, the failed request, and the error that repeated forty
 * times — each once, with a count. The raw window stays one `android_get_logcat` call away.
 *
 * Pure: no device, so every rule is testable against a captured log.
 */
object LogProblemExtractor {

    data class Result(
        val problems: List<LikelyProblem>,
        val errorLines: Int,
        val warningLines: Int,
        /** Lines that belonged to the app, after attribution. */
        val appLines: Int,
    )

    /**
     * @param packageName null treats every line as the app's.
     * @param pids the app's live processes. A process that already crashed is gone from
     *   `pidof`, so its pid is recovered from the crash block itself.
     */
    fun extract(log: String, packageName: String?, pids: Collection<String>): Result {
        val lines = log.lineSequence().mapNotNull(::parse).toList()
        val relevant = attribute(lines, packageName, pids.toSet())

        val walk = Walk()
        relevant.forEach(walk::classify)
        walk.flushPending()

        return Result(
            problems = walk.found.values.map {
                LikelyProblem(
                    it.type,
                    it.severity,
                    it.summary,
                    it.count,
                    it.lastSeen,
                    SECTION,
                    seenAt = it.seen.takeLast(MAX_OCCURRENCES),
                )
            },
            errorLines = relevant.count { it.level in ERROR_LEVELS },
            warningLines = relevant.count { it.level == 'W' },
            appLines = relevant.size,
        )
    }

    /** One Flutter framework error printed by Flutter's console handler when structured errors are off. */
    data class FlutterFrameworkError(
        val summary: String,
        /** The first occurrence's full console block; null for grouped "Another exception" repeats. */
        val full: String?,
        val count: Int,
        val seenAt: List<String>,
    ) {
        val lastSeen: String? get() = seenAt.lastOrNull()
    }

    /**
     * Flutter framework errors from logcat, for the case where
     * `ext.flutter.inspector.structuredErrors` is off.
     *
     * Flutter prints the first error as a multi-line block and later repeats as
     * `Another exception was thrown: …`. The first block is kept in full (bounded), while
     * repeats are grouped by message. Only the app's attributed `flutter` lines participate.
     */
    fun flutterFrameworkErrors(
        log: String,
        packageName: String?,
        pids: Collection<String>,
    ): List<FlutterFrameworkError> {
        val lines = attribute(log.lineSequence().mapNotNull(::parse).toList(), packageName, pids.toSet())
            .filter { it.tag.equals(FLUTTER, ignoreCase = true) }
        if (lines.isEmpty()) return emptyList()

        val result = mutableListOf<FlutterFrameworkError>()
        val firstStart = lines.indexOfFirst { frameworkBlockStart(it.message) }
        if (firstStart >= 0) {
            val firstLines = mutableListOf<Line>()
            for (line in lines.drop(firstStart)) {
                if (firstLines.isNotEmpty() && line.message.startsWith(ANOTHER_EXCEPTION)) break
                firstLines += line
                if (firstLines.size >= MAX_FRAMEWORK_BLOCK_LINES) break
            }
            if (firstLines.isNotEmpty()) {
                val summary = firstLines.asSequence()
                    .map { it.message.trim() }
                    .firstOrNull { it.isNotBlank() && !DECORATIVE_ERROR_LINE.matches(it) }
                    ?: "Flutter framework error"
                val full = DiagnosticShell.clip(
                    firstLines.joinToString("\n") { it.message },
                    MAX_FRAMEWORK_BLOCK_CHARS,
                )
                result += FlutterFrameworkError(
                    summary = clipLine(redact(summary)),
                    full = redact(full),
                    count = 1,
                    seenAt = listOf(firstLines.first().time),
                )
            }
        }

        val repeats = linkedMapOf<String, MutableList<Line>>()
        lines.filter { it.message.startsWith(ANOTHER_EXCEPTION) }.forEach { line ->
            val message = line.message.substringAfter(ANOTHER_EXCEPTION).trim().ifBlank { "Flutter framework error" }
            repeats.getOrPut(normalise(message)) { mutableListOf() } += line
        }
        repeats.values.forEach { group ->
            val message = group.first().message.substringAfter(ANOTHER_EXCEPTION).trim()
            result += FlutterFrameworkError(
                summary = clipLine(redact(message.ifBlank { "Flutter framework error" })),
                full = null,
                count = group.size,
                seenAt = group.map { it.time }.takeLast(MAX_OCCURRENCES),
            )
        }
        return result
    }

    private fun frameworkBlockStart(message: String): Boolean =
        message.contains("EXCEPTION CAUGHT BY") ||
            message.startsWith("The following ") && message.contains(" was thrown")

    /** One parsed `threadtime` line. */
    data class Line(val time: String, val pid: String, val level: Char, val tag: String, val message: String)

    fun parse(raw: String): Line? {
        val groups = THREADTIME.matchEntire(raw.trimEnd())?.groupValues ?: return null
        return Line(
            time = groups[TIME],
            pid = groups[PID],
            level = groups[LEVEL].first(),
            tag = groups[TAG].trim(),
            message = groups[MESSAGE],
        )
    }

    /**
     * The app's lines, plus the system lines that are about it: the crash block AndroidRuntime
     * prints in the dying process, and ActivityManager's "ANR in".
     */
    private fun attribute(lines: List<Line>, packageName: String?, pids: Set<String>): List<Line> {
        if (packageName == null) return lines
        val crashedPids = lines
            .filter { it.tag == ANDROID_RUNTIME && it.message.startsWith("Process: $packageName,") }
            .map { it.pid }
            .toSet()
        val appPids = pids + crashedPids

        // ActivityManager prints an ANR over several lines — "ANR in", "PID:", "Reason:" — and
        // only the first names the app, so the rest are kept by following it.
        // Matched as a whole name: `com.example.app` must not claim `com.example.app.debug`'s ANR,
        // but does own its own `com.example.app:remote` process.
        val ownAnr = Regex("""ANR in ${Regex.escape(packageName)}(?=[\s:(]|$)""")
        var anrLinesLeft = 0
        var anrPid: String? = null
        return lines.filter { line ->
            val isSystemAnr = line.tag == ACTIVITY_MANAGER && line.message.contains("ANR in ")
            when {
                isSystemAnr && ownAnr.containsMatchIn(line.message) -> {
                    anrPid = line.pid
                    anrLinesLeft = ANR_FOLLOW_LINES
                    true
                }
                isSystemAnr -> {
                    anrLinesLeft = 0
                    line.pid in appPids
                }
                anrLinesLeft > 0 && line.tag == ACTIVITY_MANAGER && line.pid == anrPid -> {
                    anrLinesLeft--
                    true
                }
                else -> line.pid in appPids
            }
        }
    }

    /** One pass over the window. Holds what a line needs from the lines before it. */
    private class Walk {
        val found = linkedMapOf<String, Accumulator>()

        /** Threads whose `FATAL EXCEPTION` was seen and whose exception line has not been. */
        private val fatalThreads = mutableMapOf<String, String>()

        /** The ANR whose `Reason:` line is expected next, by the pid that printed it. */
        private val anrAwaitingReason = mutableMapOf<String, Accumulator>()

        /** Last `--> METHOD url` per URL, so a response line can name the method. */
        private val requestMethods = mutableMapOf<String, String>()

        /** A W/E line waiting to see whether an exception head follows it. */
        private var pending: Line? = null

        /**
         * The Flutter engine prints an unhandled Dart exception as one burst of `flutter` lines
         * with one timestamp: the head, Dart frames, and for a PlatformException the native
         * stack it carries. Everything after the head belongs to it, as `(pid, time)`.
         */
        private var flutterBurst: Pair<String, String>? = null

        /** Whether [flutterBurst] is a `MissingPluginException`'s. */
        private var missingPluginBurst = false

        /**
         * DartMessenger's "Uncaught exception in binary message listener" lines, already reported
         * on their own, oldest first, by pid: a `MissingPluginException` right after one takes its
         * place. Dart gets the empty replies in the order the handlers failed.
         */
        private val channelFailures = mutableMapOf<String, ArrayDeque<ChannelFailure>>()

        /** Lines classified so far: orders a channel failure against the lines after it. */
        private var lineNumber = 0L

        /** The [lineNumber] of [pending]. */
        private var pendingLineNumber = 0L

        /**
         * By pid, the [lineNumber] of its latest line that is neither part of a DartMessenger
         * failure nor of a `MissingPluginException`. A failure followed by one is not paired.
         */
        private val unrelatedLine = mutableMapOf<String, Long>()

        fun report(key: String, type: String, severity: Severity, summary: String, at: String): Accumulator {
            // Redacted whole, then clipped: a clip can cut a token short of the length its
            // redaction rule needs, and leave the part it kept in plain sight.
            val acc = found.getOrPut(key) { Accumulator(type, severity, clipLine(redact(summary))) }
            acc.seen += at
            return acc
        }

        fun flushPending() {
            pending?.let(::reportPlain)
            pending = null
        }

        fun classify(line: Line) {
            val message = line.message
            lineNumber++
            // A head first: a second exception in the same millisecond starts a burst of its own.
            val flutterHead = line.tag == FLUTTER && FLUTTER_UNHANDLED.containsMatchIn(message)
            val inBurst = !flutterHead && line.tag == FLUTTER && flutterBurst == (line.pid to line.time)
            if (!inBurst) flutterBurst = null
            if (!channelRelated(line, flutterHead, inBurst)) unrelatedLine[line.pid] = lineNumber
            when {
                flutterHead -> flutterUnhandled(line)
                inBurst -> Unit
                isStackFrame(message) -> stackFrame(line)
                line.tag == ANDROID_RUNTIME -> runtime(line)
                line.tag == ACTIVITY_MANAGER -> activityManager(line)
                else -> appLine(line)
            }
        }

        /** Part of a DartMessenger failure, or of the `MissingPluginException` it causes. */
        private fun channelRelated(line: Line, flutterHead: Boolean, inBurst: Boolean): Boolean =
            line.tag == DART_MESSENGER ||
                (flutterHead && unhandled(line.message).startsWith(MISSING_PLUGIN)) ||
                (inBurst && missingPluginBurst)

        /** `[ERROR:flutter/…] Unhandled Exception: X` → `X`. */
        private fun unhandled(message: String): String = message.substringAfter(FLUTTER_UNHANDLED_MARKER).trim()

        /**
         * `Unhandled Exception: …` from the Flutter engine. A missing plugin and an error a
         * platform channel returned are told apart: they are fixed on the native side, not in Dart.
         */
        private fun flutterUnhandled(line: Line) {
            flushPending()
            flutterBurst = line.pid to line.time
            val exception = unhandled(line.message)
            val missingPlugin = exception.startsWith(MISSING_PLUGIN)
            missingPluginBurst = missingPlugin
            if (missingPlugin && channelHandlerFailed(line, exception)) return
            val (type, summary) = when {
                missingPlugin ->
                    TYPE_FLUTTER_PLUGIN to "Flutter plugin not registered: " +
                        exception.removePrefix("MissingPluginException(").removeSuffix(")")
                exception.startsWith("PlatformException") ->
                    TYPE_FLUTTER_PLUGIN to "Platform channel returned an error: " + platformError(exception)
                else -> TYPE_EXCEPTION to "Unhandled Dart exception: $exception"
            }
            report("flutter:${normalise(exception)}", type, Severity.ERROR, summary, line.time)
        }

        /**
         * A checked exception thrown in a platform channel handler is caught by DartMessenger,
         * which logs "Uncaught exception in binary message listener" and replies with nothing —
         * and an empty reply is what Dart reads as a `MissingPluginException`, though the plugin
         * is there (spike S8). Nothing in the log ties the two together, so the pairing is an
         * inference, kept narrow and said so in the summary: same process, within
         * [CHANNEL_FAILURE_WINDOW_MS], and no other line of that process in between but other
         * such failures. True when [exception] was reported that way.
         */
        private fun channelHandlerFailed(line: Line, exception: String): Boolean {
            val queue = channelFailures[line.pid] ?: return false
            val unrelated = unrelatedLine[line.pid] ?: 0
            // Oldest first; one that expired, or that another line followed, explains nothing.
            while (queue.isNotEmpty()) {
                val oldest = queue.first()
                val elapsed = elapsedMs(oldest.time, line.time)
                if (oldest.lineNumber > unrelated && elapsed != null && elapsed in 0..CHANNEL_FAILURE_WINDOW_MS) break
                queue.removeFirst()
            }
            val failure = queue.removeFirstOrNull() ?: return false
            retract(failure.key, failure.time)
            val call = NO_IMPLEMENTATION.find(exception)
                ?.let { "for ${it.groupValues[1]} on ${it.groupValues[2]} " }
                .orEmpty()
            val head = "Platform channel handler ${call}threw ${failure.exception ?: "an exception"}"
            // Clipped before the note, so a long exception cannot cut off that this is inferred.
            val summary =
                DiagnosticShell.clip(redact(head), DiagnosticShell.MAX_VALUE_CHARS - INFERRED.length) + INFERRED
            report("channel:${normalise(head)}", TYPE_FLUTTER_PLUGIN, Severity.ERROR, summary, line.time)
            return true
        }

        /** Takes back the occurrence at [at] of a problem that turned out to be part of another. */
        private fun retract(key: String, at: String) {
            val acc = found[key] ?: return
            acc.seen.remove(at)
            if (acc.seen.isEmpty()) found.remove(key)
        }

        /** Keeps [key] for [channelHandlerFailed] when [listener] is DartMessenger's failure line. */
        private fun rememberChannelFailure(listener: Line?, at: Line, exception: String?, key: String) {
            if (listener?.tag == DART_MESSENGER && listener.message.startsWith(LISTENER_FAILURE)) {
                val queue = channelFailures.getOrPut(at.pid) { ArrayDeque() }
                queue.addLast(ChannelFailure(at.time, exception, key, pendingLineNumber))
                if (queue.size > MAX_CHANNEL_FAILURES) queue.removeFirst()
            }
        }

        /**
         * `PlatformException(CODE, message, details, stacktrace)` → `CODE — message`.
         *
         * The message may itself hold `, `, so it ends where the fields after it begin: `details`
         * is `null` in practice, and `stacktrace` is `null` or a native exception that runs on
         * past this line. Anything else is shown whole after the code rather than cut short.
         */
        private fun platformError(exception: String): String {
            val fields = exception.removePrefix("PlatformException(")
            val code = fields.substringBefore(", ")
            val rest = fields.substringAfter(", ", missingDelimiterValue = "")
            val message = PLATFORM_ERROR_TAIL.find(rest)?.let { rest.substring(0, it.range.first) }
                ?: rest.removeSuffix(")")
            return listOfNotNull(code.removeSuffix(")"), message.takeIf { it.isNotEmpty() && it != "null" })
                .joinToString(" — ")
        }

        /** Stack frames carry nothing a one-line summary can use, except a network root cause. */
        private fun stackFrame(line: Line) {
            val cause = CAUSED_BY.find(line.message)?.groupValues?.get(1) ?: return
            if (networkException(cause) != null && pending == null) {
                report("net:$cause", TYPE_NETWORK, Severity.ERROR, "Network failure: $cause", line.time)
            }
        }

        private fun runtime(line: Line) {
            flushPending()
            val message = line.message
            when {
                message.startsWith("FATAL EXCEPTION") ->
                    fatalThreads[line.pid] = message.substringAfter(':').trim()
                line.pid in fatalThreads && EXCEPTION_HEAD.matches(message) -> {
                    val thread = fatalThreads.remove(line.pid)
                    val summary = "App crashed: ${message.trim()}" +
                        thread?.takeIf { it.isNotBlank() }?.let { " (thread $it)" }.orEmpty()
                    report("crash:${normalise(message)}", TYPE_CRASH, Severity.ERROR, summary, line.time)
                }
            }
        }

        private fun activityManager(line: Line) {
            flushPending()
            val message = line.message
            if (message.contains("ANR in ")) {
                val summary = "App not responding: ${message.substringAfter("ANR in ").trim()}"
                anrAwaitingReason[line.pid] =
                    report("anr:${normalise(message)}", TYPE_ANR, Severity.ERROR, summary, line.time)
            } else if (message.startsWith("Reason:")) {
                // The reason arrives on the next line, and it is what makes an ANR actionable.
                val anr = anrAwaitingReason.remove(line.pid) ?: return
                if (!anr.summary.contains(" — ")) {
                    anr.summary = clipLine(anr.summary + " — " + redact(message.substringAfter("Reason:").trim()))
                }
            }
        }

        private fun appLine(line: Line) {
            val message = line.message
            OKHTTP_REQUEST.find(message)?.let { request ->
                requestMethods[stripQuery(request.groupValues[2])] = request.groupValues[1]
            }
            val http = httpFailure(message, requestMethods)
            when {
                http != null -> {
                    flushPending()
                    report(http.first, TYPE_NETWORK, http.second, http.third, line.time)
                }
                EXCEPTION_HEAD.matches(message) -> exception(line)
                else -> {
                    flushPending()
                    if (line.level in ERROR_LEVELS || line.level == 'W') {
                        pending = line
                        pendingLineNumber = lineNumber
                    }
                }
            }
        }

        /** `Log.e(tag, "Could not parse", e)` prints the message, then the exception: one problem. */
        private fun exception(line: Line) {
            val previous = pending?.takeIf { it.pid == line.pid && it.tag == line.tag }
            // The line it explains becomes part of this problem rather than one of its own.
            if (previous == null) flushPending() else pending = null
            val className = line.message.substringBefore(':').trim()
            val type = if (networkException(className) != null) TYPE_NETWORK else TYPE_EXCEPTION
            val severity = when {
                line.level in ERROR_LEVELS || previous?.level in ERROR_LEVELS -> Severity.ERROR
                else -> Severity.WARNING
            }
            val context = previous?.message?.takeIf { it.isNotBlank() }?.let { "$it — " }.orEmpty()
            val key = "exc:${line.tag}:${normalise(className + (previous?.message ?: ""))}"
            report(key, type, severity, "${line.tag}: ${(context + line.message).trim()}", line.time)
            rememberChannelFailure(previous, line, line.message.trim(), key)
        }

        private fun reportPlain(line: Line) {
            val severity = if (line.level in ERROR_LEVELS) Severity.ERROR else Severity.WARNING
            val type = if (line.tag == STRICT_MODE) TYPE_STRICT_MODE else TYPE_LOG
            val key = "log:${line.level}:${line.tag}:${normalise(line.message)}"
            report(key, type, severity, "${line.tag}: ${line.message.trim()}", line.time)
            rememberChannelFailure(line, line, null, key)
        }
    }

    /** A failed HTTP response, as `(key, severity, summary)`, or null. */
    private fun httpFailure(
        message: String,
        requestMethods: Map<String, String>,
    ): Triple<String, Severity, String>? {
        val okHttp = OKHTTP_RESPONSE.find(message)
        val generic = if (okHttp == null) GENERIC_HTTP.find(message) else null
        val (method, url, status) = when {
            okHttp != null -> {
                val (code, rawUrl) = okHttp.destructured
                val url = stripQuery(rawUrl)
                Triple(requestMethods[url], url, code)
            }
            generic != null -> {
                val (verb, rawUrl, code) = generic.destructured
                Triple(verb, stripQuery(rawUrl), code)
            }
            else -> return null
        }
        return http(method, url, status.toInt()).takeIf { status.toInt() >= HTTP_CLIENT_ERROR }
    }

    private fun http(method: String?, url: String, status: Int): Triple<String, Severity, String> {
        val severity = if (status >= HTTP_SERVER_ERROR) Severity.ERROR else Severity.WARNING
        val target = describeUrl(url)
        val summary = listOfNotNull(method, target).joinToString(" ") + " returned HTTP $status"
        return Triple("http:$method:$url:$status", severity, summary)
    }

    /** `/payment (api.example.com)`: the path is what a developer greps for, the host disambiguates. */
    internal fun describeUrl(url: String): String {
        val match = URL_PARTS.matchEntire(url) ?: return url
        // `user:pass@host` loses its `://` here, and with it the shape the redactor recognises.
        val host = match.groupValues[1].substringAfterLast('@')
        val path = match.groupValues[2].ifBlank { "/" }
        return "$path ($host)"
    }

    /** Query strings are where tokens and personal data ride along; the path is enough. */
    internal fun stripQuery(url: String): String = url.substringBefore('?').substringBefore('#')

    private fun networkException(className: String): String? =
        NETWORK_EXCEPTIONS.firstOrNull { className.endsWith(it) }

    private fun isStackFrame(message: String): Boolean {
        val trimmed = message.trimStart()
        return trimmed.startsWith("at ") || trimmed.startsWith("... ") || trimmed.startsWith("Caused by:")
    }

    /** Digits and hex ids differ between otherwise identical lines; they should count as one. */
    private fun normalise(message: String): String =
        message.replace(HEX_OR_NUMBER, "#").take(NORMALISED_KEY_CHARS)

    private fun clipLine(value: String): String = DiagnosticShell.clip(value.trim())

    private fun redact(value: String): String = LogcatRedactor.redact(value).text

    private class Accumulator(val type: String, val severity: Severity, summary: String) {
        var summary: String = summary

        /** When each occurrence was logged, in log order, so taking one back keeps the right last. */
        val seen = mutableListOf<String>()
        val count: Int get() = seen.size
        val lastSeen: String? get() = seen.lastOrNull()
    }

    /** A channel handler's exception as DartMessenger logged it, and the problem it became. */
    private class ChannelFailure(val time: String, val exception: String?, val key: String, val lineNumber: Long)

    /**
     * Milliseconds from one `threadtime` stamp to another, or null when either is unreadable.
     * The stamps carry no year; a leap year reads every one, and only short gaps are compared.
     */
    private fun elapsedMs(from: String, to: String): Long? {
        val start = DeviceClock.wallClockMillis(from, ANY_LEAP_YEAR) ?: return null
        return DeviceClock.wallClockMillis(to, ANY_LEAP_YEAR)?.minus(start)
    }

    const val SECTION = "logs"

    /** Occurrence times kept per problem, the latest: enough to pair a burst with what caused it. */
    private const val MAX_OCCURRENCES = 50

    const val TYPE_CRASH = "crash"
    const val TYPE_ANR = "anr"
    const val TYPE_NETWORK = "network"
    const val TYPE_EXCEPTION = "exception"
    const val TYPE_STRICT_MODE = "strictMode"
    const val TYPE_LOG = "log"

    /** A Flutter plugin that is missing, or a platform channel call that returned an error. */
    const val TYPE_FLUTTER_PLUGIN = "flutterPlugin"

    private const val ANDROID_RUNTIME = "AndroidRuntime"
    private const val ACTIVITY_MANAGER = "ActivityManager"
    private const val STRICT_MODE = "StrictMode"
    private const val FLUTTER = "flutter"
    private const val ANOTHER_EXCEPTION = "Another exception was thrown:"
    private const val MAX_FRAMEWORK_BLOCK_LINES = 40
    private const val MAX_FRAMEWORK_BLOCK_CHARS = 4_000
    private val DECORATIVE_ERROR_LINE = Regex("""^[═╡╞─\s]+.*[═╡╞─\s]*$""")

    /** What the engine prefixes, after `[ERROR:flutter/runtime/dart_vm_initializer.cc(41)] `. */
    private const val FLUTTER_UNHANDLED_MARKER = "Unhandled Exception:"
    private val FLUTTER_UNHANDLED = Regex("""^\[ERROR:flutter/[^\]]*] Unhandled Exception:""")

    private const val DART_MESSENGER = "DartMessenger"
    private const val LISTENER_FAILURE = "Uncaught exception in binary message listener"

    /**
     * How long after DartMessenger's failure a `MissingPluginException` still belongs to it. The
     * empty reply goes straight back to Dart, so on a device the two are milliseconds apart (7 ms
     * in the sample's capture); half a second leaves room for a loaded emulator and little for
     * an unrelated call to slip in.
     */
    private const val CHANNEL_FAILURE_WINDOW_MS = 500L

    /** DartMessenger failures kept per process while waiting for their `MissingPluginException`. */
    private const val MAX_CHANNEL_FAILURES = 8

    private const val MISSING_PLUGIN = "MissingPluginException"

    /** Ends a paired problem's summary: nothing in the log ties the two lines together. */
    private const val INFERRED = " (inferred from DartMessenger's log)"

    /** `MissingPluginException(No implementation found for method M on channel C)`. */
    private val NO_IMPLEMENTATION = Regex("""No implementation found for method (\S+) on channel (.+?)\)?$""")

    /** So that `02-29` parses. */
    private const val ANY_LEAP_YEAR = 2024

    /** What follows a PlatformException's message: `null` details, then `null)` or a native exception. */
    private val PLATFORM_ERROR_TAIL = Regex(""", null, (?:null\)|[\w.$]+(?::.*)?)$""")

    private const val HTTP_CLIENT_ERROR = 400
    private const val HTTP_SERVER_ERROR = 500
    private const val NORMALISED_KEY_CHARS = 160
    private const val ANR_FOLLOW_LINES = 6

    /** Groups of [THREADTIME]. */
    private const val TIME = 1
    private const val PID = 2
    private const val LEVEL = 3
    private const val TAG = 4
    private const val MESSAGE = 5

    private val ERROR_LEVELS = setOf('E', 'F', 'A')

    private val THREADTIME =
        Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")

    /** `java.lang.IllegalStateException: message`, `kotlin.KotlinNullPointerException`. */
    private val EXCEPTION_HEAD =
        Regex("""^\s*(?:[a-z][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Throwable)(?::.*)?$""")
    private val CAUSED_BY = Regex("""Caused by:\s*((?:[a-z][\w$]*\.)+[A-Z][\w$]*)""")

    /** OkHttp's `HttpLoggingInterceptor`: `--> POST https://…` and `<-- 500 Server Error https://… (12ms)`. */
    private val OKHTTP_REQUEST = Regex("""-->\s+(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(https?://\S+)""")
    private val OKHTTP_RESPONSE = Regex("""<--\s+(\d{3})\b.*?\s(https?://\S+)""")

    /** `POST https://host/path -> HTTP 500`, `GET /path failed: HTTP/1.1 404`. */
    private val GENERIC_HTTP =
        Regex("""\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(\S+).*?\bHTTP(?:/\d(?:\.\d)?)?[\s:]*(\d{3})\b""")

    private val URL_PARTS = Regex("""^https?://([^/\s]+)(/\S*)?$""")

    private val HEX_OR_NUMBER = Regex("""0x[0-9a-fA-F]+|\b[0-9a-fA-F]{8,}\b|\d+""")

    private val NETWORK_EXCEPTIONS = listOf(
        "UnknownHostException",
        "SocketTimeoutException",
        "ConnectException",
        "SSLHandshakeException",
        "SSLException",
        "NoRouteToHostException",
        "SocketException",
    )
}
