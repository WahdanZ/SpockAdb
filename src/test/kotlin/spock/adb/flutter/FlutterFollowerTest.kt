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
    )

    @AfterEach
    fun tearDown() = follower.dispose()

    @Test
    fun `a selection change asks once`() {
        follower.follow(device, APP)
        scheduler.runNext()

        assertEquals(listOf(APP), calls.map { it.applicationId })
        assertEquals(0, scheduler.due)
        assertTrue(follower.lastOutcome is FlutterAttachOutcome.Connected)
    }

    @Test
    fun `the same selection again asks nothing more`() {
        follower.follow(device, APP)
        scheduler.runNext()
        follower.follow(device, APP)

        assertEquals(0, scheduler.due)
        assertEquals(1, calls.size)
    }

    @Test
    fun `an app that is not Flutter, or a release build, is never attached to`() {
        builds[APP] = null
        follower.follow(device, APP)
        scheduler.runNext()
        builds[OTHER_APP] = FlutterBuild.RELEASE
        follower.follow(device, OTHER_APP)
        scheduler.runNext()

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
        outcomes += FlutterAttachOutcome.NotFound("nothing named the app's VM Service")
        follower.follow(device, APP)

        val delays = (1..5).map { scheduler.runNext() }

        assertEquals(listOf(0L) + FlutterFollower.BACKOFF_MS, delays)
        assertEquals(5, calls.size)
        assertEquals(0, scheduler.due, "a terminal outcome ends the retries")
    }

    @Test
    fun `the service's suggestion is waited for when it is longer than the step`() {
        outcomes += FlutterAttachOutcome.NotReady("the direct VM is probed later", 2_700)
        follower.follow(device, APP)
        scheduler.runNext()

        assertEquals(2_700L, scheduler.runNext())
    }

    @Test
    fun `the retries stop when the attempts are spent`() {
        repeat(FlutterFollower.MAX_ATTEMPTS + 5) { outcomes += notReady() }
        follower.follow(device, APP)

        repeat(FlutterFollower.MAX_ATTEMPTS + 1) { scheduler.runNext() }

        assertEquals(FlutterFollower.MAX_ATTEMPTS + 1, calls.size)
        assertEquals(0, scheduler.due)
    }

    @Test
    fun `a selection change stops the retries of the old app`() {
        repeat(3) { outcomes += notReady() }
        follower.follow(device, APP)
        scheduler.runNext()
        assertEquals(1, scheduler.due, "a retry is due")

        follower.follow(device, OTHER_APP)

        assertEquals(1, scheduler.due, "the old retry is cancelled; the new app's attempt is due")
        scheduler.runNext()
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
        scheduler.runNext()

        assertEquals(1, scheduler.due, "only the new device's attempt; the old NotReady scheduled nothing")
        assertEquals(null, follower.lastOutcome, "the old device's answer is not the new selection's")
    }

    @Test
    fun `a terminal outcome is not asked again for the same process, and a new pid asks again`() {
        outcomes += FlutterAttachOutcome.NoDdsSession(identity(), FlutterSessionService.NO_DDS_MESSAGE)
        follower.follow(device, APP)
        scheduler.runNext()
        follower.follow(device, OTHER_APP)
        scheduler.runNext()
        follower.follow(device, APP)
        scheduler.runNext()

        assertEquals(listOf(APP, OTHER_APP), calls.map { it.applicationId }, "remembered for pid 4242")

        pids = setOf(5151)
        follower.follow(device, OTHER_APP)
        scheduler.runNext()
        follower.follow(device, APP)
        scheduler.runNext()

        assertEquals(listOf(APP, OTHER_APP, OTHER_APP, APP), calls.map { it.applicationId })
    }

    @Test
    fun `a new pid starts the backoff over`() {
        repeat(3) { outcomes += notReady() }
        follower.follow(device, APP)
        scheduler.runNext()
        scheduler.runNext()
        pids = setOf(5151)

        scheduler.runNext()

        assertEquals(FlutterFollower.BACKOFF_MS.first(), scheduler.runNext())
    }

    @Test
    fun `the app's process start asks at once, with when it started`() {
        follower.follow(device, APP)
        scheduler.runNext()

        follower.processStarted(device.serialNumber, APP, pid = 5151, hostMs = 1_000_000)
        pids = setOf(5151)
        scheduler.runNext()

        assertEquals(listOf(null, 1_000_000L), calls.map { it.startedAt })
    }

    @Test
    fun `another app's process start is not ours`() {
        follower.follow(device, APP)
        scheduler.runNext()

        follower.processStarted(device.serialNumber, OTHER_APP, pid = 5151, hostMs = 1)
        follower.processStarted(other.serialNumber, APP, pid = 5151, hostMs = 1)

        assertEquals(0, scheduler.due)
    }

    @Test
    fun `the Settings switch is passed as it is at each attempt`() {
        recordHttp = false
        follower.follow(device, APP)
        scheduler.runNext()

        assertEquals(listOf(false), calls.map { it.recordHttp })
    }

    @Test
    fun `adb failing to list the app's processes is retried`() {
        pids = null
        follower.follow(device, APP)
        scheduler.runNext()

        assertEquals(emptyList<Call>(), calls)
        assertEquals(1, scheduler.due)
    }

    @Test
    fun `Diagnose asks even when a terminal outcome is remembered`() {
        outcomes += FlutterAttachOutcome.NotFound("nothing")
        follower.follow(device, APP)
        scheduler.runNext()

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
        scheduler.runNext()
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

        fun device(serial: String) = ConnectedDevice(
            mockk<IDevice>(relaxed = true),
            DeviceInfo.unknown(serial).copy(state = DeviceState.ONLINE),
        )
    }
}
