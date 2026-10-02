package spock.adb.flutter

import com.android.ddmlib.IDevice
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class FlutterFollowerTest {

    /** Tasks run only when the test says, in the order they were scheduled. */
    private class ManualScheduler : FlutterFollower.Scheduler {
        class Task(val delayMs: Long, val runnable: Runnable) {
            val future = FutureTask(runnable, null)
        }

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, task: Runnable): Future<*> =
            Task(delayMs, task).also { tasks += it }.future

        /** Runs the next task that was not cancelled; its delay. */
        fun runNext(): Long {
            val next = tasks.first { !it.future.isDone }
            next.future.run()
            return next.delayMs
        }

        val due: Int get() = tasks.count { !it.future.isDone }
    }

    private class Call(val serial: String, val applicationId: String, val startedAt: Long?, val recordHttp: Boolean)

    private val device = device("emulator-5554")
    private val other = device("emulator-5556")
    private val scheduler = ManualScheduler()
    private val calls = mutableListOf<Call>()
    private val outcomes = ArrayDeque<FlutterAttachOutcome>()
    private var builds = mutableMapOf<String, FlutterBuild?>(APP to FlutterBuild.DEBUG, OTHER_APP to FlutterBuild.DEBUG)
    private var pids: Set<Long>? = setOf(4242)
    private var recordHttp = true

    /** Runs inside the attach call, as if the selection changed while it was connecting. */
    private var duringAttach: () -> Unit = {}

    /** The follower's clock: the test moves it as the scheduler's delays pass. */
    private var now = 1_000_000L
    private val logged = mutableListOf<String>()

    private val follower = FlutterFollower(
        attach = { device, app, startedAt, _, record ->
            calls += Call(device.serialNumber, app, startedAt, record)
            duringAttach()
            outcomes.removeFirstOrNull() ?: connected(app)
        },
        buildOf = { _, app -> builds[app] },
        pidsOf = { _, _ -> pids },
        recordHttp = { recordHttp },
        scheduler = scheduler,
        background = { CompletableFuture.runAsync(it) },
        clock = { now },
        info = { synchronized(logged) { logged += it } },
    )

    /** Runs the next task after its delay has passed on the follower's clock; its delay. */
    private fun runNext(): Long {
        val next = scheduler.tasks.first { !it.future.isDone }
        now += next.delayMs
        next.future.run()
        return next.delayMs
    }

    @AfterEach
    fun tearDown() = follower.dispose()

    @Test
    fun `a selection change asks once`() {
        follower.follow(device, APP)
        runNext()

        assertEquals(listOf(APP), calls.map { it.applicationId })
        assertEquals(0, scheduler.due)
        assertTrue(follower.lastOutcome is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `the same selection again asks nothing more`() {
        follower.follow(device, APP)
        runNext()
        follower.follow(device, APP)

        assertEquals(0, scheduler.due)
        assertEquals(1, calls.size)
    }

    @Test
    fun `an app that is not Flutter, or a release build, is never attached to`() {
        builds[APP] = null
        follower.follow(device, APP)
        runNext()
        builds[OTHER_APP] = FlutterBuild.RELEASE
        follower.follow(device, OTHER_APP)
        runNext()

        assertEquals(emptyList<Call>(), calls)
        assertEquals(0, scheduler.due)
    }

    @Test
    fun `no device or no app, nothing is asked`() {
        follower.follow(null, APP)
        follower.follow(device, " ")

        assertEquals(0, scheduler.due)
    }

    @Test
    fun `not ready is retried with backoff until an outcome that is not`() {
        repeat(4) { outcomes += notReady() }
        follower.follow(device, APP)

        val delays = (1..5).map { runNext() }

        assertEquals(listOf(0L) + FlutterFollower.BACKOFF_MS, delays)
        assertEquals(5, calls.size)
        assertEquals(0, scheduler.due, "connected: nothing more to ask")
    }

    @Test
    fun `the service's suggestion is waited for when it is longer than the step`() {
        outcomes += FlutterAttachOutcome.NotReady("the direct VM is probed later", 2_700)
        follower.follow(device, APP)
        runNext()

        assertEquals(2_700L, runNext())
    }

    @Test
    fun `a selection change stops the retries of the old app`() {
        repeat(3) { outcomes += notReady() }
        follower.follow(device, APP)
        runNext()
        assertEquals(1, scheduler.due, "a retry is due")

        follower.follow(device, OTHER_APP)

        assertEquals(1, scheduler.due, "the old retry is cancelled; the new app's attempt is due")
        runNext()
        assertEquals(listOf(APP, OTHER_APP), calls.map { it.applicationId })
    }

    @Test
    fun `an attempt still running when the selection changes is not acted on`() {
        outcomes += notReady()
        duringAttach = {
            duringAttach = {}
            follower.follow(other, APP)
        }
        follower.follow(device, APP)
        runNext()

        assertEquals(1, scheduler.due, "only the new device's attempt; the old NotReady scheduled nothing")
        assertEquals(null, follower.lastOutcome, "the old device's answer is not the new selection's")
    }

    @Test
    fun `a new pid starts the backoff over`() {
        repeat(3) { outcomes += notReady() }
        follower.follow(device, APP)
        runNext()
        runNext()
        pids = setOf(5151)

        runNext()

        assertEquals(FlutterFollower.BACKOFF_MS.first(), runNext())
    }

    @Test
    fun `the app's process start asks at once, with when it started`() {
        follower.follow(device, APP)
        runNext()

        follower.processStarted(device.serialNumber, APP, pid = 5151, hostMs = 1_000_000)
        pids = setOf(5151)
        runNext()

        assertEquals(listOf(null, 1_000_000L), calls.map { it.startedAt })
    }

    @Test
    fun `another app's process start is not ours`() {
        follower.follow(device, APP)
        runNext()

        follower.processStarted(device.serialNumber, OTHER_APP, pid = 5151, hostMs = 1)
        follower.processStarted(other.serialNumber, APP, pid = 5151, hostMs = 1)

        assertEquals(0, scheduler.due)
    }

    @Test
    fun `the Settings switch is passed as it is at each attempt`() {
        recordHttp = false
        follower.follow(device, APP)
        runNext()

        assertEquals(listOf(false), calls.map { it.recordHttp })
    }

    @Test
    fun `adb failing to list the app's processes is retried`() {
        pids = null
        follower.follow(device, APP)
        runNext()

        assertEquals(emptyList<Call>(), calls)
        assertEquals(1, scheduler.due)
    }

    @Test
    fun `Diagnose asks even when there is no debugger session`() {
        outcomes += FlutterAttachOutcome.NoDdsSession(identity(), FlutterSessionService.NO_DDS_MESSAGE)
        follower.follow(device, APP)
        runNext()

        val outcome = follower.attachNow(device, APP, FlutterBuild.DEBUG, budgetMs = 5_000)

        assertTrue(outcome is FlutterAttachOutcome.Connected, "$outcome")
        assertEquals(2, calls.size)
    }

    @Test
    fun `Diagnose on a release build asks nothing`() {
        val outcome = follower.attachNow(device, APP, FlutterBuild.RELEASE, budgetMs = 5_000)

        assertTrue(outcome is FlutterAttachOutcome.ReleaseBuild)
        assertEquals(0, calls.size)
    }

    @Test
    fun `Diagnose waits only its budget, and says the app is starting`() {
        val slow = FlutterFollower(
            attach = { _, app, _, _, _ ->
                Thread.sleep(SLOW_MS)
                connected(app)
            },
            buildOf = { _, _ -> FlutterBuild.DEBUG },
            pidsOf = { _, _ -> setOf(1L) },
            recordHttp = { true },
            scheduler = scheduler,
            background = { CompletableFuture.runAsync(it) },
        )

        val outcome = slow.attachNow(device, APP, FlutterBuild.DEBUG, budgetMs = 50)

        assertTrue(outcome is FlutterAttachOutcome.NotReady, "$outcome")
        assertEquals(FlutterFollower.STILL_ATTACHING, (outcome as FlutterAttachOutcome.NotReady).reason)
        slow.dispose()
    }

    @Test
    fun `a NotReady from Diagnose for the selected app keeps the backoff going`() {
        follower.follow(device, APP)
        runNext()
        outcomes += notReady()

        follower.attachNow(device, APP, FlutterBuild.DEBUG, budgetMs = 5_000)

        assertEquals(1, scheduler.due)
    }

    @Test
    fun `nothing runs after dispose`() {
        outcomes += notReady()
        follower.follow(device, APP)
        follower.dispose()

        scheduler.tasks.forEach { it.runnable.run() }

        assertEquals(emptyList<Call>(), calls)
    }

    @Test
    fun `the retries stop when the run's time is spent, not after a count`() {
        repeat(MANY) { outcomes += notReady() }
        follower.follow(device, APP)
        val start = now

        while (scheduler.due > 0) runNext()

        assertTrue(now - start <= FlutterFollower.FOLLOW_BUDGET_MS, "${now - start} ms")
        assertTrue(now - start > FlutterFollower.FOLLOW_BUDGET_MS - FlutterFollower.BACKOFF_MS.last() * 2)
        assertTrue(logged.any { it.contains("giving up on $APP on emulator-5554") }, "$logged")
    }

    @Test
    fun `a process start pidof does not see yet is asked again until it does`() {
        // Seen on emulator-5554: ActivityManager logs the start 2 s before the process has its name.
        follower.follow(device, APP)
        pids = emptySet()
        runNext()
        follower.processStarted(device.serialNumber, APP, pid = 4405, hostMs = now)

        runNext()
        runNext()
        assertEquals(emptyList<Call>(), calls, "not running yet: nothing to attach to")
        assertEquals(1, scheduler.due, "but asked again")
        pids = setOf(4405)
        runNext()

        assertEquals(1, calls.size)
        assertTrue(follower.lastOutcome is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `an app selected while not running waits for its process`() {
        pids = emptySet()
        follower.follow(device, APP)
        runNext()

        assertEquals(0, scheduler.due)
        assertTrue(logged.any { "waiting for its process to start" in it }, "$logged")
    }

    @Test
    fun `a failure and nothing found yet are asked again`() {
        outcomes += FlutterAttachOutcome.Failed("the VM did not answer")
        outcomes += FlutterAttachOutcome.NotFound("nothing logged yet")
        follower.follow(device, APP)

        repeat(3) { runNext() }

        assertEquals(3, calls.size)
        assertTrue(follower.lastOutcome is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `no debugger session is checked again, and a later flutter attach is connected`() {
        outcomes += FlutterAttachOutcome.NoDdsSession(identity(), FlutterSessionService.NO_DDS_MESSAGE)
        follower.follow(device, APP)
        runNext()

        assertEquals(FlutterFollower.NO_DDS_RECHECK_MS, runNext())
        assertEquals(2, calls.size)
        assertTrue(follower.lastOutcome is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `no debugger session is checked a bounded number of times`() {
        repeat(MANY) { outcomes += FlutterAttachOutcome.NoDdsSession(identity(), FlutterSessionService.NO_DDS_MESSAGE) }
        follower.follow(device, APP)

        while (scheduler.due > 0) runNext()

        assertEquals(FlutterFollower.NO_DDS_RECHECKS + 1, calls.size)
    }

    @Test
    fun `the selected app's session lost starts a run, with no device log`() {
        follower.follow(device, APP)
        runNext()

        follower.sessionChanged(FlutterSessionChange.Disconnected(FlutterSession(), identity(), "connection closed"))
        runNext()

        assertEquals(2, calls.size)
    }

    @Test
    fun `Spock disconnecting on purpose does not reconnect`() {
        follower.follow(device, APP)
        runNext()

        follower.sessionChanged(
            FlutterSessionChange.Disconnected(
                FlutterSession(),
                identity(),
                FlutterSessionService.DISCONNECTED_BY_SPOCK,
            ),
        )

        assertEquals(0, scheduler.due)
    }

    @Test
    fun `another app's session taking the selected app's place is followed by the selected app again, later`() {
        follower.follow(device, APP)
        runNext()
        val otherApp = AppIdentity(device.serialNumber, OTHER_APP, 5151, IdentityCheck.DTD_PID_START)

        follower.sessionChanged(FlutterSessionChange.Replaced(FlutterSession(), identity(), FlutterSession(), otherApp))

        assertEquals(FlutterFollower.REFOLLOW_DELAY_MS, runNext())
        assertEquals(listOf(APP, APP), calls.map { it.applicationId })
    }

    @Test
    fun `the follower's own session changes start nothing`() {
        follower.follow(device, APP)
        runNext()

        follower.sessionChanged(FlutterSessionChange.Connected(FlutterSession(), identity()))

        assertEquals(0, scheduler.due)
    }

    @Test
    fun `its log lines carry no VM Service address`() {
        outcomes += FlutterAttachOutcome.NotReady("The VM at ws://127.0.0.1:41234/$TOKEN/ws did not answer.", 500)
        outcomes += FlutterAttachOutcome.Failed("http://127.0.0.1:41234/$TOKEN/ refused")
        follower.follow(device, APP)
        repeat(3) { runNext() }
        follower.attachNow(device, APP, FlutterBuild.DEBUG, budgetMs = 5_000)

        assertTrue(logged.size >= 4, "$logged")
        assertTrue(logged.none { TOKEN in it || TOKEN.removeSuffix("=") in it }, "$logged")
        assertTrue(logged.any { "selection changed: following $APP on emulator-5554" in it }, "$logged")
    }

    private fun notReady() = FlutterAttachOutcome.NotReady("the app is starting", FlutterSessionService.RETRY_MS / 4)

    private fun identity() = AppIdentity(device.serialNumber, APP, 4242, IdentityCheck.LOGCAT_PID)

    private fun connected(app: String) = FlutterAttachOutcome.Connected(
        FlutterSession(),
        AppIdentity(device.serialNumber, app, 4242, IdentityCheck.DTD_PID_START),
        reused = false,
    )

    private companion object {
        const val APP = "spock.adb.spock_flutter_sample"
        const val OTHER_APP = "spock.adb.spock_flutter_sample.second"
        const val SLOW_MS = 1_000L
        const val MANY = 200

        /** The fake token the other token tests use. */
        const val TOKEN = spock.adb.flutter.vmservice.FakeVmService.TOKEN

        fun device(serial: String) = ConnectedDevice(
            mockk<IDevice>(relaxed = true),
            DeviceInfo.unknown(serial).copy(state = DeviceState.ONLINE),
        )
    }
}
