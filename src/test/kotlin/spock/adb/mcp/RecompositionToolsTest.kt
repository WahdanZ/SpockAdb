package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ops.RecompositionOperations
import spock.adb.device.ops.TracingHandshake
import spock.adb.flutter.FlutterRebuildRecorder
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.RebuildTracker
import spock.adb.mcp.tools.GetRecompositionCountsTool
import spock.adb.mcp.tools.ToolResult
import java.util.Base64
import java.util.concurrent.TimeUnit

class RecompositionToolsTest {

    private val trace = javaClass.getResourceAsStream("/perfetto/composition-api34-sample-10s.pftrace")!!.readBytes()
    private val commands = mutableListOf<String>()

    private val enabled = """Broadcast completed: result=1, data="{"exitCode":1,"requiredVersion":"1.0.0"}""""

    /** A device answering each command by the [replies] key it starts with. */
    private fun device(replies: Map<String, String>): IDevice = mockk<IDevice>(relaxed = true).also { device ->
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every { device.serialNumber } returns "emulator-5554"
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            commands += command.captured
            val reply = replies.entries.firstOrNull { command.captured.startsWith(it.key) }?.value.orEmpty()
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
    }

    private fun healthy(broadcast: String = enabled, perfetto: String = "Wrote 59961 bytes into x") = device(
        mapOf(
            "pidof" to "18394\n",
            "am broadcast" to broadcast,
            "echo" to perfetto,
            "base64" to Base64.getMimeEncoder().encodeToString(trace),
        ),
    )

