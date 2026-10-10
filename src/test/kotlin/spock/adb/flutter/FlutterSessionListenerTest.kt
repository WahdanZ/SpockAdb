package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Who hears a state change, on which thread, and when listeners come and go. */
class FlutterSessionListenerTest : FlutterSessionFixture() {

    /** Records every state it hears and the thread it heard it on. */
    private class Recorder : FlutterSessionListener {
        val states = CopyOnWriteArrayList<SessionState>()
        val threads = CopyOnWriteArrayList<Thread>()

        override fun onEvent(event: FlutterEvent) = Unit

        override fun onStateChanged(state: SessionState) {
            states += state
            threads += Thread.currentThread()
        }
    }

    @Test
    fun `a listener added late with replayState hears the current state first, then changes, none twice`() {
        session.connect(pasted())
        val late = Recorder()

        session.addListener(late, replayState = true)
        eventually(message = "the catch-up") { late.states.isNotEmpty() }
        session.close()

        assertEquals(
            listOf(SessionState.Connected(UI_ISOLATE), SessionState.Disconnected(FlutterSession.CLOSED_BY_SPOCK)),
            late.states.toList(),
        )
    }

    @Test
    fun `the catch-up is never delivered on the thread that added the listener`() {
        session.connect(pasted())
        val late = Recorder()

        session.addListener(late, replayState = true)

        eventually(message = "the catch-up") { late.states.isNotEmpty() }
        assertNotSame(Thread.currentThread(), late.threads.single())
    }

    @Test
    fun `a listener added late without replayState hears only later changes`() {
        session.connect(pasted())
        val late = Recorder()
        session.addListener(late)

        session.close()

        val closed: SessionState = SessionState.Disconnected(FlutterSession.CLOSED_BY_SPOCK)
        assertEquals(listOf(closed), late.states.toList())
    }

    @Test
    fun `a listener added twice is registered once`() {
        session.connect(pasted())
        val twice = Recorder()
        session.addListener(twice)
        session.addListener(twice)

        session.close()

        assertEquals(1, twice.states.size)
    }

    @Test
    fun `a listener removed during a slow delivery hears nothing more`() {
        session.connect(pasted())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : FlutterSessionListener {
            override fun onEvent(event: FlutterEvent) = Unit
            override fun onStateChanged(state: SessionState) {
                if (state is SessionState.Paused) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val removed = Recorder()
        session.addListener(slow)
        session.addListener(removed)

        vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, connectedAt + 1_000))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        session.removeListener(removed)
        release.countDown()
        vm.pushEvent("Debug", isolateEvent("Resume", UI_ISOLATE, connectedAt + 2_000))
        eventually(message = "the resume") { session.state == SessionState.Connected(UI_ISOLATE) }
        Thread.sleep(200)

        assertEquals(emptyList<SessionState>(), removed.states.toList())
    }
}
