package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Making Android ask for a permission again without clearing the app's data.
 *
 * `pm clear-permission-flags` sets no exit status, so success is decided by reading the package
 * back: these pin that a flag still set after the command is never reported as a reset.
 */
class ResetPermissionPromptTest {

    private val camera = "android.permission.CAMERA"

    @Test
    fun `both flags gone is a reset`() {
        assertNull(ResetPermissionPrompt.failureOf("", RuntimePermission(camera, false, false, false), camera))
    }

    @Test
    fun `silence from pm is not success when the flag is still there`() {
        val failure = ResetPermissionPrompt.failureOf("", RuntimePermission(camera, false, true, true), camera)

        assertNotNull(failure)
        assertTrue(failure!!.contains("still"), failure)
    }

    @Test
    fun `a permission the app does not hold at runtime is named`() {
        val failure = ResetPermissionPrompt.failureOf("", null, camera)

        assertTrue(failure!!.contains("not a runtime permission"), failure)
    }

    @Test
    fun `a package manager without the subcommand says so`() {
        val still = RuntimePermission(camera, false, true, true)

        listOf(
            "Unknown command: clear-permission-flags",
            "Unknown command: clear-permission-flags\nusage: pm path [--user USER_ID] PACKAGE\n...",
        ).forEach { output ->
            val failure = ResetPermissionPrompt.failureOf(output, still, camera)
            assertTrue(failure!!.contains("no clear-permission-flags command"), failure)
        }
    }

    @Test
    fun `USER_SET left behind is not a reset either`() {
        assertNotNull(ResetPermissionPrompt.failureOf("", RuntimePermission(camera, false, true, false), camera))
    }

    @Test
    fun `what the device said is passed on`() {
        val failure = ResetPermissionPrompt.failureOf(
            "\nException occurred while executing 'clear-permission-flags':\nSecurityException: nope",
            RuntimePermission(camera, false, true, true),
            camera,
        )

        assertTrue(failure!!.endsWith(": Exception occurred while executing 'clear-permission-flags':"), failure)
    }

    @Test
    fun `the command clears both flags for user 0, then reads the package back`() {
        val device = FakePermissionDevice(flagsAfter = "")

        val said = ResetPermissionPromptCommand().execute(PKG to camera, mockk<Project>(relaxed = true), device.device)

        assertEquals("CAMERA will be asked for again", said)
        val reset = device.commands.indexOf(
            "pm clear-permission-flags --user 0 'com.example.app' 'android.permission.CAMERA' user-set user-fixed",
        )
        assertTrue(reset >= 0, device.commands.toString())
        assertTrue(
            device.commands.drop(reset + 1).any { it == "dumpsys package 'com.example.app'" },
            "the result is read back after the reset: ${device.commands}",
        )
    }

    @Test
    fun `a flag the device kept makes the command fail`() {
        val device = FakePermissionDevice(flagsAfter = "USER_SET|USER_FIXED")

        val thrown = assertThrows(IllegalStateException::class.java) {
            ResetPermissionPromptCommand().execute(PKG to camera, mockk<Project>(relaxed = true), device.device)
        }
        assertTrue(thrown.message!!.contains("still"), thrown.message)
    }

    /** A device whose `pm` prints nothing and whose `dumpsys package` shows CAMERA with [flagsAfter]. */
    private class FakePermissionDevice(private val flagsAfter: String) {
        val commands = mutableListOf<String>()
        val device: IDevice = mockk(relaxed = true)

        init {
            every { device.getProperty("ro.build.version.sdk") } returns "34"
            val command = slot<String>()
            val receiver = slot<IShellOutputReceiver>()
            every { device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>()) } answers {
                commands += command.captured
                val bytes = reply(command.captured).toByteArray()
                receiver.captured.addOutput(bytes, 0, bytes.size)
                receiver.captured.flush()
            }
        }

        private fun reply(command: String): String = when {
            command.startsWith("pm list packages") -> "package:$PKG"
            command.startsWith("dumpsys package") -> """
                Packages:
                  Package [$PKG] (c3d4):
                    User 0: ceDataInode=1 installed=true
                      runtime permissions:
                        android.permission.CAMERA: granted=false, flags=[ $flagsAfter]
            """.trimIndent()
            else -> ""
        }
    }

    private companion object {
        const val PKG = "com.example.app"
    }
}
