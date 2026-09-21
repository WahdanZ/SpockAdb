package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.command.AmStartResult.Outcome
import java.util.concurrent.TimeUnit

/** The panel's half of the deep link: what reaches the device, and what a silent one means. */
class OpenDeepLinkCommandTest {

    private val project = mockk<Project>(relaxed = true)
    private val uri = "myapp://product/42"

    @Test
    fun `the command sent is the one AmStart builds`() {
        val (device, commands) = scriptedDevice("Status: ok\nActivity: com.example/.DeepLinkActivity")

        val result = OpenDeepLinkCommand().execute(uri, project, device)

        assertEquals(listOf(AmStart.command(uri)), commands)
        assertEquals(Outcome.STARTED, result.outcome)
        assertEquals("com.example/.DeepLinkActivity", result.component)
    }

    @Test
    fun `a device that goes quiet mid-launch is unconfirmed, not an error`() {
        // `am -W` prints nothing until the launch finishes, and ddmlib's timeout is the gap
        // between chunks of output — so a slow cold start throws for a link that did open.
        val (device, _) = scriptedDevice("Starting: Intent { act=android.intent.action.VIEW }") {
            throw ShellCommandUnresponsiveException()
        }

        val result = OpenDeepLinkCommand().execute(uri, project, device)

        assertEquals(Outcome.UNRECOGNISED, result.outcome)
        assertTrue(result.succeeded, "a device that stopped talking never said the link failed")
        assertTrue(result.message.contains(uri), result.message)
    }

    @Test
    fun `a refusal that arrives before the device goes quiet is still a refusal`() {
        val (device, _) = scriptedDevice(
            "Starting: Intent { dat=myapp://product/42 }\n" +
                "java.lang.SecurityException: Permission Denial: starting Intent",
        ) { throw ShellCommandUnresponsiveException() }

        val result = OpenDeepLinkCommand().execute(uri, project, device)

        assertEquals(Outcome.PERMISSION_DENIED, result.outcome)
        assertFalse(result.succeeded)
    }

    @Test
    fun `a blank uri is refused before anything reaches the device`() {
        val (device, commands) = scriptedDevice("Status: ok")

        val thrown = assertThrows<IllegalArgumentException> { OpenDeepLinkCommand().execute("  ", project, device) }

        assertTrue(thrown.message!!.contains("deep link"), thrown.message)
        assertTrue(commands.isEmpty())
    }

    /**
     * A device whose shell writes [reply] and then runs [afterOutput], recording commands.
     * [afterOutput] is how a timeout is staged: output first, then the exception.
     */
    private fun scriptedDevice(
        reply: String,
        afterOutput: () -> Unit = {},
    ): Pair<IDevice, List<String>> {
        val device = mockk<IDevice>(relaxed = true)
        val commands = mutableListOf<String>()
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            commands += command.captured
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
            afterOutput()
        }
        return device to commands
    }
}
