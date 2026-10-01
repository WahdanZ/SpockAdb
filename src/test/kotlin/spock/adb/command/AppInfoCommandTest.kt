package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.TimeoutException
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterBuild
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * App info against a scripted device: whether an app is Flutter is a detail of the card, so
 * finding out must never cost the rest of it, and must not list the APK on every refresh.
 */
class AppInfoCommandTest {

    private val pkg = "spock.adb.spock_flutter_sample"

    private val dumpsys = """
        Packages:
          Package [$pkg] (a1b2):
            appId=10190
            versionCode=1 minSdk=21 targetSdk=34
            versionName=1.0.0
            flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
            lastUpdateTime=2026-10-01 05:31:02
    """.trimIndent()

    private val listing = """
         39525904  1981-01-01 01:01   assets/flutter_assets/kernel_blob.bin
         39599552  1981-01-01 01:01   lib/arm64-v8a/libflutter.so
    """.trimIndent()

    /** Every command the device was sent, in order. */
    private val commands = mutableListOf<String>()

    /** Answers `dumpsys` and `pidof`; the APK listing answers with [listed], or throws when null. */
    private fun device(listed: String?): IDevice {
        val device = mockk<IDevice>(relaxed = true)
        every { device.serialNumber } returns "emulator-${UUID.randomUUID()}"
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val issued = command.captured
            commands += issued
            val reply = when {
                issued.startsWith("dumpsys package") -> dumpsys
                issued.startsWith("pidof") -> "4242"
                issued == FlutterBuild.listingCommand(pkg) -> listed ?: throw TimeoutException()
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return device
    }

    private fun execute(device: IDevice) = AppInfoCommand().execute(pkg, mockk<Project>(relaxed = true), device)

    @Test
    fun `a listing that times out leaves the rest of the app info`() {
        val info = execute(device(listed = null))

        assertEquals("1.0.0 (1)", info.version())
        assertEquals("4242", info.pid)
        assertNull(info.flutter)
    }

    @Test
    fun `the APK is listed once per install, not on every refresh`() {
        val device = device(listed = listing)

        assertEquals(FlutterBuild.DEBUG, execute(device).flutter)
        assertEquals(FlutterBuild.DEBUG, execute(device).flutter)

        assertEquals(1, commands.count { it == FlutterBuild.listingCommand(pkg) }, "$commands")
    }
}
