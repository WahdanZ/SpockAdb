package spock.adb.device.ops

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.GetActivityCommand
import spock.adb.command.GetBackStackCommand
import spock.adb.command.GetFragmentsCommand
import spock.adb.mcp.FakeToolContext
import spock.adb.mcp.text
import spock.adb.mcp.tools.ToolRegistry

/**
 * The read-only half: the tool window and an agent must read the screen the same way, and
 * neither reading should depend on an open project.
 *
 * The second half of that is what changed here. `Command.execute` takes a `Project` that none
 * of these reads looks at, so every inspection tool called `requireProject()` purely to have
 * something to pass — a failure mode belonging to a parameter rather than to the operation.
 *
 * The device is scripted from the same `dumpsys` capture the parser tests use, so "the tool
 * reports the fragment on screen" is a claim about a real dump and not about a fixture shaped
 * to make the test pass.
 */
class InspectionParityTest {

    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `the current activity is read the same way from both paths`() {
        assertSameDeviceTraffic("android_get_current_activity") { device ->
            GetActivityCommand().execute(Any(), project, device)
        }
    }

    @Test
    fun `the activity stack is read the same way from both paths`() {
        assertSameDeviceTraffic("android_get_activity_stack") { device ->
            GetBackStackCommand().execute(Any(), project, device)
        }
    }

    @Test
    fun `fragments are read the same way from both paths`() {
        assertSameDeviceTraffic("android_get_current_fragments") { device ->
            GetFragmentsCommand().execute(SAMPLE_PACKAGE, project, device)
        }
    }

    @Test
    fun `an agent gets the fragment the tool window would have shown`() {
        val result = ToolRegistry.find("android_get_current_fragments")!!
            .execute(JsonObject(), contextWithoutProject().first)

        assertTrue(result.text().contains("DetailFragment"), result.text())
    }

    @Test
    fun `reading the screen does not require an open project`() {
        // Each of these used to throw "No project is open" before it reached the device.
        listOf(
            "android_get_current_activity",
            "android_get_activity_stack",
            "android_get_current_fragments",
        ).forEach { toolName ->
            val result = ToolRegistry.find(toolName)!!.execute(JsonObject(), contextWithoutProject().first)

            assertFalse(result.isError, "$toolName still refuses without a project: ${result.text()}")
        }
    }

    private fun assertSameDeviceTraffic(toolName: String, ide: (IDevice) -> Unit) {
        val (ideDevice, ideCommands) = scriptedDevice(::screenOnDevice)
        ide(ideDevice)

        val (context, agentCommands) = contextWithoutProject()
        val result = ToolRegistry.find(toolName)!!.execute(JsonObject(), context)

        assertFalse(result.isError, "$toolName failed on a device with a screen: ${result.text()}")
        assertEquals(
            ideCommands,
            agentCommands,
            "$toolName and the tool window no longer read the device the same way",
        )
    }

    /** No project, which is the point: the application ID still comes from the context. */
    private fun contextWithoutProject(): Pair<FakeToolContext, List<String>> {
        val (device, commands) = scriptedDevice(::screenOnDevice)
        val context = FakeToolContext(
            project = null,
            available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)),
            applicationId = SAMPLE_PACKAGE,
        )
        return context to commands
    }

    /** A device with the sample app on screen, so every tool has something to report. */
    private fun screenOnDevice(command: String): String = when {
        command.startsWith("getprop ro.build.version.sdk") -> "34"
        command.contains("mResumedActivity") ->
            "  mResumedActivity: ActivityRecord{a1 u0 $SAMPLE_PACKAGE/.NavigationActivity t1}"
        command.startsWith("dumpsys activity '$SAMPLE_PACKAGE'") -> FRAGMENT_DUMP
        else -> ""
    }

    private companion object {
        /** The app the captured dump is of. */
        const val SAMPLE_PACKAGE = "spock.adb.sample"

        /** `dumpsys activity spock.adb.sample` on an API 34 emulator, at Home -> List -> Detail. */
        val FRAGMENT_DUMP: String =
            requireNotNull(
                InspectionParityTest::class.java
                    .getResource("/dumpsys/fragments-api34-sample-navigation.txt"),
            ) { "missing the captured fragment dump" }.readText()
    }
}
