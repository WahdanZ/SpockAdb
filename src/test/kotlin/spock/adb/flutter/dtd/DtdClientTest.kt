package spock.adb.flutter.dtd

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceRpcException
import java.net.InetAddress
import java.net.ServerSocket

class DtdClientTest {

    private val dtd = FakeDtd()

    @AfterEach
    fun tearDown() = dtd.close()

    private fun services() = DtdClient.connect(dtd.uri).use { it.getVmServices() }

    @Test
    fun `the handshake goes to the secret path, with no ws suffix`() {
        services()
        assertEquals(listOf("/${FakeDtd.SECRET}"), dtd.server.handshakePaths)
    }

    @Test
    fun `getVmServices is a JSON-RPC call with no params needing no secret`() {
        services()
        val request = dtd.requests.single()
        assertEquals("2.0", request.get("jsonrpc").asString)
        assertEquals("ConnectedApp.getVmServices", request.get("method").asString)
        assertEquals(0, request.getAsJsonObject("params").size())
    }

    @Test
    fun `the recorded S12 answer - DDS address, kind, device and package`() {
        val app = services().single()
        assertEquals("ws://127.0.0.1:53296/${FakeDtd.VM_TOKEN}/ws", app.uri.webSocketUri.toString())
        assertEquals("Flutter", app.kind)
        assertEquals("sdk gphone64 arm64", app.deviceModel)
        assertEquals("dtd_spike", app.packageName)
        assertEquals("Kind: Flutter - Device: sdk gphone64 arm64 - Package: dtd_spike", app.name)
        assertFalse(app.toString().contains(FakeDtd.VM_TOKEN), app.toString())
    }

    @Test
    fun `an empty list is no apps`() {
        dtd.vmServices = FakeDtd.response()
        assertTrue(services().isEmpty())
    }

    @Test
    fun `an error answer is a DtdException with the code and no secret`() {
        dtd.error = VmServiceRpcException.METHOD_NOT_FOUND to
            "Unknown method at ws://127.0.0.1:1/${FakeDtd.SECRET} and ws://127.0.0.1:2/${FakeDtd.VM_TOKEN}/ws"
        val error = assertThrows<DtdException> { services() }
        assertEquals(VmServiceRpcException.METHOD_NOT_FOUND, error.rpcCode)
        assertTrue(error.message!!.contains(DtdClient.GET_VM_SERVICES), error.message)
        assertFalse(error.message!!.contains(FakeDtd.SECRET), error.message)
        assertFalse(error.message!!.contains(FakeDtd.VM_TOKEN), error.message)
        assertNull(error.cause)
    }

    @Test
    fun `a secret that is not padded is still scrubbed from errors`() {
        FakeDtd(secret = "unpaddedDaemonSecret").use { plain ->
            plain.error = -32_000 to "Rejected unpaddedDaemonSecret"
            val error = assertThrows<DtdException> { DtdClient.connect(plain.uri).use { it.getVmServices() } }
            assertFalse(error.message!!.contains("unpaddedDaemonSecret"), error.message)
            assertEquals(-32_000, error.rpcCode)
        }
    }

    @Test
    fun `no answer times out as a DtdException`() {
        dtd.silent = true
        val error = assertThrows<DtdException> { DtdClient.connect(dtd.uri).use { it.getVmServices(timeoutMs = 200) } }
        assertTrue(error.message!!.contains("200 ms"), error.message)
        assertNull(error.rpcCode)
    }

    @Test
    fun `nothing listening is a DtdException naming the redacted address`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val nobody = DtdUri.parse("ws://127.0.0.1:$port/${FakeDtd.SECRET}")
        val error = assertThrows<DtdException> { DtdClient.connect(nobody) }
        assertTrue(error.message!!.contains("ws://127.0.0.1:$port/<redacted>"), error.message)
        assertFalse(error.message!!.contains(FakeDtd.SECRET), error.message)
        assertTrue(error is VmServiceException)
    }

    @Test
    fun `entries with no address, a foreign address or the wrong shape are dropped`() {
        dtd.vmServices = FakeDtd.response(
            "ws://10.0.0.2:1/T=/ws" to "Kind: Flutter - Device: a - Package: b",
            "not an address" to "Kind: Flutter - Device: a - Package: b",
            "ws://127.0.0.1:3/T=/ws" to "Kind: Flutter - Device: Pixel 8 - Package: kept",
        ).apply { getAsJsonArray("vmServices").add(42) }
        assertEquals(listOf("kept"), services().map { it.packageName })
    }

    @Test
    fun `name parts are read independently, and a device name may hold a dash`() {
        val parsed = DtdVmService.parse(
            FakeDtd.response(
                "ws://127.0.0.1:1/T=/ws" to "Kind: Flutter - Device: Galaxy S23 - Ultra - Package: my_app",
                "ws://127.0.0.1:2/T=/ws" to "Kind: Dart - Package: cli_tool",
                "ws://127.0.0.1:3/T=/ws" to "something else entirely",
                "ws://127.0.0.1:4/T=/ws" to "",
            ),
        )
        assertEquals(listOf("Galaxy S23 - Ultra", null, null, null), parsed.map { it.deviceModel })
        assertEquals(listOf("my_app", "cli_tool", null, null), parsed.map { it.packageName })
        assertEquals(listOf("Flutter", "Dart", null, null), parsed.map { it.kind })
    }

    @Test
    fun `a daemon answering the upgrade with a redirect is a DtdException, and the redirect is not followed`() {
        FakeDtd().use { target ->
            dtd.server.redirectTo = target.wsUri
            val error = assertThrows<DtdException> { services() }
            assertFalse(error.message!!.contains(FakeDtd.SECRET), error.message)
            assertTrue(target.server.handshakePaths.isEmpty(), "followed to ${target.server.handshakePaths}")
        }
    }

    @Test
    fun `an answer past the message cap fails the call instead of being held`() {
        val huge = "x".repeat(DtdClient.MAX_MESSAGE_CHARS)
        dtd.vmServices = FakeDtd.response("ws://127.0.0.1:3/T=/ws" to huge)
        assertThrows<DtdException> { services() }
    }

    @Test
    fun `a name is one line, and capped`() {
        val parsed = DtdVmService.parse(
            FakeDtd.response(
                "ws://127.0.0.1:1/T=/ws" to "Kind: Flutter - Device: Pixel\\n8\\u0007 - Package: my_app",
                "ws://127.0.0.1:2/T=/ws" to "Kind: Flutter - Device: " + "d".repeat(500) + " - Package: long_app",
            ),
        )
        assertEquals("Kind: Flutter - Device: Pixel 8  - Package: my_app", parsed[0].name)
        assertEquals("Pixel 8", parsed[0].deviceModel)
        assertEquals("my_app", parsed[0].packageName)
        assertEquals(DtdVmService.MAX_NAME_CHARS, parsed[1].name.length)
        assertNull(parsed[1].packageName)
    }
}
