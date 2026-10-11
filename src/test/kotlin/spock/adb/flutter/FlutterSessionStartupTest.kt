package spock.adb.flutter

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState
import spock.adb.flutter.vmservice.AnnouncedCandidate
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceUri
import java.util.concurrent.FutureTask

/**
 * When [FlutterSessionService.ensureSession] may ask an app's VM directly, through its start:
 * the window anchored on the VM's announcement, and a Flutter tool's forward keeping Spock off
 * the VM (spike S10). The device gate of 2026-10-02 (emulator-5554, Flutter 3.22.2): the VM
 * announced itself 6 s after its process started, DDS came after that.
 */
class FlutterSessionStartupTest {

    private val vm = FakeVmService()
    private val project = mockk<Project> { every { basePath } returns "/work/app" }
    private val device = ConnectedDevice(
        mockk<IDevice>(relaxed = true),
        DeviceInfo.unknown("emulator-5554").copy(model = "sdk_gphone64_arm64", state = DeviceState.ONLINE),
    )

    private var logcatFound: List<VmServiceCandidate> = emptyList()
    private var now = 1_000_000L
    private var sessionsMade = 0

    /** Whether a Flutter tool holds a forward to the VM's device port; null when adb cannot say. */
    private var foreign: Boolean? = false

    /** When the VM's announcement was logged, on the host's clock; null when unreadable. */
    private var vmLineAt: Long? = null
    private val logged = mutableListOf<String>()

    /** Whether Android's freezer holds the app; null when the device does not say. */
    private var isFrozen: Boolean? = false

    private val service = FlutterSessionService(project).apply {
        dtdDiscovery = { _, _ -> emptyList() }
        logcatDiscovery = { _, _ -> VmServiceDiscovery { logcatFound } }
        appPids = { _, _ -> setOf(APP_PID) }
        processTiming = { _, _ -> null }
        newSession = {
            sessionsMade++
            FlutterSession()
        }
        clock = { now }
        onEdt = { false }
        background = { FutureTask(it, null).also { task -> Thread(task).start() } }
        measureDeviceTime = { null }
        // Never the real adb server on the machine running the tests.
        foreignForward = { _, _ -> foreign }
        stampToHost = { _, _ -> vmLineAt }
        frozen = { _, _, _ -> isFrozen }
        info = { line -> synchronized(logged) { logged += line } }
    }

    @AfterEach
    fun tearDown() {
        service.disconnect()
        vm.close()
    }

    private fun ensure(startedAt: Long? = null) =
        service.ensureSession(device, APP_ID, startedAt, null, recordHttp = true)

    /** What the engine announced in logcat: the fake VM, its device port, and the line's stamp. */
    private class Announced(private val uri: VmServiceUri, port: Int) :
        AnnouncedCandidate(port, "10-02 14:37:24.226") {
        override val description: String get() = "port $devicePort (from logcat) ${uri.redacted()}"
        override fun open(): VmServiceUri = uri
    }

    private fun announced() = Announced(VmServiceUri.parse(vm.uri), VM_DEVICE_PORT)

    /** Direct connections made while the VM had no DDS. */
    private var probesWithoutDds = 0

    /**
     * One call a second from the process start, for up to [seconds], until a final answer; the VM
     * announces itself at [announceAt] s, a Flutter tool forwards it from [forwardAt] s, and DDS
     * attaches at [ddsAt] s. What each call answered.
     */
    private fun startUp(
        seconds: Int,
        announceAt: Int,
        ddsAt: Int?,
        forwardAt: Int? = null,
    ): List<Pair<Int, FlutterAttachOutcome>> {
        val start = now
        vmLineAt = start + announceAt * 1_000L
        val answers = mutableListOf<Pair<Int, FlutterAttachOutcome>>()
        for (second in 0..seconds) {
            now = start + second * 1_000L
            logcatFound = if (second >= announceAt) listOf(announced()) else emptyList()
            vm.dds = ddsAt != null && second >= ddsAt
            if (forwardAt != null) foreign = second >= forwardAt
            val before = sessionsMade
            val outcome = ensure(startedAt = start)
            if (!vm.dds) probesWithoutDds += sessionsMade - before
            answers += second to outcome
            if (outcome is FlutterAttachOutcome.Connected || outcome is FlutterAttachOutcome.NoDdsSession) break
        }
        return answers
    }

    @Test
    fun `a VM announced 6 s after its process, DDS 4 s later - not ready, then connected`() {
        val answers = startUp(seconds = 20, announceAt = 6, ddsAt = 10)

        assertTrue(answers.none { it.second is FlutterAttachOutcome.NoDdsSession }, "$answers")
        assertTrue(answers.first().second is FlutterAttachOutcome.NotReady)
        assertTrue(answers.last().second is FlutterAttachOutcome.Connected, "$answers")
    }

    @Test
    fun `the window runs from the VM's announcement, not the process start`() {
        // DDS 8 s after a 6 s announcement: past a window counted from the process start (15 s),
        // inside one counted from the announcement (21 s).
        val answers = startUp(seconds = 25, announceAt = 6, ddsAt = 16)

        assertTrue(answers.none { it.second is FlutterAttachOutcome.NoDdsSession }, "$answers")
        assertTrue(answers.last().second is FlutterAttachOutcome.Connected, "$answers")
    }

