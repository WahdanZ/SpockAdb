package spock.adb.flutter

import com.android.ddmlib.IDevice
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState
import spock.adb.flutter.dtd.DtdAppIdentity
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

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

    /** What `pidof` answers on the device. */
    private var appPids: Set<Long> = setOf(APP_PID)
    private var pidsFail = false

    /** The pid each DTD candidate's VM runs as, by port; the ports probed. */
    private val vmPids = mutableMapOf<Int, Long>()
    private val probed = mutableListOf<Int>()

    private var now = 1_000_000L
    private var probe: DdsProbeResult = DdsProbeResult.Dds
    private var sessionsMade = 0
    private val changes = mutableListOf<FlutterSessionChange>()
    private val parent: Disposable = Disposer.newDisposable()

    private val service = FlutterSessionService(project).apply {
        dtdDiscovery = { path, model ->
            dtdAsked += path to model
            VmServiceDiscovery { dtdFound }
        }
        logcatDiscovery = { _, applicationId ->
            logcatAsked += applicationId
            VmServiceDiscovery { logcatFound }
        }
        appPids = { _, _ ->
            if (pidsFail) throw IOException("adb went away")
            this@FlutterSessionServiceTest.appPids
        }
        dtdIdentity = DtdAppIdentity { uri ->
            probed += uri.port
            vmPids[uri.port]
        }
        ddsProbe = { probe }
        newSession = {
            sessionsMade++
            FlutterSession()
        }
        clock = { now }
        onEdt = { false }
        background = { FutureTask(it, null).also { task -> Thread(task).start() } }
        addListener(parent) { changes += it }
    }

    @AfterEach
    fun tearDown() {
        service.disconnect()
        Disposer.dispose(parent)
        vm.close()
    }

    private class Fixed(source: VmServiceSource, ddsLikely: Boolean, private val uri: VmServiceUri) :
        VmServiceCandidate(source, ddsLikely) {
        override val description: String get() = "$source ${uri.redacted()}"
        override fun open(): VmServiceUri = uri
    }

    private fun candidate(source: VmServiceSource, port: Int = 1, ddsLikely: Boolean = true) =
        Fixed(source, ddsLikely, VmServiceUri.parse("ws://127.0.0.1:$port/T=/ws"))

    /** A DTD candidate whose VM runs as [pid]. */
    private fun dtdApp(port: Int, pid: Long): VmServiceCandidate {
        vmPids[port] = pid
        return candidate(VmServiceSource.DTD, port)
    }

    private fun live(source: VmServiceSource, ddsLikely: Boolean) =
        Fixed(source, ddsLikely, VmServiceUri.parse(vm.uri))

    private fun discover(applicationId: String? = APP_ID, pasted: String? = null) =
        service.discover(device, applicationId, pasted)

    private fun ensure(startedAt: Long? = null, build: FlutterBuild? = null) =
        service.ensureSession(device, APP_ID, startedAt, build)

    // --- discovery ---

    @Test
    fun `DTD is asked for the project with the device's model`() {
        discover()
        assertEquals(listOf("/work/app" to "sdk_gphone64_arm64"), dtdAsked)
    }

    @Test
    fun `order is the confirmed DTD app, then the pasted address`() {
        dtdFound = listOf(dtdApp(1, APP_PID))
        val found = discover(pasted = "http://127.0.0.1:50300/HXKQJZK_Rkw=/").candidates
        assertEquals(listOf(VmServiceSource.DTD, VmServiceSource.PASTED), found.map { it.candidate.source })
        assertEquals(AppIdentity("emulator-5554", APP_ID, APP_PID, IdentityCheck.DTD_PID), found[0].identity)
        assertEquals(IdentityCheck.PASTED_UNVERIFIED, found[1].identity.verifiedBy)
        assertFalse(found[1].identity.verified)
    }

    @Test
    fun `two flavors of one project - only the one running as the selected app's pid is kept`() {
        dtdFound = listOf(dtdApp(1, 111L), dtdApp(2, APP_PID))
        val found = discover().candidates.single()
        assertEquals(2, found.candidate.open().port)
        assertTrue(found.identity.verified)
        assertEquals(listOf(1, 2), probed)
    }

    @Test
    fun `several DTD apps on the app's pid - the best-ranked is taken`() {
        dtdFound = listOf(dtdApp(1, APP_PID), dtdApp(2, APP_PID))
        assertEquals(1, discover().candidates.single().candidate.open().port)
    }

    @Test
    fun `no DTD app runs as the app's pid - logcat, keyed by the pid, is asked instead`() {
        dtdFound = listOf(dtdApp(1, 111L), candidate(VmServiceSource.DTD, port = 2))
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, ddsLikely = false))
        val result = discover()
        assertEquals(listOf(VmServiceSource.LOGCAT), result.candidates.map { it.candidate.source })
        assertEquals(IdentityCheck.LOGCAT_PID, result.candidates.single().identity.verifiedBy)
        assertEquals(APP_PID, result.candidates.single().identity.pid)
        assertTrue(result.notes.any { "None of the 2 apps" in it }, "${result.notes}")
    }

    @Test
    fun `logcat is the last resort - asked only when nothing else found the app`() {
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, ddsLikely = false))
        assertEquals(listOf(VmServiceSource.LOGCAT), discover().candidates.map { it.candidate.source })
        assertEquals(listOf(APP_ID), logcatAsked)

        dtdFound = listOf(dtdApp(1, APP_PID))
        assertEquals(listOf(VmServiceSource.DTD), discover().candidates.map { it.candidate.source })
        assertEquals(1, logcatAsked.size)
    }

    @Test
    fun `an app that is not running - no VM is probed and logcat is not read`() {
        appPids = emptySet()
        dtdFound = listOf(dtdApp(1, APP_PID))
        val result = discover()
        assertTrue(result.isEmpty)
        assertTrue(probed.isEmpty())
        assertTrue(logcatAsked.isEmpty())
        assertTrue(result.notes.single().contains("not running"), "${result.notes}")
    }

    @Test
    fun `adb failing to list the app's pids is said, and nothing unconfirmed is offered`() {
        pidsFail = true
        dtdFound = listOf(dtdApp(1, APP_PID))
        val result = discover(pasted = "http://127.0.0.1:50300/HXKQJZK_Rkw=/")
        assertEquals(listOf(VmServiceSource.PASTED), result.candidates.map { it.candidate.source })
        assertTrue(result.notes.single().contains("adb went away"), "${result.notes}")
    }

    @Test
    fun `no application id - one DTD app is offered unverified, several are not guessed between, logcat never`() {
        dtdFound = listOf(candidate(VmServiceSource.DTD, port = 1))
        val one = discover(applicationId = null)
        assertEquals(IdentityCheck.DTD_NAME_UNVERIFIED, one.candidates.single().identity.verifiedBy)
        assertFalse(one.candidates.single().identity.verified)
        assertTrue(probed.isEmpty())

        dtdFound = listOf(candidate(VmServiceSource.DTD, port = 1), candidate(VmServiceSource.DTD, port = 2))
        val two = discover(applicationId = " ")
        assertTrue(two.isEmpty)
        assertTrue(two.notes.single().contains("select the app"), "${two.notes}")
        assertTrue(logcatAsked.isEmpty())
    }

    @Test
    fun `no project path means no DTD`() {
        service.projectPath = { null }
        discover()
        assertTrue(dtdAsked.isEmpty())
    }

    @Test
    fun `a blank device model is no filter`() {
        val unnamed = device.copy(info = device.info.copy(model = ""))
        service.discover(unnamed, APP_ID)
        assertNull(dtdAsked.single().second)
    }

    @Test
    fun `a failing source is skipped`() {
        service.dtdDiscovery = { _, _ -> VmServiceDiscovery { throw VmServiceException("boom") } }
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, ddsLikely = false))
        assertEquals(listOf(VmServiceSource.LOGCAT), discover().candidates.map { it.candidate.source })
    }

    @Test
    fun `an unusable paste fails before any source is asked`() {
        assertThrows<IllegalArgumentException> { discover(pasted = "nonsense") }
        assertTrue(dtdAsked.isEmpty())
        assertTrue(discover(applicationId = null, pasted = "  ").isEmpty)
    }

    @Test
    fun `never on the EDT`() {
        service.onEdt = { true }
        assertThrows<IllegalStateException> { discover() }
        assertThrows<IllegalStateException> { ensure() }
        assertThrows<IllegalStateException> { service.connect(live(VmServiceSource.PASTED, true)) }
        assertTrue(dtdAsked.isEmpty())
        service.onEdt = { false }
    }

    // --- connect, disconnect, dispose ---

    @Test
    fun `connect holds one session, a second connect replaces the first, and listeners follow`() {
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
            assertEquals(
                listOf(
                    FlutterSessionChange.Connected::class,
                    FlutterSessionChange.Replaced::class,
                    FlutterSessionChange.Disconnected::class,
                ),
                changes.map { it::class },
            )
            assertSame(first, (changes[1] as FlutterSessionChange.Replaced).previous)
        }
    }

    @Test
    fun `a connected identity gets its pid from the VM`() {
        val identity = AppIdentity("emulator-5554", APP_ID, null, IdentityCheck.PASTED_UNVERIFIED)
        service.connect(live(VmServiceSource.PASTED, true), identity)
        assertEquals(12345L, service.identity?.pid)
        assertEquals("emulator-5554", service.identity?.serial)
    }

    @Test
    fun `a lost connection is reported once`() {
        service.connect(live(VmServiceSource.PASTED, true))
        vm.server.drop()
        FakeVmService.eventually { changes.lastOrNull() is FlutterSessionChange.Disconnected }
        assertEquals(1, changes.count { it is FlutterSessionChange.Disconnected })
    }

    @Test
    fun `a failed connect leaves no session`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertThrows<VmServiceException> { service.connect(candidate(VmServiceSource.PASTED, port)) }
        assertNull(service.current)
    }

    @Test
    fun `disconnect ends a connect still opening at once, without waiting for it`() {
        vm.on("getVM") { FakeVmService.Reply.None }
        val connecting = CompletableFuture.supplyAsync {
            runCatching { service.connect(live(VmServiceSource.PASTED, true)) }
        }
        vm.awaitRequests("getVM")
        val started = System.nanoTime()
        service.disconnect()
        val outcome = connecting.get(2, TimeUnit.SECONDS)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertTrue(outcome.exceptionOrNull() is VmServiceException, "$outcome")
        assertNull(service.current)
    }

    @Test
    fun `dispose closes the session on a pooled thread, waits for it, and refuses new connections`() {
        val session = service.connect(PastedUriDiscovery(vm.uri).discover().single())
        val closer = Executors.newSingleThreadExecutor()
        val closedOn = CompletableFuture<Thread>()
        service.background = { task ->
            closer.submit {
                closedOn.complete(Thread.currentThread())
                task.run()
            }
        }
        try {
            service.dispose()
            assertTrue(session.state is SessionState.Disconnected, "waited for the close")
            assertNotSame(Thread.currentThread(), closedOn.get(1, TimeUnit.SECONDS))
            assertNull(service.current)
            assertThrows<IllegalStateException> { service.connect(PastedUriDiscovery(vm.uri).discover().single()) }
        } finally {
            closer.shutdownNow()
        }
    }

    @Test
    fun `dispose waits a bounded time for a close that never finishes`() {
        service.connect(PastedUriDiscovery(vm.uri).discover().single())
        service.closeWaitMs = 50
        service.background = { FutureTask(it, null) }
        val started = System.nanoTime()
        service.dispose()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
    }

    // --- ensureSession ---

    @Test
    fun `ensureSession - not running and release builds are said, not probed`() {
        appPids = emptySet()
        assertEquals(FlutterAttachOutcome.NotRunning("emulator-5554", APP_ID), ensure())
        assertEquals(FlutterAttachOutcome.ReleaseBuild("emulator-5554", APP_ID), ensure(build = FlutterBuild.RELEASE))
        assertEquals(0, sessionsMade)
    }

    @Test
    fun `ensureSession - the DTD app on the app's pid is connected, with its identity`() {
        val app = Fixed(VmServiceSource.DTD, true, VmServiceUri.parse(vm.uri))
        vmPids[app.open().port] = APP_PID
        dtdFound = listOf(app)
        val outcome = ensure() as FlutterAttachOutcome.Connected
        assertEquals(AppIdentity("emulator-5554", APP_ID, APP_PID, IdentityCheck.DTD_PID), outcome.identity)
        assertSame(outcome.session, service.current)
        assertFalse(outcome.reused)
        assertTrue(logcatAsked.isEmpty())
    }

    @Test
    fun `ensureSession - not ready inside the startup window, connected once the address appears`() {
        val first = ensure()
        assertTrue(first is FlutterAttachOutcome.NotReady, "$first")
        assertEquals(0, sessionsMade)

        now += 2_000
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val second = ensure() as FlutterAttachOutcome.Connected
        assertEquals(IdentityCheck.LOGCAT_PID, second.identity.verifiedBy)
        assertEquals(APP_PID, second.identity.pid)
    }

    @Test
    fun `ensureSession - a repeat call while connected returns the same session without reconnecting`() {
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val first = ensure() as FlutterAttachOutcome.Connected
        val again = ensure() as FlutterAttachOutcome.Connected
        assertSame(first.session, again.session)
        assertTrue(again.reused)
        assertEquals(1, sessionsMade)
        assertEquals(1, logcatAsked.size)
    }

    @Test
    fun `ensureSession - a VM without DDS inside the window is not ready, and not probed again at once`() {
        probe = DdsProbeResult.DirectNoDds
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        assertTrue(ensure() is FlutterAttachOutcome.NotReady)
        assertNull(service.current, "a direct VM without DDS is never kept")
        assertEquals(1, sessionsMade)

        now += 1_000
        val waiting = ensure() as FlutterAttachOutcome.NotReady
        assertEquals(FlutterSessionService.DIRECT_PROBE_INTERVAL_MS - 1_000, waiting.retryAfterMs)
        assertEquals(1, sessionsMade)
    }

    @Test
    fun `ensureSession - a VM without DDS past the window is terminal for that pid, and a new pid starts over`() {
        probe = DdsProbeResult.DirectNoDds
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val started = now - FlutterSessionService.STARTUP_GRACE_MS - 1
        val noDds = ensure(startedAt = started) as FlutterAttachOutcome.NoDdsSession
        assertEquals(APP_PID, noDds.identity.pid)
        assertNull(service.current)
        now += 60_000
        assertTrue(ensure() is FlutterAttachOutcome.NoDdsSession)
        assertEquals(1, sessionsMade, "terminal: not probed again")

        appPids = setOf(APP_PID + 1)
        assertTrue(ensure() is FlutterAttachOutcome.NotReady, "a new process gets its own window")
        assertEquals(2, sessionsMade)
    }

    @Test
    fun `ensureSession - unreachable inside the window is not ready, past it a failure`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, port, ddsLikely = false))
        assertTrue(ensure() is FlutterAttachOutcome.NotReady)
        now += FlutterSessionService.STARTUP_GRACE_MS
        assertTrue(ensure() is FlutterAttachOutcome.Failed)
    }

    @Test
    fun `ensureSession - nothing found past the window is not found, with why`() {
        val outcome = ensure(startedAt = now - FlutterSessionService.STARTUP_GRACE_MS) as FlutterAttachOutcome.NotFound
        assertTrue(outcome.reason.contains("VM Service address"), outcome.reason)
    }

    @Test
    fun `ensureSession - the session of a pid that is gone is not reused`() {
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val first = ensure() as FlutterAttachOutcome.Connected
        appPids = setOf(APP_PID + 1)
        val second = ensure() as FlutterAttachOutcome.Connected
        assertNotSame(first.session, second.session)
        assertTrue(first.session.state is SessionState.Disconnected)
    }

    private companion object {
        const val APP_ID = "com.example.app.dev"
        const val APP_PID = 4242L
    }
}
