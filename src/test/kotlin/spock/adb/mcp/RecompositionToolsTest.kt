package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ops.RecompositionOperations
import spock.adb.device.ops.TracingHandshake
import spock.adb.mcp.tools.GetRecompositionCountsTool
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
}
