package spock.adb.diagnostics

import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.logcat.LogcatRedactor

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
                LikelyProblem(it.type, it.severity, it.summary, it.count, it.lastSeen, SECTION)
            },
            errorLines = relevant.count { it.level in ERROR_LEVELS },
            warningLines = relevant.count { it.level == 'W' },
            appLines = relevant.size,
        )
    }

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
        var anrLinesLeft = 0
        var anrPid: String? = null
        return lines.filter { line ->
            val isSystemAnr = line.tag == ACTIVITY_MANAGER && line.message.contains("ANR in ")
            when {
                isSystemAnr && line.message.contains("ANR in $packageName") -> {
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

        fun report(key: String, type: String, severity: Severity, summary: String, at: String): Accumulator {
            val acc = found.getOrPut(key) { Accumulator(type, severity, clipLine(redact(summary))) }
            acc.count++
            acc.lastSeen = at
            return acc
        }

        fun flushPending() {
            pending?.let(::reportPlain)
            pending = null
        }

        fun classify(line: Line) {
            val message = line.message
            when {
                isStackFrame(message) -> stackFrame(line)
                line.tag == ANDROID_RUNTIME -> runtime(line)
                line.tag == ACTIVITY_MANAGER -> activityManager(line)
                else -> appLine(line)
            }
        }

        /** Stack frames carry nothing a one-line summary can use, except a network root cause. */
        private fun stackFrame(line: Line) {
            val cause = CAUSED_BY.find(line.message)?.groupValues?.get(1) ?: return
            if (networkException(cause) != null && pending == null) {
                report("net:$cause", TYPE_NETWORK, Severity.ERROR, "Network failure: ${clipLine(cause)}", line.time)
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
                    val summary = "App crashed: ${clipLine(message)}" +
                        thread?.takeIf { it.isNotBlank() }?.let { " (thread $it)" }.orEmpty()
                    report("crash:${normalise(message)}", TYPE_CRASH, Severity.ERROR, summary, line.time)
                }
            }
        }

        private fun activityManager(line: Line) {
            flushPending()
            val message = line.message
            if (message.contains("ANR in ")) {
                val summary = "App not responding: ${clipLine(message.substringAfter("ANR in "))}"
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
                    if (line.level in ERROR_LEVELS || line.level == 'W') pending = line
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
            report(
                "exc:${line.tag}:${normalise(className + (previous?.message ?: ""))}",
                type,
                severity,
                "${line.tag}: ${clipLine(context + line.message)}",
                line.time,
            )
        }

        private fun reportPlain(line: Line) {
            val severity = if (line.level in ERROR_LEVELS) Severity.ERROR else Severity.WARNING
            val type = if (line.tag == STRICT_MODE) TYPE_STRICT_MODE else TYPE_LOG
            report(
                "log:${line.level}:${line.tag}:${normalise(line.message)}",
                type,
                severity,
                "${line.tag}: ${clipLine(line.message)}",
                line.time,
            )
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
        return Triple("http:$method:$url:$status", severity, clipLine(summary))
    }

    /** `/payment (api.example.com)`: the path is what a developer greps for, the host disambiguates. */
    private fun describeUrl(url: String): String {
        val match = URL_PARTS.matchEntire(url) ?: return url
        val host = match.groupValues[1]
        val path = match.groupValues[2].ifBlank { "/" }
        return "$path ($host)"
    }

    /** Query strings are where tokens and personal data ride along; the path is enough. */
    private fun stripQuery(url: String): String = url.substringBefore('?').substringBefore('#')

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
        var count = 0
        var lastSeen: String? = null
    }

    const val SECTION = "logs"

    const val TYPE_CRASH = "crash"
    const val TYPE_ANR = "anr"
    const val TYPE_NETWORK = "network"
    const val TYPE_EXCEPTION = "exception"
    const val TYPE_STRICT_MODE = "strictMode"
    const val TYPE_LOG = "log"

    private const val ANDROID_RUNTIME = "AndroidRuntime"
    private const val ACTIVITY_MANAGER = "ActivityManager"
    private const val STRICT_MODE = "StrictMode"

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
