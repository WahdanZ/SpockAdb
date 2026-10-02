package spock.adb.flutter.vmservice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.vmservice.FakeVmService.Companion.DDS_VERSION
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class VmServiceProbeTest {

    private val vm = FakeVmService()

    @AfterEach
    fun tearDown() = vm.close()

    private fun methods(fake: FakeVmService) = fake.requests.map { it.get("method").asString }

    @Test
    fun `DDS is found with one call, on a connection closed at once`() {
        val uri = VmServiceUri.parse(vm.uri)

        val result = VmServiceProbe.probe(uri)

        assertEquals(VmServiceProbe.Result.Dds(uri), result)
        assertEquals(listOf(DDS_VERSION), methods(vm))
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a VM with no DDS is a result, not an error, and is left having heard nothing else`() {
        vm.dds = false

        val result = VmServiceProbe.probe(VmServiceUri.parse(vm.uri))

        assertEquals(VmServiceProbe.Result.DirectNoDds, result)
        assertEquals(listOf(DDS_VERSION), methods(vm))
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a VM that hands over to DDS is followed, and DDS's address is the target`() {
        FakeVmService().use { dds ->
            vm.dds = false
            vm.server.redirectTo = dds.uri

            val result = VmServiceProbe.probe(VmServiceUri.parse(vm.uri))

            assertEquals(VmServiceProbe.Result.Dds(VmServiceUri.parse(dds.uri)), result)
            assertTrue(vm.requests.isEmpty())
            assertEquals(listOf(DDS_VERSION), methods(dds))
        }
    }

    @Test
    fun `nothing listening is unreachable, with the token kept out of the reason`() {
        val freePort = ServerSocket(0).use { it.localPort }

        val result = VmServiceProbe.probe(VmServiceUri.parse("ws://127.0.0.1:$freePort/HXKQJZK_Rkw=/ws"))

        val reason = (result as VmServiceProbe.Result.Unreachable).reason
        assertTrue(reason.startsWith("Could not connect"), reason)
        assertFalse(reason.contains("HXKQJZK"), reason)
    }

    @Test
    fun `any other answer to the probe is unreachable, not taken for the VM itself`() {
        vm.on(DDS_VERSION) { FakeVmService.Reply.Error(-32_000, "Server error") }

        val result = VmServiceProbe.probe(VmServiceUri.parse(vm.uri))

        assertTrue(result is VmServiceProbe.Result.Unreachable, result.toString())
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
    }
}
