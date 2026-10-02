package spock.adb.flutter.analysis

import com.google.gson.JsonObject
import spock.adb.diagnostics.DiagnosticShell
import spock.adb.diagnostics.LikelyProblem
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.diagnostics.LogProblemExtractor
import spock.adb.flutter.vmservice.Redaction
import spock.adb.logcat.LogcatRedactor

/**
 * Reads `ext.dart.io.getHttpProfile` and reports the requests that failed.
 *
 * What it can see is narrow, and the report must not pretend otherwise:
 * - only traffic through dart:io's `HttpClient` (`package:http`'s default client and `dio` use
 *   it). `cupertino_http`, `cronet_http` and native SDKs bypass dart:io and never appear;
 * - only requests made after HTTP logging (`ext.dart.io.httpEnableTimelineLogging`) was switched
 *   on in that isolate, and hot restart switches it off again.
 *
 * Times in the profile are epoch **microseconds** on the device's clock. The profile's own
 * `timestamp` is what to pass as `updatedSince` next time; a later page repeats a request that
 * changed (it finished), so pages are merged by [Request.id]. A 4xx/5xx is failed as soon as its
 * status is in, so the same failed request can arrive on two pages, before and after its body is
 * read: anything kept per failure (a timeline row) is keyed by [Request.id] too.
 *
 * Pure: no device, no IDE.
 */
object HttpProfileReader {

    data class Request(
        val id: String,
        val method: String,
        /** VM Service tokens scrubbed, query and fragment stripped; host and path kept. */
        val url: String,
        val status: Int?,
        /** VM Service tokens scrubbed, and query strings stripped from any URL in it. */
        val error: String?,
        val startTimeUs: Long?,
        /**
         * When the response body was read to its end, or the request failed; null while in flight.
         * An app that reads the status and never drains the body leaves it null for good.
         */
        val endTimeUs: Long?,
    ) {
        val inFlight: Boolean get() = endTimeUs == null && error == null

        /**
         * An error, or a 4xx/5xx status. The status is final once the headers are in, so a request
         * whose body is still being read — or never will be — has failed when its status says so.
         */
        val failed: Boolean get() = error != null || (status != null && status >= HTTP_CLIENT_ERROR)

        val durationMs: Long?
            get() = if (startTimeUs != null && endTimeUs != null) (endTimeUs - startTimeUs) / MICROS_PER_MILLI else null

        /** `GET /status/500 (httpbin.org)`. */
        val target: String get() = "$method ${LogProblemExtractor.describeUrl(url)}"
    }

    data class Profile(
        /** The profile's `timestamp`, epoch µs: the `updatedSince` for the next page. */
        val timestampUs: Long?,
        val requests: List<Request>,
    )

    /** An unreadable profile reads as empty; an unreadable request is skipped. */
    fun read(profile: JsonObject?): Profile = Profile(
        timestampUs = FlutterJson.long(profile, "timestamp"),
        requests = FlutterJson.array(profile, "requests")
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull(::request)
            .orEmpty(),
    )

    /** [update] replaces requests of [previous] with the same id, and adds the rest. */
    fun merge(previous: List<Request>, update: List<Request>): List<Request> {
        val byId = linkedMapOf<String, Request>()
        (previous + update).forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    /**
     * One problem of type `network` (the type logcat's HTTP failures carry) per distinct failure:
     * WARNING for a 4xx, ERROR for a 5xx or a request that never got a response. A request in
     * flight with no status yet is not a failure.
     */
    fun problems(requests: List<Request>): List<LikelyProblem> {
        val groups = linkedMapOf<String, MutableList<Request>>()
        requests.filter { it.failed }.forEach { request ->
            groups.getOrPut("${request.method} ${request.url} ${request.status} ${errorKind(request)}") {
                mutableListOf()
            }.add(request)
        }
        return groups.values.map { failures ->
            val request = failures.first()
            LikelyProblem(
                type = FlutterProblemTypes.NETWORK,
                severity = severity(request),
                summary = summary(request),
                count = failures.size,
                section = FLUTTER_SECTION,
            )
        }
    }

    fun severity(request: Request): Severity =
        if (request.error == null && request.status != null && request.status < HTTP_SERVER_ERROR) {
            Severity.WARNING
        } else {
            Severity.ERROR
        }

    /** `GET /status/500 (httpbin.org) returned HTTP 500`, or `… failed: SocketException: …`. */
    fun summary(request: Request): String {
        val outcome = request.error?.let { "failed: $it" } ?: "returned HTTP ${request.status}"
        return DiagnosticShell.clip(LogcatRedactor.redact("${request.target} $outcome").text)
    }

    private fun request(json: JsonObject): Request? {
        val id = FlutterJson.string(json, "id") ?: return null
        val uri = FlutterJson.string(json, "uri") ?: return null
        val requestData = FlutterJson.obj(json, "request")
        val response = FlutterJson.obj(json, "response")
        // A request to the VM Service itself (an app or package that talks to it) carries the token.
        val error = (FlutterJson.string(requestData, "error") ?: FlutterJson.string(response, "error"))
            ?.let { stripUrlQueries(Redaction.scrub(it)) }
        return Request(
            id = id,
            method = FlutterJson.string(json, "method") ?: FlutterJson.string(requestData, "method") ?: "?",
            url = LogProblemExtractor.stripQuery(Redaction.scrub(uri)),
            status = FlutterJson.long(response, "statusCode")?.toInt(),
            error = error,
            startTimeUs = FlutterJson.long(json, "startTime"),
            endTimeUs = FlutterJson.long(response, "endTime")
                ?: FlutterJson.long(json, "endTime")?.takeIf { error != null },
        )
    }

    /** `SocketException: Failed host lookup: 'x'` → `SocketException`, so retries group. */
    private fun errorKind(request: Request): String = request.error?.substringBefore(':').orEmpty()

    private const val HTTP_CLIENT_ERROR = 400
    private const val HTTP_SERVER_ERROR = 500
    private const val MICROS_PER_MILLI = 1000
}