    @Test
    fun `the VM is not asked in the first seconds after it announces itself`() {
        startUp(seconds = 8, announceAt = 6, ddsAt = null)

        assertEquals(0, sessionsMade, "no direct connection before 3 s after the announcement")
    }

    @Test
    fun `a Flutter tool forwarding the VM keeps Spock off it while it settles, and says so`() {
        val settle = (AppStartup.TOOL_SETTLE_MS / 1_000).toInt()
        val answers = startUp(seconds = 6 + settle - 1, announceAt = 6, ddsAt = null, forwardAt = 6)

        assertEquals(0, sessionsMade, "not connected to the VM while the tool may be starting DDS")
        val last = answers.last().second as FlutterAttachOutcome.NotReady
        assertTrue(last.reason.contains("A Flutter tool is attaching"), last.reason)
        assertTrue(logged.any { "a Flutter tool forwards the VM Service's device port" in it }, "$logged")
    }

    @Test
    fun `DDS 12 s in, a tool forwarding - connected by about 14 s, with no probe before DDS`() {
        val answers = startUp(seconds = 30, announceAt = 2, ddsAt = 12, forwardAt = 4)

        val (second, outcome) = answers.last()
        assertTrue(outcome is FlutterAttachOutcome.Connected, "$answers")
        assertTrue(second <= 15, "connected at $second s")
        assertTrue(probesWithoutDds <= 1, "$probesWithoutDds probes before DDS")
    }

    @Test
    fun `a tool still attaching is asked again at the probe spacing, then the normal rules after the cap`() {
        val answers = startUp(seconds = 90, announceAt = 6, ddsAt = null, forwardAt = 6)

        val (second, outcome) = answers.last()
        assertTrue(outcome is FlutterAttachOutcome.NoDdsSession, "$answers")
        val cap = 6 + AppStartup.TOOL_ATTACH_CAP_MS / 1_000
        assertTrue(second >= cap, "no debugger session only after the cap: $second s")
        val probesBeforeCap = sessionsMade
        assertTrue(probesBeforeCap > 1, "asked again while the tool attached")
        val spacing = (AppStartup.DIRECT_PROBE_INTERVAL_MS / 1_000).toInt()
        assertTrue(probesBeforeCap <= (second - 16) / spacing + 1, "$probesBeforeCap probes by $second s")
    }

    @Test
    fun `no tool forwarding and no DDS past the window is no debugger session`() {
        val answers = startUp(seconds = 30, announceAt = 6, ddsAt = null)

        assertTrue(answers.last().second is FlutterAttachOutcome.NoDdsSession, "$answers")
        assertTrue(answers.dropLast(1).none { it.second is FlutterAttachOutcome.NoDdsSession })
    }

    @Test
    fun `after no debugger session, a tool that forwards the VM is attached to`() {
        startUp(seconds = 30, announceAt = 6, ddsAt = null)
        val probes = sessionsMade
        assertTrue(ensure() is FlutterAttachOutcome.NoDdsSession)
        assertEquals(probes, sessionsMade, "the verdict stands with no VM contact")

        foreign = true
        vm.dds = true
        val held = ensure()
        now += AppStartup.TOOL_SETTLE_MS

        assertTrue(held is FlutterAttachOutcome.NotReady, "$held")
        assertTrue(ensure() is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `no announcement yet is not ready for as long as a VM may take to start`() {
        val start = now
        now = start + AppStartup.STARTUP_GRACE_MS - 1
        assertTrue(ensure(startedAt = start) is FlutterAttachOutcome.NotReady)
        now = start + AppStartup.STARTUP_GRACE_MS
        assertTrue(ensure(startedAt = start) is FlutterAttachOutcome.NotFound)
    }

    @Test
    fun `its decisions are logged, with no address or token`() {
        startUp(seconds = 12, announceAt = 6, ddsAt = null, forwardAt = 6)

        assertTrue(
            logged.any { it.startsWith("Flutter attach for $APP_ID on emulator-5554: NotReady") },
            "$logged",
        )
        assertTrue(logged.none { it.contains(FakeVmService.TOKEN) || it.contains("ws://") }, "$logged")
    }

    @Test
    fun `a frozen app is not ready, and its VM is not asked`() {
        isFrozen = true
        logcatFound = listOf(announced())
        vmLineAt = now - 59_000
        vm.dds = true

        val outcome = ensure(startedAt = now - 60_000) as FlutterAttachOutcome.NotReady

        assertTrue(outcome.frozen)
        assertTrue(outcome.reason.contains("cached-app freezer"), outcome.reason)
        assertEquals(0, sessionsMade)

        isFrozen = false
        assertTrue(ensure() is FlutterAttachOutcome.Connected, "thawed: connected")
    }

    @Test
    fun `a device that does not say whether the app is frozen is asked as before`() {
        isFrozen = null
        logcatFound = listOf(announced())
        vmLineAt = now - 59_000
        vm.dds = true

        assertTrue(ensure(startedAt = now - 60_000) is FlutterAttachOutcome.Connected)
    }

    private companion object {
        const val APP_ID = "com.example.app.dev"

        /** The pid in the recorded `getVM`. */
        const val APP_PID = 12345L
        const val VM_DEVICE_PORT = 38551
    }
}
