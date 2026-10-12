package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.ClearAppDataTool
import spock.adb.mcp.tools.DebugContextTool
import spock.adb.mcp.tools.GetDeviceConditionsTool
import spock.adb.mcp.tools.GetLogcatTool
import spock.adb.mcp.tools.GetPackageInfoTool
import spock.adb.mcp.tools.PackageSource
import spock.adb.mcp.tools.ResolvedPackage
import spock.adb.mcp.tools.RevokePermissionTool
import spock.adb.mcp.tools.packageOrNull
import spock.adb.mcp.tools.resolvePackage
import java.util.concurrent.TimeUnit

/**
 * One resolver for every tool that takes `packageName` (#182, F-04). Before it, each tool picked
 * its own default: with no project app, android_get_package_info failed, android_get_logcat read
 * the whole device without saying so, and android_get_debug_context used the selected app.
 */
class PackageResolutionTest {

    private val issued = mutableListOf<String>()

    /** A device whose shell answers by command, so a test can see which app was asked about. */
    private fun device(pids: Map<String, String> = emptyMap(), installed: Set<String> = emptySet()): ConnectedDevice {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val issuedCommand = command.captured
            issued += issuedCommand
            val reply = when {
                issuedCommand.startsWith("pidof") -> pids[issuedCommand.removePrefix("pidof ").trim('\'')].orEmpty()
                issuedCommand.startsWith("pm list packages") ->
                    installed.joinToString("\n") { "package:$it" }
                issuedCommand.startsWith("logcat") -> "01-01 00:00:00.000  1234  1234 E MyApp: boom"
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return FakeToolContext.device("emulator-5554").copy(device = device)
    }

    private fun context(
        selected: String? = null,
        project: String? = null,
        device: ConnectedDevice = device(),
    ) = FakeToolContext(available = listOf(device), applicationId = project, spockSelectedApp = selected)

    private fun args(packageName: String? = null) = JsonObject().apply {
        packageName?.let { addProperty("packageName", it) }
    }

    @Test
    fun `an app the agent names wins over the selected app and the project's`() {
        val resolved = context(selected = SELECTED, project = PROJECT).packageOrNull(args(ASKED))

        assertEquals(ResolvedPackage(ASKED, PackageSource.ARGUMENT), resolved)
        assertNull(resolved?.note, "an app the agent named needs no note")
    }

    @Test
    fun `with none named the app selected in Spock wins over the project's`() {
        val resolved = context(selected = SELECTED, project = PROJECT).packageOrNull(args())

        assertEquals(ResolvedPackage(SELECTED, PackageSource.SELECTED), resolved)
        assertEquals("package: $SELECTED (app selected in Spock)", resolved?.note)
    }

    @Test
    fun `with none named or selected the project's app is used`() {
        val resolved = context(project = PROJECT).packageOrNull(args())

        assertEquals(ResolvedPackage(PROJECT, PackageSource.PROJECT), resolved)
        assertEquals("package: $PROJECT (open project's application ID)", resolved?.note)
    }

    @Test
    fun `a blank packageName counts as not given`() {
        assertEquals(SELECTED, context(selected = SELECTED).packageOrNull(args("  "))?.name)
    }

    @Test
    fun `with none of the three the error names every way to give one`() {
        val failure = assertThrows<IllegalStateException> { context().resolvePackage(args()) }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("Pass packageName"), message)
        assertTrue(message.contains("select the app in Spock's tool window"), message)
        assertTrue(message.contains("open the Android project and let its Gradle sync finish"), message)
    }

    @Test
    fun `a tool that needs an app refuses with that error rather than guessing`() {
        // The case from the review: a Flutter project whose application ID does not resolve.
        val failure = assertThrows<IllegalStateException> { GetPackageInfoTool().execute(args(), context()) }

        assertTrue(failure.message.orEmpty().startsWith("No packageName was given"), failure.message)
    }

    @Test
    fun `android_get_package_info uses the selected app when the project resolves none, and says so`() {
        val result = GetPackageInfoTool().execute(
            args(),
            context(selected = SELECTED, device = device(installed = setOf(SELECTED))),
        )

        assertFalse(result.isError, result.text())
        assertEquals("package: $SELECTED (app selected in Spock)", result.text().lines().first())
        assertTrue(issued.any { it == "dumpsys package $SELECTED" || it == "dumpsys package '$SELECTED'" }, "$issued")
    }

