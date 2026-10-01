package spock.adb.flutter.vmservice

import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.fixture
import spock.adb.flutter.vmservice.FakeVmService.Companion.notification
import spock.adb.flutter.vmservice.FakeVmService.Companion.response
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class VmServiceClientTest {

    private val vm = FakeVmService()
    private val clientDelegate = lazy { VmServiceClient.connect(VmServiceUri.parse(vm.uri)) }
    private val client by clientDelegate

    @AfterEach
    fun tearDown() {
        // A test that never used the client must not open one just to close it.
        if (clientDelegate.isInitialized()) client.close()
        vm.close()
    }

    private fun result(value: String) = JsonObject().apply { addProperty("value", value) }

    @Test
    fun `the handshake carries the token in the path, and client frames are masked`() {
        client.getVM()
        assertEquals(listOf("/${FakeVmService.TOKEN}/ws"), vm.server.handshakePaths)
        assertTrue(vm.server.clientFramesMasked)
    }

    @Test
    fun `a call is a JSON-RPC request and returns its result`() {
        val result = client.getVM()
        assertEquals("VM", result.get("type").asString)
        val request = vm.requests.single()
        assertEquals("2.0", request.get("jsonrpc").asString)
        assertEquals("getVM", request.get("method").asString)
    }

    @Test
    fun `answers are matched by id, whatever order they come in`() {
        vm.on("first") { FakeVmService.Reply.None }
        vm.on("second") { FakeVmService.Reply.None }
        val first = client.callAsync("first")
        val second = client.callAsync("second")
        val (firstId, secondId) = vm.awaitRequests("first").single().get("id").asString to
            vm.awaitRequests("second").single().get("id").asString
        assertNotEquals(firstId, secondId)

        vm.reply(secondId, result("two"))
        vm.reply(firstId, result("one"))

        assertEquals("one", first.get(2, TimeUnit.SECONDS).get("value").asString)
        assertEquals("two", second.get(2, TimeUnit.SECONDS).get("value").asString)
    }

    @Test
    fun `an error answer is an RPC exception with its code`() {
        val error = assertThrows<VmServiceRpcException> { client.call("ext.flutter.missing") }
        assertEquals(VmServiceRpcException.METHOD_NOT_FOUND, error.code)
        assertEquals("ext.flutter.missing", error.method)
    }

    @Test
    fun `a call with no answer times out, and a late answer is dropped`() {
        vm.on("slow") { FakeVmService.Reply.None }
        val error = assertThrows<VmServiceTimeoutException> { client.call("slow", timeoutMs = 200) }
        assertEquals("slow", error.method)

        vm.reply(vm.requestsFor("slow").single().get("id").asString, result("late"))
        assertEquals("VM", client.getVM().get("type").asString)
        assertTrue(client.isOpen)
    }

    @Test
    fun `a named call can wait longer than the default`() {
        vm.on("slowButFine") { FakeVmService.Reply.None }
        val replier = Thread {
            Thread.sleep(300)
            vm.reply(vm.requestsFor("slowButFine").single().get("id").asString, result("done"))
        }
        replier.start()
        val answer = client.call("slowButFine", timeoutMs = 3_000)
        assertEquals("done", answer.get("value").asString)
    }

    @Test
    fun `a response split over several frames is reassembled`() {
        vm.on("big") { FakeVmService.Reply.None }
        val pending = client.callAsync("big")
        val id = vm.awaitRequests("big").single().get("id").asString
        val big = result("x".repeat(70_000))
        vm.server.sendFragmented(response(id).apply { add("result", big) }.toString(), parts = 5)
        assertEquals(70_000, pending.get(2, TimeUnit.SECONDS).get("value").asString.length)
    }

    @Test
    fun `events arrive in order on one event thread, redacted, and fragmented events too`() {
        val received = CopyOnWriteArrayList<VmServiceEvent>()
        val threads = CopyOnWriteArrayList<String>()
        client.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) {
                    received += event
                    threads += Thread.currentThread().name
                }
            },
        )
        client.getVM()
        vm.pushEvent("Extension", fixture("event-frame.json"))
        vm.server.sendFragmented(notification("Extension", fixture("event-connectedVmServiceUri.json")).toString(), 3)
        vm.pushEvent("Extension", fixture("event-activeDevToolsServerAddress.json"))

        eventually { received.size == 3 }
        assertEquals(
            listOf("Flutter.Frame", "Flutter.ServiceExtensionStateChanged", "Flutter.ServiceExtensionStateChanged"),
            received.map { it.extensionKind },
        )
        assertEquals(setOf("Spock VM Service events"), threads.toSet())
        assertEquals("Extension", received[1].streamId)
        assertEquals("isolates/1111", received[1].isolateId)
        assertEquals(1_727_776_801_000L, received[1].timestamp)
        received.forEach { assertFalse(it.event.toString().contains("HXKQJZK"), it.event.toString()) }
        assertEquals("<redacted>", received[1].event.getAsJsonObject("extensionData").get("value").asString)
    }

    @Test
    fun `a listener that throws does not stop the others`() {
        val received = CountDownLatch(2)
        client.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) = error("listener bug")
            },
        )
        client.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) = received.countDown()
            },
        )
        client.getVM()
        vm.pushEvent("Extension", fixture("event-frame.json"))
        vm.pushEvent("Extension", fixture("event-frame.json"))
        assertTrue(received.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `close fails calls still waiting, sends a close frame and tells listeners`() {
        val closed = CopyOnWriteArrayList<String>()
        client.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) = Unit
                override fun onClosed(reason: String) {
                    closed += reason
                }
            },
        )
        vm.on("never") { FakeVmService.Reply.None }
        val pending = client.callAsync("never")
        vm.awaitRequests("never")

        client.close()

        val error = assertThrows<ExecutionException> { pending.get(2, TimeUnit.SECONDS) }
        assertTrue(error.cause is VmServiceClosedException)
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
        eventually { closed == listOf("closed by Spock") }
        assertFalse(client.isOpen)
        assertThrows<VmServiceClosedException> { client.getVM() }
        client.close()
    }

    @Test
    fun `a lost connection fails calls still waiting and says so`() {
        val closed = CopyOnWriteArrayList<String>()
        client.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) = Unit
                override fun onClosed(reason: String) {
                    closed += reason
                }
            },
        )
        vm.on("never") { FakeVmService.Reply.None }
        val pending = client.callAsync("never")
        vm.awaitRequests("never")

        vm.server.drop()

        val error = assertThrows<ExecutionException> { pending.get(2, TimeUnit.SECONDS) }
        assertTrue(error.cause is VmServiceClosedException)
        eventually { closed.size == 1 }
        assertFalse(client.isOpen)
        assertNotEquals("closed by Spock", client.closeReason)
    }

    @Test
    fun `the VM Service closing the connection is reported with its code`() {
        client.getVM()
        vm.server.sendClose(1001)
        eventually { !client.isOpen }
        assertEquals("closed by the VM Service (1001)", client.closeReason)
    }

    @Test
    fun `a service extension gets the isolate and string arguments`() {
        client.callServiceExtension(FakeVmService.HTTP_LOGGING, "isolates/1111", mapOf("enabled" to "true"))
        val params = vm.requestsFor(FakeVmService.HTTP_LOGGING).single().getAsJsonObject("params")
        assertEquals("isolates/1111", params.get("isolateId").asString)
        assertTrue(params.get("enabled").asJsonPrimitive.isString)
        assertEquals("true", params.get("enabled").asString)
    }

    @Test
    fun `stream subscriptions are plain calls`() {
        assertEquals("Success", client.streamListen("Extension").get("type").asString)
        assertEquals("Success", client.streamCancel("Extension").get("type").asString)
        val params = vm.requestsFor("streamListen").single().getAsJsonObject("params")
        assertEquals("Extension", params.get("streamId").asString)
    }

    @Test
    fun `a connection that cannot open says so without the token`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val error = assertThrows<VmServiceException> {
            val uri = VmServiceUri.parse("ws://127.0.0.1:$freePort/HXKQJZK_Rkw=/ws")
            VmServiceClient.connect(uri, connectTimeoutMs = 2_000)
        }
        assertTrue(error.message!!.contains("ws://127.0.0.1:$freePort/<redacted>/ws"), error.message)
        assertFalse(error.message!!.contains("HXKQJZK"), error.message)
    }
}
