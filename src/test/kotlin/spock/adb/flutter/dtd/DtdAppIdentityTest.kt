package spock.adb.flutter.dtd

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.net.InetAddress
import java.net.ServerSocket

class DtdAppIdentityTest {

    private val vm = FakeVmService()

    @AfterEach
    fun tearDown() = vm.close()

    private class Fixed(private val uri: VmServiceUri) : VmServiceCandidate(VmServiceSource.DTD, ddsLikely = true) {
        override val description: String get() = uri.redacted()
        override fun open(): VmServiceUri = uri
    }

    private fun at(port: Int) = Fixed(VmServiceUri.parse("ws://127.0.0.1:$port/T=/ws"))

    @Test
    fun `the VM's pid is read with one getVM and nothing else`() {
        assertEquals(12345L, DtdAppIdentity.readVmPid(VmServiceUri.parse(vm.uri)))
        assertEquals(listOf("getVM"), vm.requests.map { it.get("method").asString })
    }

    @Test
    fun `a VM handing its clients to DDS is followed there once`() {
        FakeVmService().use { dds ->
            dds.on("getVM") { FakeVmService.Reply.Result(JsonObject().apply { addProperty("pid", 777) }) }
            vm.server.redirectTo = dds.uri
            assertEquals(777L, DtdAppIdentity.readVmPid(VmServiceUri.parse(vm.uri)))
        }
    }

    @Test
    fun `the pid is read as a number or a string, and nothing else`() {
        fun vmWith(pid: String) = JsonParser.parseString("""{"type":"VM","pid":$pid}""").asJsonObject
        assertEquals(42L, DtdAppIdentity.pidOf(vmWith("42")))
        assertEquals(42L, DtdAppIdentity.pidOf(vmWith("\" 42 \"")))
        assertNull(DtdAppIdentity.pidOf(vmWith("null")))
        assertNull(DtdAppIdentity.pidOf(vmWith("0")))
        assertNull(DtdAppIdentity.pidOf(vmWith("[1]")))
        assertNull(DtdAppIdentity.pidOf(JsonObject()))
        assertNull(DtdAppIdentity.pidOf(null))
    }

    @Test
    fun `a candidate that does not answer is not confirmed, the others still are`() {
        val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val live = Fixed(VmServiceUri.parse(vm.uri))
        val confirmed = DtdAppIdentity().confirm(listOf(at(nobody), live), setOf(12345L))
        assertSame(live, confirmed?.candidate)
        assertEquals(12345L, confirmed?.pid)
    }

    @Test
    fun `none on the app's pids, or no pids, confirms nothing - and with no pids nothing is asked`() {
        val asked = mutableListOf<Int>()
        val identity = DtdAppIdentity { uri ->
            asked += uri.port
            if (uri.port == 2) throw VmServiceException("boom") else 1L
        }
        assertNull(identity.confirm(listOf(at(1), at(2)), setOf(9L)))
        assertEquals(listOf(1, 2), asked)
        asked.clear()
        assertNull(identity.confirm(listOf(at(1)), emptySet()))
        assertTrue(asked.isEmpty())
    }
}
