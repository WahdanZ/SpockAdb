package spock.adb.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Calls every read-only tool against a real device, through a real running server.
 *
 * This exists because the failure mode it looks for cannot be reached any other way.
 * Android Studio supplies an `IDevice` whose implementation leaves nineteen methods
 * unimplemented, and a tool that calls one of them returns an error to every caller while
 * compiling cleanly and passing its unit tests — a test that builds its own
 * `AndroidDebugBridge` gets stock ddmlib, where those methods work. `android_take_screenshot`
 * shipped broken that way. [spock.adb.StubbedIDeviceApiTest] now blocks that specific class
 * of bug statically; this covers the rest, where a tool is wired up wrongly, parses output
 * that has changed shape, or fails only against a live device.
 *
 * Opt-in, because it needs both an IDE running the server and a device attached:
 *
 * ```
 * SPOCK_MCP_URL=http://127.0.0.1:58649/mcp SPOCK_MCP_TOKEN=… ./gradlew test --tests '*McpSmokeTest'
 * ```
 *
 * Both values come from `Tools → Spock ADB → Copy MCP Client Configuration (HTTP)`. Without
 * them the live checks skip, so an ordinary `./gradlew test` is unaffected — but
 * [every read-only tool is covered] still runs, so a new tool cannot be added without
 * deciding how it is smoke-tested.
 */
class McpSmokeTest {

    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    @Test
    fun `every read-only tool is covered`() {
        // Runs with or without a device: it compares the registry against the tables below,
        // so adding a read-only tool without classifying it fails here rather than silently
        // going untested.
        val readOnly = ToolRegistry.bySafety(ToolSafety.READ_ONLY).map { it.name }.toSet()
        val covered = PROBES.keys + QUERIES.keys

        val untested = readOnly - covered
        assertTrue(untested.isEmpty()) {
            "These read-only tools are not smoke-tested: ${untested.sorted()}. Add each to " +
                "PROBES (must succeed) or QUERIES (may report no match)."
        }

        val stale = covered - readOnly
        assertTrue(stale.isEmpty()) {
            "These tools are smoke-tested but are no longer read-only: ${stale.sorted()}."
        }
    }

    @Test
    fun `the running server is this build`() {
        val endpoint = liveEndpoint() ?: return

        // The server runs inside an IDE that was launched at some point in the past. When
        // this build defines a tool the server has never heard of, every probe for it fails
        // with "Unknown tool", which reads like a broken tool rather than a stale IDE.
        val missing = ToolRegistry.all().map { it.name }.toSet() - endpoint.servedTools
        assertTrue(missing.isEmpty()) {
            "The MCP server at ${endpoint.url} does not serve ${missing.sorted()}. It is " +
                "running an older build — restart the IDE (./gradlew runIde) to pick up this one."
        }
    }

