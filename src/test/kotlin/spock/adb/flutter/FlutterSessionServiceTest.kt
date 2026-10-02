package spock.adb.flutter

import com.android.ddmlib.IDevice
import com.google.gson.JsonArray
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
import spock.adb.flutter.dtd.DtdCandidate
import spock.adb.flutter.dtd.FakeDtd
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.NoDdsException
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceProbe
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
    private var dtdFound: List<DtdCandidate> = emptyList()
    private var logcatAsked = mutableListOf<String>()
    private var logcatFound: List<VmServiceCandidate> = emptyList()
    private var logcatDelayMs = 0L

    /** What `pidof` answers on the device, and when each process started there. */
    private var appPids: Set<Long> = setOf(APP_PID)
    private var pidsFail = false
    private val timings = mutableMapOf<Long, ProcessTiming?>(APP_PID to ProcessTiming(PROCESS_START, null))

    /** Each DTD candidate's VM, by port; the ports probed. */
    private val vms = mutableMapOf<Int, DtdAppIdentity.VmProcess>()
    private val probed = mutableListOf<Int>()

    private var now = 1_000_000L
    private var sessionsMade = 0
    private val changes = mutableListOf<FlutterSessionChange>()
    private val parent: Disposable = Disposer.newDisposable()

    private val service = FlutterSessionService(project).apply {
        dtdDiscovery = { path, model ->
            dtdAsked += path to model
            dtdFound
        }
        logcatDiscovery = { _, applicationId ->
            logcatAsked += applicationId
            Thread.sleep(logcatDelayMs)
            VmServiceDiscovery { logcatFound }
        }
        appPids = { _, _ ->
            if (pidsFail) throw IOException("adb went away")
            this@FlutterSessionServiceTest.appPids
        }
        processTiming = { _, pid -> timings[pid] }
        dtdIdentity = DtdAppIdentity(vmProcess = { uri ->
            probed += uri.port
            vms[uri.port]
        })
        newSession = {
            synchronized(this@FlutterSessionServiceTest) { sessionsMade++ }
            FlutterSession()
        }
        clock = { now }
        onEdt = { false }
        // Never the real adb server on the machine running the tests.
        foreignForward = { _, _ -> false }
        stampToHost = { _, _ -> null }
        background = { FutureTask(it, null).also { task -> Thread(task).start() } }
        addListener(parent) { synchronized(changes) { changes += it } }
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

    /** A DTD candidate whose VM runs as [pid], started with the app's process unless said otherwise. */
    private fun dtdApp(port: Int, pid: Long, start: Long? = PROCESS_START + 500): DtdCandidate {
        vms[port] = DtdAppIdentity.VmProcess(pid, start)
        return FakeDtd.candidate(VmServiceUri.parse("ws://127.0.0.1:$port/T=/ws"))
    }

    /** The fake VM, listed by a DTD as the app's. */
    private fun liveDtdApp(fake: FakeVmService = vm): DtdCandidate {
        val uri = VmServiceUri.parse(fake.uri)
        vms[uri.port] = DtdAppIdentity.VmProcess(APP_PID, PROCESS_START + 500)
        return FakeDtd.candidate(uri)
    }

    private fun live(source: VmServiceSource, ddsLikely: Boolean, fake: FakeVmService = vm) =
        Fixed(source, ddsLikely, VmServiceUri.parse(fake.uri))

    /** A fake VM whose `getVM` says [pid]. */
    private fun vmWithPid(pid: Long) = FakeVmService().apply {
        on("getVM") {
            FakeVmService.Reply.Result(
                FakeVmService.fixture("getVM.json").apply {
                    addProperty("pid", pid)
                    add("isolates", JsonArray().apply { add(FakeVmService.isolateRef(FakeVmService.UI_ISOLATE)) })
                },
            )
        }
    }

    private fun discover(applicationId: String? = APP_ID, pasted: String? = null) =
        service.discover(device, applicationId, pasted)

    private fun ensure(startedAt: Long? = null, build: FlutterBuild? = null) =
        service.ensureSession(device, APP_ID, startedAt, build, recordHttp = true)

    private fun changeKinds() = synchronized(changes) { changes.map { it::class.simpleName } }

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
        assertEquals(AppIdentity("emulator-5554", APP_ID, APP_PID, IdentityCheck.DTD_PID_START), found[0].identity)
        assertEquals(IdentityCheck.PASTED_UNVERIFIED, found[1].identity.verifiedBy)
        assertFalse(found[1].identity.verified)
    }

    @Test
    fun `two flavors of one project - only the one that is the selected app's process is kept`() {
        dtdFound = listOf(dtdApp(1, 111L), dtdApp(2, APP_PID))
        val found = discover().candidates.single()
        assertEquals(2, found.candidate.open().port)
        assertTrue(found.identity.verified)
        assertEquals(listOf(1, 2), probed)
    }

    @Test
    fun `two emulators of one snapshot - the same pid started at another time is the other device's app`() {
        dtdFound = listOf(dtdApp(1, APP_PID, start = PROCESS_START - 600_000), dtdApp(2, APP_PID))
        assertEquals(2, discover().candidates.single().candidate.open().port)
    }

    @Test
    fun `several DTD apps passing as the selected app are offered as ambiguous, not picked by rank`() {
        dtdFound = listOf(dtdApp(1, APP_PID), dtdApp(2, APP_PID, start = PROCESS_START + 900))
        val result = discover()
        assertTrue(result.candidates.none { it.candidate.source == VmServiceSource.DTD })
        assertEquals(listOf(1, 2), result.ambiguous.map { it.candidate.open().port })
        assertTrue(result.ambiguous.all { it.identity.verifiedBy == IdentityCheck.DTD_PID_AMBIGUOUS })
    }

    @Test
    fun `a device whose proc is refused - the pid alone, marked weaker`() {
        timings[APP_PID] = null
        dtdFound = listOf(dtdApp(1, APP_PID))
        assertEquals(IdentityCheck.DTD_PID_ONLY, discover().candidates.single().identity.verifiedBy)
    }

    @Test
    fun `no DTD app is the app's process - logcat, keyed by the pid, is asked instead`() {
        dtdFound = listOf(dtdApp(1, 111L), FakeDtd.candidate(VmServiceUri.parse("ws://127.0.0.1:2/T=/ws")))
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
        dtdFound = listOf(dtdApp(1, APP_PID))
        val one = discover(applicationId = null)
        assertEquals(IdentityCheck.DTD_NAME_UNVERIFIED, one.candidates.single().identity.verifiedBy)
        assertFalse(one.candidates.single().identity.verified)
        assertTrue(probed.isEmpty())

        dtdFound = listOf(dtdApp(1, APP_PID), dtdApp(2, APP_PID))
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
        service.dtdDiscovery = { _, _ -> throw VmServiceException("boom") }
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

    // --- connect, disconnect, dispose, listeners ---

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
            assertEquals(listOf("Connected", "Replaced", "Disconnected"), changeKinds())
            assertSame(first, (changes[1] as FlutterSessionChange.Replaced).previous)
        }
    }

    @Test
    fun `a connected identity gets its pid from the VM`() {
        val identity = AppIdentity("emulator-5554", APP_ID, null, IdentityCheck.PASTED_UNVERIFIED)
        service.connect(live(VmServiceSource.PASTED, true), identity)
        assertEquals(APP_PID, service.identity?.pid)
        assertEquals("emulator-5554", service.identity?.serial)
    }

    @Test
    fun `a lost session is reported once - not again on disconnect`() {
        service.connect(live(VmServiceSource.PASTED, true))
        vm.server.drop()
        FakeVmService.eventually { changeKinds().lastOrNull() == "Disconnected" }
        service.disconnect()
        assertEquals(listOf("Connected", "Disconnected"), changeKinds())
    }

    @Test
    fun `a session after a lost one is Connected, not Replaced`() {
        service.connect(live(VmServiceSource.PASTED, true))
        vm.server.drop()
        FakeVmService.eventually { changeKinds().lastOrNull() == "Disconnected" }
        FakeVmService().use { other ->
            service.connect(live(VmServiceSource.PASTED, true, other))
            assertEquals(listOf("Connected", "Disconnected", "Connected"), changeKinds())
        }
    }

    @Test
    fun `a listener whose parent is already disposed is not registered`() {
        val gone = Disposer.newDisposable().also(Disposer::dispose)
        val heard = mutableListOf<FlutterSessionChange>()
        service.addListener(gone) { heard += it }
        service.connect(live(VmServiceSource.PASTED, true))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `connect refuses a VM with no DDS unless asked to keep it, read-only`() {
        vm.dds = false
        assertThrows<NoDdsException> { service.connect(live(VmServiceSource.PASTED, true)) }
        assertNull(service.current)
        val kept = service.connect(live(VmServiceSource.PASTED, true), allowDirect = true)
        assertTrue(kept.readOnly)
        assertSame(kept, service.current)
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
    fun `ensureSession - the DTD app that is the app's process is connected at once, with its identity`() {
        dtdFound = listOf(liveDtdApp())
        val outcome = ensure() as FlutterAttachOutcome.Connected
        assertEquals(AppIdentity("emulator-5554", APP_ID, APP_PID, IdentityCheck.DTD_PID_START), outcome.identity)
        assertSame(outcome.session, service.current)
        assertFalse(outcome.reused)
        assertTrue(logcatAsked.isEmpty())
    }

    @Test
    fun `ensureSession - DTD apps that cannot be told apart are ambiguous`() {
        dtdFound = listOf(dtdApp(1, APP_PID), dtdApp(2, APP_PID))
        val outcome = ensure() as FlutterAttachOutcome.Ambiguous
        assertEquals(2, outcome.candidates.size)
        assertEquals(0, sessionsMade)
    }

    @Test
    fun `ensureSession - not ready inside the startup window, connected once the address appears`() {
        val first = ensure()
        assertTrue(first is FlutterAttachOutcome.NotReady, "$first")
        assertEquals(0, sessionsMade)

        now += FlutterSessionService.DIRECT_PROBE_MIN_AGE_MS
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val second = ensure() as FlutterAttachOutcome.Connected
        assertEquals(IdentityCheck.LOGCAT_PID, second.identity.verifiedBy)
        assertEquals(APP_PID, second.identity.pid)
    }

    @Test
    fun `ensureSession - the direct VM is not connected to in the first seconds after the start`() {
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val early = ensure(startedAt = now - 1_000) as FlutterAttachOutcome.NotReady
        assertEquals(FlutterSessionService.DIRECT_PROBE_MIN_AGE_MS - 1_000, early.retryAfterMs)
        assertEquals(0, sessionsMade)
    }

    @Test
    fun `ensureSession - a repeat call while connected returns the same session without reconnecting`() {
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val first = ensure(startedAt = now - 5_000) as FlutterAttachOutcome.Connected
        val again = ensure() as FlutterAttachOutcome.Connected
        assertSame(first.session, again.session)
        assertTrue(again.reused)
        assertEquals(1, sessionsMade)
        assertEquals(1, logcatAsked.size)
    }

    @Test
    fun `ensureSession - two calls at once make one connection, and both are connected`() {
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        logcatDelayMs = 200
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) {
                pool.submit<FlutterAttachOutcome> {
                    start.await()
                    ensure(startedAt = now - 5_000)
                }
            }
            start.countDown()
            val outcomes = calls.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(outcomes.all { it is FlutterAttachOutcome.Connected }, "$outcomes")
            assertEquals(1, sessionsMade)
            assertEquals(1, outcomes.count { (it as FlutterAttachOutcome.Connected).reused })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `ensureSession - a VM without DDS inside the window is not ready, and not probed again at once`() {
        vm.dds = false
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val old = now - FlutterSessionService.DIRECT_PROBE_MIN_AGE_MS
        assertTrue(ensure(startedAt = old) is FlutterAttachOutcome.NotReady)
        assertNull(service.current, "a direct VM without DDS is never kept")
        assertEquals(1, sessionsMade)

        now += 1_000
        val waiting = ensure() as FlutterAttachOutcome.NotReady
        assertEquals(FlutterSessionService.DIRECT_PROBE_INTERVAL_MS - 1_000, waiting.retryAfterMs)
        assertEquals(1, sessionsMade)
    }

    @Test
    fun `ensureSession - no DDS is not ready inside the window, then terminal after it, with no further probe`() {
        vm.dds = false
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        val started = now - FlutterSessionService.DIRECT_PROBE_MIN_AGE_MS
        assertTrue(ensure(startedAt = started) is FlutterAttachOutcome.NotReady)
        assertEquals(1, vm.requestsFor(VmServiceProbe.DDS_VERSION).size)
        assertTrue(vm.requestsFor("streamListen").isEmpty(), "refused before anything else was called")

        now = started + FlutterSessionService.STARTUP_GRACE_MS
        val noDds = ensure() as FlutterAttachOutcome.NoDdsSession
        assertTrue(noDds.message.contains("without a debugger session"), noDds.message)
        assertEquals(2, vm.requestsFor(VmServiceProbe.DDS_VERSION).size)

        now += 60_000
        assertTrue(ensure() is FlutterAttachOutcome.NoDdsSession)
        assertEquals(2, vm.requestsFor(VmServiceProbe.DDS_VERSION).size, "no further direct probe")
        assertNull(service.current)
    }

    @Test
    fun `ensureSession - a VM without DDS past the window is terminal for that pid, and a new pid starts over`() {
        vm.dds = false
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
        assertEquals(1, sessionsMade, "and its VM is not asked in its first seconds")
    }

    @Test
    fun `ensureSession - after no DDS, a later flutter attach is found through the DTD`() {
        vm.dds = false
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        assertTrue(ensure(startedAt = now - 60_000) is FlutterAttachOutcome.NoDdsSession)

        vm.dds = true
        dtdFound = listOf(liveDtdApp())
        val attached = ensure() as FlutterAttachOutcome.Connected
        assertEquals(IdentityCheck.DTD_PID_START, attached.identity.verifiedBy)
    }

    @Test
    fun `ensureSession - disconnect and resetAttach forget a no-DDS verdict`() {
        vm.dds = false
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        assertTrue(ensure(startedAt = now - 60_000) is FlutterAttachOutcome.NoDdsSession)
        service.resetAttach()
        vm.dds = true
        assertTrue(ensure(startedAt = now - 60_000) is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `ensureSession - the device's process age decides the window when the caller does not`() {
        timings[APP_PID] = ProcessTiming(PROCESS_START, ageMs = 3_600_000)
        vm.dds = false
        logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false))
        assertTrue(ensure() is FlutterAttachOutcome.NoDdsSession, "an app running for an hour is not starting")
    }

    @Test
    fun `ensureSession - a logcat VM on another pid is not kept`() {
        vmWithPid(999L).use { other ->
            logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false, fake = other))
            val outcome = ensure(startedAt = now - 60_000)
            assertTrue(outcome is FlutterAttachOutcome.Failed, "$outcome")
            assertNull(service.current)
        }
    }

    @Test
    fun `ensureSession - unreachable inside the window is not ready, past it a failure`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        logcatFound = listOf(candidate(VmServiceSource.LOGCAT, port, ddsLikely = false))
        val old = now - FlutterSessionService.DIRECT_PROBE_MIN_AGE_MS
        assertTrue(ensure(startedAt = old) is FlutterAttachOutcome.NotReady)
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
        val first = ensure(startedAt = now - 5_000) as FlutterAttachOutcome.Connected
        vmWithPid(APP_PID + 1).use { restarted ->
            appPids = setOf(APP_PID + 1)
            logcatFound = listOf(live(VmServiceSource.LOGCAT, ddsLikely = false, fake = restarted))
            val second = ensure(startedAt = now - 5_000) as FlutterAttachOutcome.Connected
            assertNotSame(first.session, second.session)
            assertTrue(first.session.state is SessionState.Disconnected)
        }
    }

    @Test
    fun `ensureSession - the Settings switch decides whether a new session records HTTP`() {
        dtdFound = listOf(liveDtdApp())
        val outcome = service.ensureSession(device, APP_ID, null, null, recordHttp = false)
            as FlutterAttachOutcome.Connected

        FakeVmService.eventually(message = "the HTTP decision") { outcome.session.snapshot.httpRecording != null }
        assertEquals(HttpRecording.Off(HttpRecording.Reason.SETTING_OFF), outcome.session.snapshot.httpRecording)
        assertTrue(vm.requestsFor(FakeVmService.HTTP_LOGGING).none { it.getAsJsonObject("params").has("enabled") })
    }

    @Test
    fun `ensureSession - a new session's device clock is measured once, on the device it was verified on`() {
        val measured = CopyOnWriteArrayList<IDevice>()
        val time = DeviceTime(-1_000, 47, java.time.ZoneOffset.ofHours(2))
        service.measureDeviceTime = { device ->
            measured += device
            time
        }
        dtdFound = listOf(liveDtdApp())

        val first = ensure() as FlutterAttachOutcome.Connected
        assertEquals(time, first.session.deviceTime.await(FakeVmService.AWAIT_MS))
        val again = ensure() as FlutterAttachOutcome.Connected

        assertTrue(again.reused)
        assertEquals(listOf(device.device), measured.toList())
    }

    @Test
    fun `ensureSession - a clock that cannot be read leaves the slot done and empty`() {
        service.measureDeviceTime = { error("adb went away") }
        dtdFound = listOf(liveDtdApp())

        val outcome = ensure() as FlutterAttachOutcome.Connected

        FakeVmService.eventually(message = "the measurement to end") { outcome.session.deviceTime.done }
        assertNull(outcome.session.deviceTime.current)
    }

    @Test
    fun `listeners hear of a session before it connects`() {
        val created = CopyOnWriteArrayList<FlutterSession>()
        service.addListener(
            parent,
            object : FlutterSessionServiceListener {
                override fun sessionChanged(change: FlutterSessionChange) = Unit

                override fun sessionCreated(session: FlutterSession) {
                    assertTrue(session.state is SessionState.Disconnected, "not connected yet")
                    created += session
                }
            },
        )
        dtdFound = listOf(liveDtdApp())

        val outcome = ensure() as FlutterAttachOutcome.Connected

        assertEquals(listOf(outcome.session), created.toList())
    }

    private companion object {
        const val APP_ID = "com.example.app.dev"

        /** The pid in the recorded `getVM`. */
        const val APP_PID = 12345L
        const val PROCESS_START = 1_727_776_799_500L
    }
}
