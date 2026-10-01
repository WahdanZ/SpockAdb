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
    fun `an error's data and an address extension's answer never carry the token`() {
        vm.on("ext.flutter.broken") {
            FakeVmService.Reply.Error(-32_000, "failed at http://127.0.0.1:50300/HXKQJZK_Rkw=/")
        }
        vm.on("ext.flutter.connectedVmServiceUri") {
            FakeVmService.Reply.Result(JsonObject().apply { addProperty("value", "http://127.0.0.1:50300/HXKQJZK_Rkw=/") })
        }
        vm.on("withData") { FakeVmService.Reply.Error(-32_000, "boom", data = "see ws://localhost:50300/HXKQJZK_Rkw=/ws") }

        val error = assertThrows<VmServiceRpcException> { client.call("withData") }
        val extension = assertThrows<VmServiceRpcException> { client.call("ext.flutter.broken") }
        val uri = client.callServiceExtension("ext.flutter.connectedVmServiceUri", "isolates/1111")

        assertFalse(error.data.toString().contains("HXKQJZK"), error.data.toString())
        assertFalse(extension.message!!.contains("HXKQJZK"), extension.message)
        assertEquals("<redacted>", uri.get("value").asString)
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

    @Test
    fun `a VM that answers the upgrade with a redirect to DDS says where, keeping the token out of the message`() {
        FakeVmService().use { dds ->
            vm.server.redirectTo = "ws://127.0.0.1:${dds.server.port}/DdSToKeN123=/ws"

            val error = assertThrows<VmServiceRedirectException> { VmServiceClient.connect(VmServiceUri.parse(vm.uri)) }

            assertEquals("ws://127.0.0.1:${dds.server.port}/DdSToKeN123=/ws", error.target.webSocketUri.toString())
            assertFalse(error.message!!.contains("DdSToKeN123"), error.message)
            assertFalse(error.message!!.contains(FakeVmService.TOKEN), error.message)
        }
    }

    @Test
    fun `a redirect off loopback is refused, not followed`() {
        vm.server.redirectTo = "http://10.0.2.2:5555/DdSToKeN123=/ws"

        val error = assertThrows<VmServiceException> { VmServiceClient.connect(VmServiceUri.parse(vm.uri)) }

        assertFalse(error is VmServiceRedirectException)
        assertTrue(error.message!!.contains("not on this machine's loopback"), error.message)
        assertFalse(error.message!!.contains("DdSToKeN123"), error.message)
    }

    @Test
    fun `malformed JSON, a binary frame and a ping from the server leave the connection working`() {
        client.getVM()
        vm.server.sendText("{not json")
        vm.server.sendBinary(byteArrayOf(0, 1, 2))
        vm.server.sendPing()
        vm.server.sendText("[1, 2]")
        vm.server.sendText("""{"jsonrpc":"2.0","id":{"odd":true},"result":{}}""")

        assertEquals("VM", client.getVM().get("type").asString)
        assertTrue(client.isOpen)
    }

    @Test
    fun `non-ASCII text split mid-character across frames is reassembled intact`() {
        vm.on("unicode") { FakeVmService.Reply.None }
        val pending = client.callAsync("unicode")
        val id = vm.awaitRequests("unicode").single().get("id").asString
        val text = "Grüße ✓ 漢字 🚀 ".repeat(400)

        // 7 parts of a multi-byte text: the byte boundaries fall inside characters.
        vm.server.sendFragmented(response(id).apply { add("result", result(text)) }.toString(), parts = 7)

        assertEquals(text, pending.get(2, TimeUnit.SECONDS).get("value").asString)
    }

    @Test
    fun `a message past the size cap closes the connection, saying why`() {
        val capped = VmServiceClient.connect(VmServiceUri.parse(vm.uri), maxMessageChars = 1_000)
        vm.on("huge") { FakeVmService.Reply.None }
        val pending = capped.callAsync("huge")
        vm.reply(vm.awaitRequests("huge").single().get("id").asString, result("x".repeat(2_000)))

        val error = assertThrows<ExecutionException> { pending.get(2, TimeUnit.SECONDS) }
        assertTrue(error.cause is VmServiceClosedException)
        assertFalse(capped.isOpen)
        assertTrue(capped.closeReason!!.contains("larger than 1000"), capped.closeReason)
    }

    @Test
    fun `a full event queue sheds the oldest logs and never an isolate or debug event`() {
        val small = VmServiceClient.connect(VmServiceUri.parse(vm.uri), maxQueuedEvents = 5)
        val gate = CountDownLatch(1)
        val received = CopyOnWriteArrayList<String>()
        small.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) {
                    if (received.isEmpty()) gate.await(5, TimeUnit.SECONDS)
                    received += "${event.streamId}-${event.timestamp}"
                }
            },
        )
        small.getVM()
        fun event(kind: String, timestamp: Int) = JsonObject().apply {
            addProperty("type", "Event")
            addProperty("kind", kind)
            addProperty("timestamp", timestamp)
        }

        // The first is taken at once and held at the gate; five queue up; the rest overflow.
        (0 until 20).forEach { vm.pushEvent("Logging", event("Logging", it)) }
        (0 until 3).forEach { vm.pushEvent("Debug", event("Resume", it)) }
        eventually(message = "the overflow") { small.droppedEventCount == 17L }
        gate.countDown()

        eventually(message = "the queue to drain") { received.size == 6 }
        assertEquals(
            listOf("Logging-0", "Logging-18", "Logging-19", "Debug-0", "Debug-1", "Debug-2"),
            received,
        )
        small.close()
    }

    @Test
    fun `no event is delivered after onClosed`() {
        val seen = CopyOnWriteArrayList<String>()
        client.addListener(
            object : VmServiceListener {
                override fun onEvent(event: VmServiceEvent) {
                    seen += "event"
                }

                override fun onClosed(reason: String) {
                    seen += "closed"
                }
            },
        )
        client.getVM()
        val pusher = Thread {
            repeat(500) {
                try {
                    vm.pushEvent("Extension", fixture("event-frame.json"))
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }
        pusher.start()
        eventually(message = "events flowing") { seen.size > 20 }
        client.close()
        pusher.join(5_000)

        eventually(message = "the close") { "closed" in seen }
        Thread.sleep(200)
        assertEquals("closed", seen.last())
        assertEquals(1, seen.count { it == "closed" })
    }

    @Test
    fun `failing the pending calls stops their callers and leaves the connection open`() {
        vm.on("stuck") { FakeVmService.Reply.None }
        val pending = client.callAsync("stuck")
        vm.awaitRequests("stuck")

        client.failPendingCalls("session closing")

        val error = assertThrows<ExecutionException> { pending.get(2, TimeUnit.SECONDS) }
        assertTrue(error.cause is VmServiceClosedException)
        assertTrue(client.isOpen)
        assertEquals("VM", client.getVM().get("type").asString)
    }

    @Test
    fun `close racing many calls ends every one of them at once`() {
        vm.on("slow") { FakeVmService.Reply.None }
        client.getVM()
        val outcomes = CopyOnWriteArrayList<String>()
        val callers = (0 until 16).map { index ->
            Thread {
                repeat(20) {
                    try {
                        client.call(if (index % 2 == 0) "slow" else "getVM", timeoutMs = 10_000)
                        outcomes += "answered"
                    } catch (e: VmServiceClosedException) {
                        outcomes += "closed"
                        return@Thread
                    } catch (e: VmServiceException) {
                        outcomes += "other: ${e.javaClass.simpleName}"
                        return@Thread
                    }
                }
            }.apply { start() }
        }
        vm.awaitRequests("slow", count = 8)
        val started = System.nanoTime()

        client.close()
        callers.forEach { it.join(3_000) }

        assertTrue(callers.none { it.isAlive }, "a caller is still waiting")
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        assertTrue(outcomes.none { it.startsWith("other") }, outcomes.toString())
        assertEquals(16, outcomes.count { it == "closed" })
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `answers racing their timeouts leave no call behind and the connection working`() {
        val replier = java.util.concurrent.Executors.newScheduledThreadPool(2)
        vm.on("racy") { FakeVmService.Reply.None }
        val outcomes = CopyOnWriteArrayList<String>()
        try {
            val callers = (0 until 8).map {
                Thread {
                    repeat(25) {
                        val pending = client.callAsync("getVM")
                        pending.get(2, TimeUnit.SECONDS)
                        try {
                            client.call("racy", timeoutMs = 20)
                            outcomes += "answered"
                        } catch (_: VmServiceTimeoutException) {
                            outcomes += "timed out"
                        }
                    }
                }.apply { start() }
            }
            val answered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val feeder = Thread {
                while (callers.any { it.isAlive }) {
                    vm.requestsFor("racy").map { it.get("id").asString }.filter { answered.add(it) }.forEach { id ->
                        replier.schedule({ vm.reply(id, result("late or not")) }, (id.toLong() % 40), TimeUnit.MILLISECONDS)
                    }
                    Thread.sleep(2)
                }
            }.apply { start() }
            callers.forEach { it.join(30_000) }
            feeder.join(5_000)
        } finally {
            replier.shutdown()
            replier.awaitTermination(2, TimeUnit.SECONDS)
        }

        assertEquals(200, outcomes.size)
        eventually(message = "no call left waiting") { client.pendingCount == 0 }
        assertTrue(client.isOpen)
        assertEquals("VM", client.getVM().get("type").asString)
    }

    @Test
    fun `a handshake that completes after connect gave up does not leave a socket open`() {
        vm.server.handshakeDelayMs = 600
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val connecting = Thread {
            try {
                VmServiceClient.connect(VmServiceUri.parse(vm.uri), connectTimeoutMs = 5_000)
            } catch (e: VmServiceException) {
                failure.set(e)
            }
        }
        connecting.start()
        eventually(message = "the handshake to start") { vm.server.handshakePaths.isNotEmpty() }

        connecting.interrupt()
        connecting.join(2_000)

        assertTrue(failure.get() is VmServiceException, "connect should give up when interrupted")
        assertTrue(
            vm.server.connectionEnded.await(3, TimeUnit.SECONDS),
            "the late handshake's socket is still open",
        )
    }
}