    @Test
    fun `every probe tool succeeds against a real device`() {
        val endpoint = liveEndpoint() ?: return
        assumeTrue(endpoint.devices.isNotEmpty(), "no device attached to ${endpoint.url}")

        val failures = PROBES.servedBy(endpoint).mapNotNull { (tool, arguments) ->
            val result = endpoint.call(tool, arguments)
            when {
                result.isError -> "$tool -> ${result.text}"
                result.isEmptyResult -> "$tool -> returned no content"
                else -> null
            }
        }

        assertTrue(failures.isEmpty()) {
            "These read-only tools failed against ${endpoint.devices.first()}:\n" +
                failures.joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `query tools report no match rather than failing`() {
        val endpoint = liveEndpoint() ?: return
        assumeTrue(endpoint.devices.isNotEmpty(), "no device attached to ${endpoint.url}")

        // Nothing on screen can match this, so each tool must give its own "not found"
        // verdict. An infrastructure failure surfaces here as an unrecognisable message.
        val failures = QUERIES.servedBy(endpoint).mapNotNull { (tool, arguments) ->
            val result = endpoint.call(tool, arguments)
            val text = result.text
            when {
                result.isEmptyResult -> "$tool -> returned no content"
                !result.isError -> null
                NO_MATCH_VERDICT.containsMatchIn(text) -> null
                else -> "$tool -> failed instead of reporting no match: $text"
            }
        }

        assertTrue(failures.isEmpty()) {
            "These read-only tools broke on a selector that simply does not match:\n" +
                failures.joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `no tool result carries an unimplemented-API failure`() {
        val endpoint = liveEndpoint() ?: return
        assumeTrue(endpoint.devices.isNotEmpty(), "no device attached to ${endpoint.url}")

        // The sentence Android Studio's IDevice returns from every method it does not
        // implement. It reached MCP clients verbatim for the whole life of the screenshot
        // tool, so it is worth asserting on by name.
        val offenders = (PROBES + QUERIES).servedBy(endpoint)
            .filter { (tool, arguments) -> endpoint.call(tool, arguments).text.contains(UNIMPLEMENTED) }
            .keys

        assertTrue(offenders.isEmpty()) {
            "These tools returned \"$UNIMPLEMENTED\": ${offenders.sorted()}. " +
                "Route the call through executeShellCommand — see McpShell."
        }
    }

    // ---- transport -------------------------------------------------------------------

    private data class CallResult(val isError: Boolean, val text: String, val isEmptyResult: Boolean)

    private inner class Endpoint(val url: String, val token: String) {
        val devices: List<String> by lazy {
            call("android_list_devices", "{}").text.lines().filter { it.isNotBlank() }
        }

        /** What the running server advertises, which is not always what this build defines. */
        val servedTools: Set<String> by lazy {
            val response = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            gson.fromJson(response, JsonObject::class.java)
                ?.getAsJsonObject("result")
                ?.getAsJsonArray("tools")
                ?.map { it.asJsonObject.get("name").asString }
                ?.toSet()
                .orEmpty()
        }

        fun call(tool: String, arguments: String): CallResult {
            val body = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"$tool","arguments":$arguments}}
            """.trimIndent()
            val response = post(body)
            val result = gson.fromJson(response, JsonObject::class.java)?.getAsJsonObject("result")
                ?: return CallResult(true, "no result in response: $response", isEmptyResult = true)

            val content = result.getAsJsonArray("content") ?: return CallResult(true, "", true)
            // Images carry no text; their presence is the evidence the call worked.
            val text = content.joinToString("\n") { element ->
                element.asJsonObject.get("text")?.asString
                    ?: "<${element.asJsonObject.get("type")?.asString ?: "unknown"}>"
            }
            return CallResult(
                isError = result.get("isError")?.asBoolean ?: false,
                text = text,
                isEmptyResult = content.isEmpty,
            )
        }

        private fun post(body: String): String {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        }
    }

    /**
     * Restricts a table to tools the running server actually serves.
     *
     * A stale IDE is reported once, by [the running server is this build]; it should not also
     * surface as a failure against every tool it has not heard of.
     */
    private fun Map<String, String>.servedBy(endpoint: Endpoint): Map<String, String> =
        filterKeys { it in endpoint.servedTools }.toSortedMap()

    /** Null, having skipped the test, when no live server was configured. */
    private fun liveEndpoint(): Endpoint? {
        val url = System.getenv("SPOCK_MCP_URL")
        val token = System.getenv("SPOCK_MCP_TOKEN")
        assumeTrue(
            !url.isNullOrBlank() && !token.isNullOrBlank(),
            "set SPOCK_MCP_URL and SPOCK_MCP_TOKEN to smoke-test a running MCP server",
        )
        return Endpoint(url!!, token!!)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val CALL_TIMEOUT_SECONDS = 60L
        const val UNIMPLEMENTED = "This method is not used in Android Studio"

        /** A selector no screen can match, so a query tool must answer "not found". */
        const val ABSENT = "spock-smoke-test-no-such-element"

        val NO_MATCH_VERDICT = Regex("FAIL|not found|nothing matched|no element", RegexOption.IGNORE_CASE)

        /** Report device or app state. Any error is a real failure. */
        val PROBES = mapOf(
            "android_list_devices" to "{}",
            "android_get_device_info" to "{}",
            "android_list_packages" to """{"filter":"android"}""",
            "android_get_package_info" to """{"packageName":"com.android.settings"}""",
            "android_get_current_activity" to "{}",
            "android_get_activity_stack" to "{}",
            "android_get_current_fragments" to "{}",
            "android_get_logcat" to """{"lines":20}""",
            "android_get_processes" to "{}",
            "android_get_battery_info" to "{}",
            "android_get_network_info" to "{}",
            "android_get_debug_context" to """{"include":["activity","ui","logcat"],"maxLogcatLines":20}""",
            "android_take_screenshot" to "{}",
            "android_get_ui_tree" to "{}",
            "android_accessibility_audit" to "{}",
            // Default sections only: the screenshot section is covered by its own tool above.
            "android_get_debug_context" to "{}",
        )

        /** Search for something. Reporting "no match" is a pass; anything else is not. */
        val QUERIES = mapOf(
            "android_find_ui_element" to """{"text":"$ABSENT"}""",
            "android_assert_visible" to """{"text":"$ABSENT"}""",
            "android_assert_enabled" to """{"text":"$ABSENT"}""",
            "android_assert_text" to """{"text":"$ABSENT"}""",
        )
    }
}