    private fun run(device: IDevice, arguments: JsonObject = JsonObject()) =
        GetRecompositionCountsTool().execute(
            arguments.apply { addProperty("packageName", "spock.adb.sample") },
            FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device))),
        )

    @Test
    fun `reports the app's composables, most frequent first, and cleans up the trace`() {
        val result = run(healthy())

        assertFalse(result.isError)
        val text = result.text()
        assertTrue(text.contains("475 composition(s) across 5 composable(s), 1 of them the app's own"), text)
        assertTrue(
            text.contains("95  spock.adb.sample.compose.TickingCounter  (RecompositionActivity.kt:73)"),
            text,
        )
        assertFalse(text.contains("androidx.compose.material3.Text"), text)
        val cleanup = "rm -f '/data/misc/perfetto-traces/spock-recomposition-"
        assertTrue(commands.any { it.startsWith(cleanup) }, "$commands")
    }

    @Test
    fun `libraries are listed when asked for`() {
        val text = run(healthy(), JsonObject().apply { addProperty("includeLibraries", true) }).text()

        assertTrue(text.contains("androidx.compose.material3.Text  (Text.kt:109)"), text)
    }

    @Test
    fun `the recording asks perfetto for the requested window, capped at thirty seconds`() {
        run(healthy(), JsonObject().apply { addProperty("durationSeconds", 90) })

        assertTrue(commands.single { it.startsWith("echo") }.contains("duration_ms: 30000"), "$commands")
    }

    @Test
    fun `an app that is not running is told to open its screen`() {
        val result = run(device(mapOf("pidof" to "")))

        assertTrue(result.isError)
        assertTrue(result.text().contains("spock.adb.sample is not running"), result.text())
    }

    @Test
    fun `an app without the tracing library is told what to add`() {
        val result = run(healthy(broadcast = "Broadcast completed: result=0"))

        assertTrue(result.isError)
        assertTrue(result.text().contains("androidx.compose.runtime:runtime-tracing"), result.text())
        assertFalse(commands.any { it.startsWith("echo") }, "nothing is recorded for an app that cannot be traced")
    }

    @Test
    fun `a perfetto failure is reported with what it said, and the file is still removed`() {
        val result = run(healthy(perfetto = "Could not connect to the traced service"))

        assertTrue(result.isError)
        assertTrue(result.text().contains("Could not connect to the traced service"), result.text())
        assertTrue(commands.any { it.startsWith("rm -f ") }, "$commands")
    }

    @Test
    fun `the handshake reply is read from its exit code, or its result code when there is no body`() {
        assertEquals(TracingHandshake.Result.ENABLED, TracingHandshake.parse(enabled))
        assertEquals(
            TracingHandshake.Result.ALREADY_ENABLED,
            TracingHandshake.parse("""Broadcast completed: result=2, data="{"exitCode":2}""""),
        )
        assertEquals(
            TracingHandshake.Result.BINARY_MISSING,
            TracingHandshake.parse(
                """Broadcast completed: result=11, data="{"exitCode":11,"message":"lib not found"}"""",
            ),
        )
        assertEquals(TracingHandshake.Result.NO_RECEIVER, TracingHandshake.parse("Broadcast completed: result=0"))
        assertEquals(TracingHandshake.Result.FAILED, TracingHandshake.parse("Error: device offline"))
    }

    @Test
    fun `a freshly enabled app is given time to attach before recording, one already tracing is not`() {
        val fresh = System.currentTimeMillis().let { start ->
            run(healthy())
            System.currentTimeMillis() - start
        }
        val already = System.currentTimeMillis().let { start ->
            run(healthy(broadcast = """Broadcast completed: result=2, data="{"exitCode":2}""""))
            System.currentTimeMillis() - start
        }

        assertTrue(fresh >= RecompositionOperations.WARM_UP_MS, "fresh: ${fresh}ms")
        assertTrue(already < RecompositionOperations.WARM_UP_MS, "already enabled: ${already}ms")
    }

    @Test
    fun `the perfetto config is one line, so echo carries it whole`() {
        assertFalse(RecompositionOperations.config(5_000).contains('\n'))
    }

    private fun flutterRun(result: FlutterRebuildRecorder.Result?, arguments: JsonObject = JsonObject()): ToolResult {
        val asked = mutableListOf<GetRecompositionCountsTool.FlutterRebuildRequest>()
        val tool = GetRecompositionCountsTool { request ->
            asked += request
            result
        }
        return tool.execute(
            arguments.apply { addProperty("packageName", "spock.adb.spock_flutter_sample") },
            FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = healthy()))),
        ).also { assertEquals(listOf("spock.adb.spock_flutter_sample"), asked.map { it.packageName }) }
    }

    private fun rebuilds(
        tracking: FlutterRebuildRecorder.Tracking,
        seeded: Boolean = true,
        sessionEnded: Boolean = false,
    ): FlutterRebuildRecorder.Result {
        val tracker = RebuildTracker()
        val location = """{"ids":[7],"lines":[40],"columns":[12],"names":["StormTile"]}"""
        val map = """{"file:///app/lib/fixtures/frames.dart":$location}"""
        tracker.seedLocations(JsonParser.parseString(map).asJsonObject)
        (1..3).forEach { frame ->
            tracker.accept(
                FlutterExtensionEvent(
                    FlutterExtensionEvent.REBUILT_WIDGETS,
                    frame.toLong(),
                    null,
                    JsonParser.parseString("""{"startTime":${frame * 16_000},"events":[7,4,9,1]}""").asJsonObject,
                ),
            )
        }
        return FlutterRebuildRecorder.Result.Recorded(tracker.report(3_000), 3_000, tracking, seeded, sessionEnded)
    }

    @Test
    fun `a Flutter app records widget rebuilds in the tool's own shape, and Compose is not touched`() {
        val result = flutterRun(rebuilds(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF))

        assertFalse(result.isError)
        val text = result.text()
        val headline = "Recorded spock.adb.spock_flutter_sample for 3.0s (Flutter widget rebuilds): 3 frame(s)"
        assertTrue(text.startsWith(headline), text)
        assertTrue(text.contains("    12  StormTile  (lib/fixtures/frames.dart:40:12)"), text)
        assertTrue(text.contains("     3  Widget  (#9)"), text)
        assertTrue(text.endsWith("Rebuild tracking: Spock switched it on for the recording and off again."), text)
        assertTrue(commands.none { it.startsWith("am broadcast") || it.startsWith("echo") }, "$commands")
    }

    @Test
    fun `a flag that was already on is said to be left on, and a missing seed is said too`() {
        val text = flutterRun(rebuilds(FlutterRebuildRecorder.Tracking.ALREADY_ON, seeded = false)).text()

        assertTrue(text.contains("it was on already (the IDE's rebuild counts or DevTools), so Spock left it on"), text)
        assertTrue(text.contains("appear by number"), text)
    }

    @Test
    fun `a Flutter session that may not be written to is an error that says why`() {
        val result = flutterRun(FlutterRebuildRecorder.Result.Refused(FlutterRebuildRecorder.READ_ONLY))

        assertTrue(result.isError)
        assertTrue(result.text().contains("flutter run"), result.text())
        assertTrue(commands.isEmpty(), "$commands")
    }

    @Test
    fun `any other app records Compose as before`() {
        val result = flutterRun(null)

        assertTrue(result.text().contains("composition(s) across"), result.text())
    }

    @Test
    fun `a restart, a session that ended, and a flag someone else wrote are each said in words`() {
        val restarted = flutterRun(rebuilds(FlutterRebuildRecorder.Tracking.ISOLATE_GONE)).text()
        assertTrue(restarted.contains("the app restarted during the recording"), restarted)
        assertTrue(restarted.endsWith("counts stop at the restart."), restarted)
        assertFalse(restarted.contains("new one"), restarted)

        val ended = flutterRun(rebuilds(FlutterRebuildRecorder.Tracking.LEFT_ON, sessionEnded = true)).text()
        assertTrue(ended.contains("The Flutter session ended during the recording, so it stopped early."), ended)
        assertTrue(ended.contains("connection to the app was lost"), ended)

        val others = flutterRun(rebuilds(FlutterRebuildRecorder.Tracking.CHANGED_BY_OTHERS)).text()
        assertTrue(others.contains("Spock left it as that set it"), others)

        val unconfirmed = flutterRun(rebuilds(FlutterRebuildRecorder.Tracking.UNCONFIRMED)).text()
        assertTrue(unconfirmed.contains("cannot tell whether it changed the flag"), unconfirmed)
    }

    @Test
    fun `with no packageName the app selected in Spock is recorded, not the project's`() {
        val asked = mutableListOf<String>()
        val tool = GetRecompositionCountsTool(selectedApp = { "spock.adb.spock_flutter_sample.second" }) { request ->
            asked += request.packageName
            rebuilds(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF)
        }

        val result = tool.execute(
            JsonObject(),
            FakeToolContext(
                available = listOf(FakeToolContext.device("emulator-5554").copy(device = healthy())),
                applicationId = "spock.adb.spock_flutter_sample",
            ),
        )

        assertEquals(listOf("spock.adb.spock_flutter_sample.second"), asked)
        assertTrue(result.text().startsWith("Recorded spock.adb.spock_flutter_sample.second"), result.text())
    }

    @Test
    fun `a Compose host embedding Flutter with no Flutter session records Compose`() {
        val noSession = FlutterRebuildRecorder.Result.NoSession(
            "spock.adb.spock_flutter_sample ships the Flutter engine; recording its widget rebuilds needs a " +
                "live debug session, and it runs without one.",
        )

        val result = flutterRun(noSession)

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("composition(s) across"), result.text())
        assertFalse(result.text().contains("Flutter engine"), result.text())
    }

    @Test
    fun `a Flutter app with no session that Compose cannot record either says both`() {
        val noSession = FlutterRebuildRecorder.Result.NoSession(
            "spock.adb.spock_flutter_sample ships the Flutter engine; recording its widget rebuilds needs a " +
                "live debug session, and it runs without one.",
        )
        val asked = mutableListOf<String>()
        val tool = GetRecompositionCountsTool { request ->
            asked += request.packageName
            noSession
        }

        val result = tool.execute(
            JsonObject().apply { addProperty("packageName", "spock.adb.spock_flutter_sample") },
            FakeToolContext(
                available = listOf(
                    FakeToolContext.device("emulator-5554")
                        .copy(device = healthy(broadcast = "Broadcast completed: result=0")),
                ),
            ),
        )

        assertTrue(result.isError)
        assertTrue(result.text().contains("androidx.compose.runtime:runtime-tracing"), result.text())
        assertTrue(result.text().contains("needs a live debug session"), result.text())
    }
}
