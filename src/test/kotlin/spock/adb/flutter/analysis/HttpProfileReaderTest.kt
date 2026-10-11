package spock.adb.flutter.analysis

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.LikelyProblem.Severity
import java.time.ZoneOffset

/**
 * Against `ext.dart.io.getHttpProfile` from the sample app's Network screen: a 500, a request to
 * a host that does not resolve, and a 200 still in flight when the profile was read.
 */
class HttpProfileReaderTest {

    private val profile = HttpProfileReader.read(FlutterFixtures.json("httpProfile.json"))

    @Test
    fun `each request reads with its outcome and duration`() {
        assertEquals(1790888286591993, profile.timestampUs)
        val (serverError, unknownHost, inFlight) = profile.requests

        assertEquals("GET", serverError.method)
        assertEquals("https://httpbin.org/status/500", serverError.url)
        assertEquals(500, serverError.status)
        assertNull(serverError.error)
        // From the request's start to the end of the response: 1790888259293550 − 1790888257230195 µs.
        assertEquals(2_063L, serverError.durationMs)
        assertTrue(serverError.failed)

        assertNull(unknownHost.status)
        assertTrue(unknownHost.error!!.startsWith("SocketException: Failed host lookup: 'no-such-host.spock.invalid'"))
        assertEquals(42L, unknownHost.durationMs)
        assertTrue(unknownHost.failed)

        assertTrue(inFlight.inFlight)
        assertFalse(inFlight.failed)
        assertNull(inFlight.durationMs)
    }

    @Test
    fun `failures are network problems, the server's an error`() {
        val problems = HttpProfileReader.problems(profile.requests)

        assertEquals(2, problems.size)
        assertTrue(problems.all { it.type == "network" && it.section == FLUTTER_SECTION })
        assertEquals(Severity.ERROR, problems[0].severity)
        assertEquals("GET /status/500 (httpbin.org) returned HTTP 500", problems[0].summary)
        assertEquals(Severity.ERROR, problems[1].severity)
        assertTrue(
            problems[1].summary.startsWith(
                "GET / (no-such-host.spock.invalid) failed: SocketException: Failed host lookup",
            ),
            problems[1].summary,
        )
    }

    @Test
    fun `a client error is a warning, repeats count once, and the query string is dropped`() {
        val requests = listOf(
            request("1", "https://api.example.com/users/42?token=abc#frag", status = 404),
            request("2", "https://api.example.com/users/42?token=xyz", status = 404),
            request("3", "https://user:pass@api.example.com/login", status = 302),
        )

        val problem = HttpProfileReader.problems(HttpProfileReader.read(profileOf(requests)).requests).single()

        assertEquals(Severity.WARNING, problem.severity)
        assertEquals("GET /users/42 (api.example.com) returned HTTP 404", problem.summary)
        assertEquals(2, problem.count)
        assertFalse(problem.summary.contains("token"))
    }

    @Test
    fun `a 404 whose body is never read has failed, though it never finishes`() {
        val undrained = """{"id": "9", "method": "GET", "uri": "https://httpbin.org/status/404", "startTime": 1000000,
            "response": {"startTime": 1400000, "statusCode": 404}}"""
        val waiting = """{"id": "10", "method": "GET", "uri": "https://httpbin.org/delay/9", "startTime": 1000000,
            "request": {"method": "GET"}}"""

        val (notFound, inFlight) = HttpProfileReader.read(profileOf(listOf(undrained, waiting))).requests

        assertTrue(notFound.inFlight)
        assertTrue(notFound.failed)
        assertFalse(inFlight.failed)
        val problem = HttpProfileReader.problems(listOf(notFound, inFlight)).single()
        assertEquals("GET /status/404 (httpbin.org) returned HTTP 404", problem.summary)
        val failure = FlutterTimelineMapper.httpFailure(notFound, FlutterTimelineMapper.Placement(ZoneOffset.UTC))!!
        assertEquals("The response body had not been read to its end.", failure.row.detail)
        // Failed already in flight, so a later page that finishes it maps again: keyed by request id.
        assertEquals("9", failure.requestId)
    }

    @Test
    fun `a request to the VM Service itself keeps its token out of the problem and the row`() {
        val token = "AbCdEf12_xYz"
        val vmService = """{"id": "7", "method": "GET", "uri": "http://127.0.0.1:52511/$token=/getVM",
            "startTime": 1000000, "endTime": 1500000,
            "response": {"statusCode": 403, "endTime": 1600000}}"""
        val refused = """{"id": "8", "method": "GET", "uri": "http://127.0.0.1:52511/$token=/ws",
            "startTime": 1000000, "endTime": 1500000,
            "request": {"error": "WebSocketException: http://127.0.0.1:52511/$token=/ws refused"}}"""

        val requests = HttpProfileReader.read(profileOf(listOf(vmService, refused))).requests
        val problems = HttpProfileReader.problems(requests)
        val placement = FlutterTimelineMapper.Placement(ZoneOffset.UTC)
        val rows = requests.mapNotNull { FlutterTimelineMapper.httpFailure(it, placement) }

        assertEquals("GET /<redacted>/getVM (127.0.0.1:52511) returned HTTP 403", problems[0].summary)
        (problems.map { it.summary } + rows.flatMap { listOf(it.row.title, it.row.detail) } + requests.map { it.url })
            .forEach { assertFalse(it.contains(token), it) }
    }

    @Test
    fun `a URL in an error message loses its query string`() {
        val failed = """{"id": "1", "method": "GET", "uri": "https://api.example.com/me?session=abc",
            "startTime": 1000000, "endTime": 1500000,
            "request": {"error": "HttpException: Connection closed, uri = https://api.example.com/me?session=abc"}}"""

        val request = HttpProfileReader.read(profileOf(listOf(failed))).requests.single()

        assertEquals("HttpException: Connection closed, uri = https://api.example.com/me", request.error)
        assertFalse(HttpProfileReader.problems(listOf(request)).single().summary.contains("session"))
    }

    @Test
    fun `a later page replaces the requests it repeats`() {
        val finished = request("-812882566", "https://httpbin.org/status/200", status = 200)
        val update = HttpProfileReader.read(profileOf(listOf(finished))).requests

        val merged = HttpProfileReader.merge(profile.requests, update)

        assertEquals(3, merged.size)
        assertEquals(200, merged.last().status)
        assertFalse(merged.last().inFlight)
    }

    @Test
    fun `an empty or malformed profile reads as no requests`() {
        assertTrue(HttpProfileReader.read(null).requests.isEmpty())
        assertTrue(HttpProfileReader.read(FlutterFixtures.json("listViews.json")).requests.isEmpty())
        val odd = JsonParser.parseString(
            """{"requests": [1, {"id": 5}, {"id": "a"}, {"id": "b", "uri": "https://x.test/", "response": 3}]}""",
        ).asJsonObject

        val requests = HttpProfileReader.read(odd).requests

        assertEquals(1, requests.size)
        assertTrue(requests.single().inFlight)
        assertEquals("?", requests.single().method)
        assertTrue(HttpProfileReader.problems(requests).isEmpty())
    }

    private fun request(id: String, uri: String, status: Int) =
        """{"id": "$id", "method": "GET", "uri": "$uri", "startTime": 1000000, "endTime": 1500000,
            "response": {"startTime": 1400000, "statusCode": $status, "endTime": 1600000}}"""

    private fun profileOf(requests: List<String>) =
        JsonParser.parseString("""{"timestamp": 2000000, "requests": [${requests.joinToString()}]}""").asJsonObject
}
