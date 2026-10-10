package spock.adb.flutter

import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.FakeToolContext
import java.util.concurrent.TimeUnit

/**
 * Which ddmlib client is the selected app's process, for the follower; and how long Diagnose
 * waits to learn whether an app is Flutter, before its own budget starts.
 */
class FlutterFollowerServiceTest {

    @Test
    fun `the app's main process is the app`() {
        assertEquals(APP, FlutterFollowerService.processName(description = APP, packageName = APP))
    }

    @Test
    fun `a secondary process is not, though ddmlib gives it the app's package`() {
        assertNotEquals(APP, FlutterFollowerService.processName(description = "$APP:bg", packageName = APP))
    }

    @Test
    fun `before the process name is known, the package stands in`() {
        assertEquals(APP, FlutterFollowerService.processName(description = " ", packageName = APP))
        assertEquals(APP, FlutterFollowerService.processName(description = null, packageName = APP))
        assertEquals(null, FlutterFollowerService.processName(description = null, packageName = ""))
    }

    @Test
    fun `only a client change that names a process counts`() {
        assertEquals(APP, FlutterFollowerService.namedProcess(Client.CHANGE_NAME, APP, APP))
        assertEquals(
            APP,
            FlutterFollowerService.namedProcess(Client.CHANGE_NAME or Client.CHANGE_DEBUGGER_STATUS, APP, APP),
        )
        assertNull(FlutterFollowerService.namedProcess(Client.CHANGE_DEBUGGER_STATUS, APP, APP))
        assertNull(FlutterFollowerService.namedProcess(Client.CHANGE_PORT, APP, APP))
    }

    @Test
    fun `Diagnose gives each read of its Flutter detection a short wait`() {
        val waits = mutableListOf<Long>()
        val device = mockk<IDevice>(relaxed = true)
        every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
            waits += TimeUnit.SECONDS.convert(arg<Long>(2), arg<TimeUnit>(3))
        }

        FlutterFollowerService.detectForDiagnose(connected(device), APP, FlutterBuildCache())

        assertEquals(listOf(5L, 5L), waits, "dumpsys package, then the APK listing")
    }

    @Test
    fun `adb not answering in time reads as not Flutter, so Diagnose goes on without the section`() {
        val device = mockk<IDevice>()
        every {
            device.executeShellCommand(any(), any(), any(), any<TimeUnit>())
        } throws ShellCommandUnresponsiveException()

        assertNull(FlutterFollowerService.detectForDiagnose(connected(device), APP, FlutterBuildCache()))
    }

    @Test
    fun `detection elsewhere keeps its longer wait`() {
        val waits = mutableListOf<Long>()
        val device = mockk<IDevice>(relaxed = true)
        every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
            waits += TimeUnit.SECONDS.convert(arg<Long>(2), arg<TimeUnit>(3))
        }

        FlutterBuildCache().detectOn(device, "emulator-5554", APP)

        assertTrue(waits.all { it > FlutterFollowerService.DETECT_SECONDS }, "$waits")
    }

    private fun connected(device: IDevice) = FakeToolContext.device("emulator-5554").copy(device = device)

    private companion object {
        const val APP = "com.foo"
    }
}
