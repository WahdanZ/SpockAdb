package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.DDS_VERSION
import spock.adb.flutter.vmservice.FakeVmService.Companion.HTTP_LOGGING
import spock.adb.flutter.vmservice.FakeVmService.Companion.STRUCTURED_ERRORS
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.NoDdsException
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceRpcException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What answers on connect, DDS or the VM itself, decides whether Spock stays and whether it writes (H1). */
class FlutterSessionConnectionKindTest : FlutterSessionFixture() {

    @Test
    fun `what answers is asked first, before any stream, VM read or extension call`() {
        session.connect(pasted())

        val methods = vm.methods()
        assertEquals(DDS_VERSION, methods.first())
        assertEquals(1, methods.count { it == DDS_VERSION })
        assertTrue(methods.indexOf("getVM") > 0 && methods.indexOf("streamListen") > 0, methods.toString())
    }

    @Test
    fun `a pasted address of a VM with no DDS is left at once, having called nothing else`() {
        vm.dds = false

        val error = assertThrows<NoDdsException> { session.connect(pasted()) }

        assertTrue(error.message!!.contains("without a debugger session"), error.message)
        assertTrue(error.message!!.contains("flutter attach"), error.message)
        assertEquals(listOf(DDS_VERSION), vm.methods())
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
        assertEquals(SessionState.Disconnected(error.message!!), session.state)
        assertNull(session.client)
        assertNull(session.connectionKind)
        assertTrue(session.readOnly)
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `a VM with no DDS found through logcat is left too, and its forward released`() {
        vm.dds = false
        val candidate = tracking(ddsLikely = false)

        assertThrows<NoDdsException> { session.connect(candidate) }

        assertEquals(listOf(DDS_VERSION), vm.methods())
        assertEquals(1, candidate.released)
    }

    @Test
    fun `a VM with no DDS is kept only when asked, and then read but never written`() {
        vm.dds = false
        val candidate = tracking(ddsLikely = false)

        session.connect(candidate, allowDirect = true)

        assertEquals(ConnectionKind.DIRECT_NO_DDS, session.connectionKind)
        assertTrue(session.readOnly)
        assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
        assertEquals(true, session.structuredErrorsEnabled)
        assertEquals(DDS_VERSION, vm.methods().first())
        session.close()

        assertEquals(emptyList<String>(), writes(HTTP_LOGGING) + writes(STRUCTURED_ERRORS))
        assertFalse(vm.httpLogging)
        assertEquals(1, candidate.released)
    }

    @Test
    fun `a candidate not marked as DDS that answers as DDS is a DDS connection`() {
        session.connect(tracking(ddsLikely = false))

        assertEquals(ConnectionKind.DDS, session.connectionKind)
        assertFalse(session.readOnly)
        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
    }

    @Test
    fun `asking with DDS allowed is no different on DDS`() {
        session.connect(pasted(), allowDirect = true)

        assertEquals(ConnectionKind.DDS, session.connectionKind)
        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
    }

    @Test
    fun `a probe that fails any other way fails the connection`() {
        vm.on(DDS_VERSION) { FakeVmService.Reply.Error(-32_000, "Server error") }
        val candidate = tracking()

        val error = assertThrows<VmServiceRpcException> { session.connect(candidate, allowDirect = true) }

        assertEquals(DDS_VERSION, error.method)
        assertEquals(listOf(DDS_VERSION), vm.methods())
        assertEquals(SessionState.Disconnected(error.message!!), session.state)
        assertNull(session.connectionKind)
        assertEquals(1, candidate.released)
    }

    @Test
    fun `a direct address the VM redirects to DDS is followed, and the connection is then DDS's`() {
        FakeVmService().use { dds ->
            vm.dds = false
            vm.server.redirectTo = dds.uri
            val candidate = tracking(ddsLikely = false)

            session.connect(candidate)

            assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
            assertEquals(ConnectionKind.DDS, session.connectionKind)
            assertFalse(session.readOnly)
            assertEquals(DDS_VERSION, dds.methods().first())
            val ddsWrites = dds.requestsFor(HTTP_LOGGING).map { it.getAsJsonObject("params") }
                .filter { it.has("enabled") }.map { it.get("enabled").asString }
            assertEquals(listOf("true"), ddsWrites)
            assertTrue(vm.requests.isEmpty())

            session.close()

            assertFalse(dds.httpLogging)
            assertEquals(1, candidate.released)
        }
    }

    @Test
    fun `logging switched on through DDS is switched off through DDS, never through a direct connection`() {
        session.connect(pasted())
        vm.server.drop()
        eventually { session.state is SessionState.Disconnected }

        vm.dds = false
        session.connect(tracking(ddsLikely = false), allowDirect = true)
        session.close()
        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))

        vm.dds = true
        session.connect(pasted())
        session.close()
        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `no listener hears of a VM with no DDS while its socket is still open`() {
        vm.dds = false
        val clients = CopyOnWriteArrayList<VmServiceClient>()
        val watched = FlutterSession(connector = { VmServiceClient.connect(it).also(clients::add) })
        val openWhenTold = CopyOnWriteArrayList<Boolean>()
        watched.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    if (state is SessionState.Disconnected) openWhenTold += clients.any { it.isOpen }
                }
            },
        )

        assertThrows<NoDdsException> { watched.connect(pasted()) }

        assertEquals(listOf(false), openWhenTold.toList())
        assertEquals(listOf(DDS_VERSION), vm.methods())
    }

    @Test
    fun `a connect closed before its probe answered asks on close, and restores only through DDS`() {
        session.connect(pasted())
        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
        vm.server.drop()
        eventually { session.state is SessionState.Disconnected }
        val probes = AtomicInteger()
        vm.on(DDS_VERSION) {
            if (probes.incrementAndGet() == 1) FakeVmService.Reply.None else ddsVersion()
        }

        val connecting = Thread { runCatching { session.connect(pasted()) } }
        connecting.start()
        eventually(message = "the stuck probe") { probes.get() == 1 }
        session.close()
        connecting.join(5_000)

        assertEquals(2, probes.get())
        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `a connect closed before its probe answered leaves logging alone when the answer is the VM itself`() {
        session.connect(pasted())
        vm.server.drop()
        eventually { session.state is SessionState.Disconnected }
        val probes = AtomicInteger()
        vm.on(DDS_VERSION) {
            when (probes.incrementAndGet()) {
                1 -> FakeVmService.Reply.None
                else -> FakeVmService.Reply.Error(FakeVmService.METHOD_NOT_FOUND, "Method not found")
            }
        }

        val connecting = Thread { runCatching { session.connect(pasted()) } }
        connecting.start()
        eventually(message = "the stuck probe") { probes.get() == 1 }
        session.close()
        connecting.join(5_000)

        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
    }

    private fun ddsVersion() = FakeVmService.Reply.Result(
        JsonObject().apply {
            addProperty("type", "Version")
            addProperty("major", 1)
        },
    )
}
