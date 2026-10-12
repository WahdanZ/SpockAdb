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
import spock.adb.mcp.tools.GetPackageInfoTool
import java.util.concurrent.TimeUnit

/**
 * `android_get_package_info` answers with a summary, and with the dump only when asked: the dump
 * was 30 000 characters for a Flutter app, starting with its intent resolver tables (#182).
 */
class GetPackageInfoToolTest {

    private val dumpsys = checkNotNull(javaClass.getResource("/dumpsys/package-api34-sample-app.txt")).readText()

    private fun context(dump: String = dumpsys): FakeToolContext {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every { device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>()) } answers {
            val out = when {
                command.captured.startsWith("pm list packages") -> "package:$APP"
                command.captured.startsWith("dumpsys package") -> dump
                else -> ""
            }
            val bytes = out.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
        }
        return FakeToolContext(
            available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)),
            applicationId = APP,
        )
    }

    @Test
    fun `the default answer is the summary`() {
        val result = GetPackageInfoTool().execute(JsonObject(), context())

        assertFalse(result.isError, result.text())
        assertTrue(result.text().startsWith("$APP\nVersion: 1.2.0-sample (7)"), result.text())
        assertFalse(result.text().contains("Activity Resolver Table"), "no raw dump by default")
    }

    @Test
    fun `raw returns the dump itself`() {
        val result = GetPackageInfoTool().execute(JsonObject().apply { addProperty("raw", true) }, context())

        assertEquals(dumpsys.trim(), result.text().trim())
    }

    @Test
    fun `the summary reads past where the raw dump is cut`() {
        // A resolver table long enough to push the package section past the 40 000-character cap,
        // as Settings' does: the version must still be found.
        val padding = "Activity Resolver Table:\n" + "  Schemes:\n".repeat(5_000)
        val long = padding + dumpsys.substringAfter("Domain verification status:")

        val summary = GetPackageInfoTool().execute(JsonObject(), context(long)).text()
        val raw = GetPackageInfoTool().execute(JsonObject().apply { addProperty("raw", true) }, context(long)).text()

        assertTrue(summary.contains("Version: 1.2.0-sample (7)"), summary)
        assertTrue(raw.contains("[truncated:"), "raw stays capped")
        assertFalse(raw.contains("versionName="), "the cap cuts the raw dump before the version")
    }

    private companion object {
        const val APP = "spock.adb.sample"
    }
}
