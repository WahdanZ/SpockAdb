package spock.adb.flutter.dtd

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.dtd.DtdAppIdentity.Result
import spock.adb.flutter.dtd.DtdAppIdentity.VmProcess
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceUri
import java.net.InetAddress
import java.net.ServerSocket

class DtdAppIdentityTest {

    private val vm = FakeVmService()

    @AfterEach
    fun tearDown() = vm.close()

    private fun at(port: Int) = FakeDtd.candidate(VmServiceUri.parse("ws://127.0.0.1:$port/T=/ws"))

    /** Each port's VM, as the fake reads it; the ports asked. */
    private val vms = mutableMapOf<Int, VmProcess>()
    private val asked = mutableListOf<Int>()
    private var now = 1_000_000L

    private val identity = DtdAppIdentity(
        vmProcess = { uri ->
            asked += uri.port
            vms[uri.port] ?: throw VmServiceException("nobody at ${uri.port}")
        },
        clock = { now },
    )

    @Test
    fun `the VM's pid and start are read with one getVM and nothing else`() {
        assertEquals(VmProcess(12345L, 1727776800000L), DtdAppIdentity.readVmProcess(VmServiceUri.parse(vm.uri)))
        assertEquals(listOf("getVM"), vm.requests.map { it.get("method").asString })
    }

    @Test
    fun `a VM handing its clients to DDS is followed there once, and said to be a VM's own address`() {
        FakeVmService().use { dds ->
            dds.on("getVM") { FakeVmService.Reply.Result(JsonObject().apply { addProperty("pid", 777) }) }
            vm.server.redirectTo = dds.uri
            val read = DtdAppIdentity.readVmProcess(VmServiceUri.parse(vm.uri))
            assertEquals(VmProcess(777L, null, viaRedirect = true), read)
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
    fun `the single app on the pid that started with the process is confirmed`() {
        vms[1] = VmProcess(4242L, PROCESS_START + 800)
        vms[2] = VmProcess(111L, PROCESS_START + 800)
        val confirmed = identity.confirm(listOf(at(1), at(2)), mapOf(4242L to PROCESS_START)) as Result.Confirmed
        assertEquals(1, confirmed.candidate.open().port)
        assertTrue(confirmed.startChecked)
    }

    @Test
    fun `the same pid with another start is another device's app, and rejected`() {
        vms[1] = VmProcess(4242L, PROCESS_START - 3_600_000)
        vms[2] = VmProcess(4242L, PROCESS_START + DtdAppIdentity.VM_START_LAG_MS + 1)
        assertSame(Result.None, identity.confirm(listOf(at(1), at(2)), mapOf(4242L to PROCESS_START)))
    }

    @Test
    fun `the same pid and start on two candidates is ambiguous, whatever their rank`() {
        vms[1] = VmProcess(4242L, PROCESS_START + 500)
        vms[2] = VmProcess(4242L, PROCESS_START + 900)
        val result = identity.confirm(listOf(at(1), at(2)), mapOf(4242L to PROCESS_START)) as Result.Ambiguous
        assertEquals(listOf(1, 2), result.candidates.map { it.candidate.open().port })
    }

    @Test
    fun `when the device does not say when the process started, the pid alone decides - and only if one passes`() {
        vms[1] = VmProcess(4242L, PROCESS_START)
        vms[2] = VmProcess(111L, PROCESS_START)
        val confirmed = identity.confirm(listOf(at(1), at(2)), mapOf(4242L to null)) as Result.Confirmed
        assertFalse(confirmed.startChecked)

        vms[2] = VmProcess(4242L, null)
        assertTrue(identity.confirm(listOf(at(1), at(2)), mapOf(4242L to null)) is Result.Ambiguous)
    }

    @Test
    fun `a candidate that does not answer is not confirmed, the others still are`() {
        val nobody = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val live = FakeDtd.candidate(VmServiceUri.parse(vm.uri))
        val confirmed = DtdAppIdentity().confirm(listOf(at(nobody), live), mapOf(12345L to null)) as Result.Confirmed
        assertSame(live, confirmed.candidate)
    }

    @Test
    fun `no pids asks nothing`() {
        assertSame(Result.None, identity.confirm(listOf(at(1)), emptyMap()))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an address that turned out to be a VM's own is not asked again within the direct-probe interval`() {
        vms[1] = VmProcess(4242L, PROCESS_START, viaRedirect = true)
        identity.confirm(listOf(at(1)), mapOf(4242L to PROCESS_START))
        now += DtdAppIdentity.DIRECT_PROBE_INTERVAL_MS - 1
        assertSame(Result.None, identity.confirm(listOf(at(1)), mapOf(4242L to PROCESS_START)))
        now += 1
        assertTrue(identity.confirm(listOf(at(1)), mapOf(4242L to PROCESS_START)) is Result.Confirmed)
        assertEquals(listOf(1, 1), asked)
    }

    private companion object {
        const val PROCESS_START = 1_790_771_367_840L
    }
}