    @Test
    fun `an error from a defaulted app still says which app it was`() {
        // A destructive tool defaulting to the selected app refuses an app that is not installed,
        // and the agent has to be able to see it was not the app it meant.
        val context = context(selected = SELECTED, project = PROJECT)

        val result = ClearAppDataTool().execute(args(), context)

        assertTrue(result.isError)
        assertEquals("package: $SELECTED (app selected in Spock)", result.text().lines().first())
        assertTrue(context.confirmations.isEmpty(), "never asked to wipe an app that is not there")
    }

    @Test
    fun `a destructive tool's confirmation says the app was a default, before anything happens`() {
        // The note in the answer arrives after the action; the developer approving it must know first.
        val defaulted = context(selected = SELECTED, project = PROJECT, device = device(installed = setOf(SELECTED)))
        ClearAppDataTool().execute(args(), defaulted)
        RevokePermissionTool().execute(
            args().apply { addProperty("permission", "android.permission.CAMERA") },
            defaulted,
        )

        assertEquals(listOf("android_clear_app_data", "android_revoke_permission"), defaulted.confirmations)
        defaulted.confirmationSummaries.forEach { summary ->
            assertTrue(
                summary.startsWith("$SELECTED is the app selected in Spock; the agent did not name one. "),
                summary,
            )
        }

        val named = context(selected = SELECTED, device = device(installed = setOf(ASKED)))
        ClearAppDataTool().execute(args(ASKED), named)
        assertFalse(named.confirmationSummaries.single().contains("did not name"), named.confirmationSummaries[0])
    }

    @Test
    fun `logcat that falls back to the whole device says so on its first line`() {
        val result = GetLogcatTool().execute(args(), context())

        val lines = result.text().lines()
        assertTrue(lines.first().startsWith("Whole-device log:"), result.text())
        assertTrue(lines.first().contains("Pass packageName"), lines.first())
        assertFalse(issued.single { it.startsWith("logcat") }.contains("--pid"), "$issued")
    }

    @Test
    fun `logcat asked for the whole device has no note`() {
        val result = GetLogcatTool().execute(args(""), context(selected = SELECTED))

        assertEquals("01-01 00:00:00.000  1234  1234 E MyApp: boom", result.text())
        assertFalse(issued.single { it.startsWith("logcat") }.contains("--pid"), "$issued")
    }

    @Test
    fun `logcat with no packageName reads the selected app's processes and names it first`() {
        val context = context(selected = SELECTED, device = device(mapOf(SELECTED to "4321")))

        val result = GetLogcatTool().execute(args(), context)

        assertEquals("package: $SELECTED (app selected in Spock)", result.text().lines().first())
        assertTrue(issued.single { it.startsWith("logcat") }.contains("--pid=4321"), "$issued")
    }

    @Test
    fun `the full debug context reads the selected app's log, not the project's`() {
        val arguments = JsonObject().apply {
            addProperty("format", "full")
            add("include", JsonArray().apply { add("logcat") })
        }

        val device = device(mapOf(SELECTED to "4321", PROJECT to "999"))

        val context = context(selected = SELECTED, project = PROJECT, device = device)

        val result = DebugContextTool().execute(arguments, context)

        assertTrue(result.text().contains("package: $SELECTED (app selected in Spock)"), result.text())
        assertTrue(issued.single { it.startsWith("logcat") }.contains("--pid=4321"), "$issued")
    }

    @Test
    fun `the debug summary with no app says it covers the whole device`() {
        val result = DebugContextTool().execute(
            JsonObject().apply { add("include", JsonArray().apply { add("logs") }) },
            context(),
        )

        val report = JsonParser.parseString(result.text()).asJsonObject
        assertTrue(report["packageName"].isJsonNull, result.text())
        assertTrue(report["packageFrom"].asString.contains("whole device"), result.text())
    }

    @Test
    fun `device conditions with no app say the bucket was not read`() {
        val result = GetDeviceConditionsTool().execute(args(), context())

        assertFalse(result.isError, result.text())
        assertTrue(result.text().lines().first().startsWith("Standby bucket: not read."), result.text())
    }

    private companion object {
        const val ASKED = "com.example.asked"
        const val SELECTED = "com.example.app.dev"
        const val PROJECT = "com.example.app"
    }
}
