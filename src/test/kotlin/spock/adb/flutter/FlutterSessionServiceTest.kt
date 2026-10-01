package spock.adb.flutter

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.net.InetAddress
import java.net.ServerSocket

class FlutterSessionServiceTest {

    private val vm = FakeVmService()
    private val project = mockk<Project> { every { basePath } returns "/work/app" }
    private val device = ConnectedDevice(
        mockk<IDevice>(relaxed = true),
        DeviceInfo.unknown("emulator-5554").copy(model = "sdk_gphone64_arm64", state = DeviceState.ONLINE),
    )

    private val dtdAsked = mutableListOf<Pair<String, String?>>()
    private var dtdFound: List<VmServiceCandidate> = emptyList()
    private var logcatAsked = mutableListOf<String>()
    private var logcatFound: List<VmServiceCandidate> = emptyList()
    private val background = mutableListOf<Runnable>()

    private val service = FlutterSessionService(project).apply {
        dtdDiscovery = { path, model ->
            dtdAsked += path to model
            VmServiceDiscovery { dtdFound }
        }
        logcatDiscovery = { _, applicationId ->
            logcatAsked += applicationId
            VmServiceDiscovery { logcatFound }
        }
        this.background = { this@FlutterSessionServiceTest.background += it }
    }

    @AfterEach
    fun tearDown() {
        service.disconnect()
        vm.close()
    }

    private class Fixed(source: VmServiceSource, ddsLikely: Boolean, private val uri: VmServiceUri) :
        VmServiceCandidate(source, ddsLikely) {
        override val description: String get() = "$source ${uri.redacted()}"
        override fun open(): VmServiceUri = uri
    }

    private fun candidate(source: VmServiceSource, port: Int = 1, ddsLikely: Boolean = true) =
        Fixed(source, ddsLikely, VmServiceUri.parse("ws://127.0.0.1:$port/T=/ws"))

    @Test
    fun `DTD is asked for the project with the device's model`() {
        service.discover(device, "com.example.app")
        assertEquals(listOf("/work/app" to "sdk_gphone64_arm64"), dtdAsked)
    }

    @Test
    fun `order is DTD, then the pasted address`() {
        dtdFound = listOf(candidate(VmServiceSource.DTD))
        val found = service.discover(device, "com.example.app", pasted = "http://127.0.0.1:50300/HXKQJZK_Rkw=/")
        assertEquals(listOf(VmServiceSource.DTD, VmServiceSource.PASTED), found.map { it.source })
    }

    @Test
    fun `logcat is the last resort - asked only when nothing else found the app`() {
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, ddsLikely = false))
        assertEquals(listOf(VmServiceSource.LOGCAT), service.discover(device, "com.example.app").map { it.source })
        assertEquals(listOf("com.example.app"), logcatAsked)

        dtdFound = listOf(candidate(VmServiceSource.DTD))
        assertEquals(listOf(VmServiceSource.DTD), service.discover(device, "com.example.app").map { it.source })
        assertEquals(1, logcatAsked.size)
    }

    @Test
    fun `no application id means no logcat, and no project path means no DTD`() {
        service.projectPath = { null }
        assertTrue(service.discover(device, applicationId = null).isEmpty())
        assertTrue(dtdAsked.isEmpty())
        assertTrue(logcatAsked.isEmpty())
    }

    @Test
    fun `a blank device model is no filter`() {
        val unnamed = device.copy(info = device.info.copy(model = ""))
        service.discover(unnamed, null)
        assertNull(dtdAsked.single().second)
    }

    @Test
    fun `a failing source is skipped`() {
        service.dtdDiscovery = { _, _ -> VmServiceDiscovery { throw VmServiceException("boom") } }
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, ddsLikely = false))
        assertEquals(listOf(VmServiceSource.LOGCAT), service.discover(device, "com.example.app").map { it.source })
    }

    @Test
    fun `an unusable paste fails before any source is asked`() {
        assertThrows<IllegalArgumentException> { service.discover(device, "com.example.app", pasted = "nonsense") }
        assertTrue(dtdAsked.isEmpty())
        assertTrue(service.discover(device, null, pasted = "  ").isEmpty())
    }

    @Test
    fun `connect holds one session, and a second connect closes the first`() {
        val first = service.connect(PastedUriDiscovery(vm.uri).discover().single())
        assertSame(first, service.current)
        assertTrue(first.state is SessionState.Connected)

        FakeVmService().use { other ->
            val second = service.connect(PastedUriDiscovery(other.uri).discover().single())
            assertTrue(first.state is SessionState.Disconnected, "${first.state}")
            assertSame(second, service.current)
            service.disconnect()
            assertNull(service.current)
            assertTrue(second.state is SessionState.Disconnected)
        }
    }

    @Test
    fun `a failed connect leaves no session`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertThrows<VmServiceException> { service.connect(candidate(VmServiceSource.PASTED, port)) }
        assertNull(service.current)
    }

    @Test
    fun `dispose closes the session on a pooled thread, and refuses new connections`() {
        val session = service.connect(PastedUriDiscovery(vm.uri).discover().single())
        service.dispose()
        assertTrue(session.state is SessionState.Connected, "closed on the disposing thread")
        background.single().run()
        assertTrue(session.state is SessionState.Disconnected)
        assertNull(service.current)
        assertThrows<IllegalStateException> { service.connect(PastedUriDiscovery(vm.uri).discover().single()) }
    }
}
